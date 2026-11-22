// JNI bridge between StableDiffusionNative.kt and stable-diffusion.cpp
// (include/stable-diffusion.h at the commit pinned in CMakeLists.txt).
//
// One call does load + generate + free: an image model is only needed for
// the few seconds of a generation, and keeping it resident would hold
// ~1 GB of RAM next to the chat model for nothing.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define TAG "lia-sd"

namespace {

std::mutex g_generation_mutex;  // one generation at a time
std::mutex g_ctx_mutex;         // guards g_ctx for nativeCancel
sd_ctx_t* g_ctx = nullptr;
std::atomic<bool> g_cancel_requested{false};
std::atomic<int> g_step{0};
std::atomic<int> g_steps{0};

std::mutex g_error_mutex;
std::string g_last_error;

void set_last_error(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = message;
}

void log_callback(enum sd_log_level_t level, const char* text, void*) {
    if (text == nullptr) return;
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case SD_LOG_ERROR: priority = ANDROID_LOG_ERROR; break;
        case SD_LOG_WARN:  priority = ANDROID_LOG_WARN; break;
        case SD_LOG_INFO:  priority = ANDROID_LOG_INFO; break;
        default:           priority = ANDROID_LOG_DEBUG; break;
    }
    __android_log_print(priority, TAG, "%s", text);
    if (level == SD_LOG_ERROR) {
        std::string message(text);
        while (!message.empty() && (message.back() == '\n' || message.back() == '\r')) message.pop_back();
        set_last_error(message);
    }
}

void progress_callback(int step, int steps, float, void*) {
    g_step.store(step);
    g_steps.store(steps);
}

std::string to_std_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

// Load + generate + free. With an [init_rgb] picture, an img2img pass over
// it at [width]x[height]: [strength] of the schedule is redone (0.4 of 10
// steps = 4 steps), which redraws fine detail and keeps the composition.
static jbyteArray run_generation(
        JNIEnv* env,
        jstring jModelPath, jstring jPrompt, jstring jNegativePrompt,
        jint width, jint height, jint steps, jfloat cfgScale, jlong seed, jint threads,
        jstring jSampleMethod, jintArray jOutSize,
        uint8_t* init_rgb, float strength,
        jboolean convertToQ8, jstring jParamsBackend, jboolean mmap, jstring jVaePath) {
    std::lock_guard<std::mutex> generation_lock(g_generation_mutex);

    set_last_error("");
    g_cancel_requested.store(false);
    g_step.store(0);
    g_steps.store(steps);

    const std::string model_path = to_std_string(env, jModelPath);
    const std::string prompt = to_std_string(env, jPrompt);
    const std::string negative_prompt = to_std_string(env, jNegativePrompt);
    const std::string sample_method_name = to_std_string(env, jSampleMethod);
    const std::string params_backend = to_std_string(env, jParamsBackend);
    const std::string vae_path = to_std_string(env, jVaePath);

    sd_set_log_callback(log_callback, nullptr);
    sd_set_progress_callback(progress_callback, nullptr);

    sd_ctx_params_t ctx_params;
    sd_ctx_params_init(&ctx_params);
    ctx_params.model_path = model_path.c_str();
    ctx_params.n_threads = threads;
    // Same picture, less time and memory: exact (flash) attention in the UNet,
    // and 16-bit checkpoints (Absolute Reality, ChilloutMix, DreamShaper)
    // turned into 8-bit weights the dot-product build of liasd_fast reads
    // fast — Q8_0 is visually lossless. Already-Q8_0 files are left as they
    // are. The VAE (first_stage_model) is kept in 16 bits, as before: 8-bit
    // VAE weights show as grain and banding on the final picture.
    ctx_params.diffusion_flash_attn = true;
    if (convertToQ8) {
        ctx_params.wtype = SD_TYPE_Q8_0;
        ctx_params.tensor_type_rules = "first_stage_model=f16";
    }
    // Weights read from the file only while their part runs ("te=disk,vae=disk":
    // the text encoder and the decoder; "all=disk": everything, for models
    // bigger than the free memory), through mmap so Android can drop those
    // pages under pressure instead of killing the app.
    ctx_params.enable_mmap = mmap;
    if (!params_backend.empty()) ctx_params.params_backend = params_backend.c_str();
#ifdef LIA_SD_GPU
    // liasd_gpu (« GPU (essai) »): only the drawing steps run on the GPU,
    // the bulk of the time. The text encoder and the decoder stay on the
    // CPU, as they are in the CPU builds.
    ctx_params.backend = "diffusion=vulkan,te=cpu,vae=cpu";
#endif
    // A full-precision decoder file instead of the one inside the model: the
    // 4/8-bit decoders of compressed models leave fine vertical stripes.
    if (!vae_path.empty()) {
        ctx_params.vae_path = vae_path.c_str();
        // sd.cpp drops its SDXL decoder overflow guard (conv scale 1/32) as
        // soon as a separate decoder is given, assuming the « fp16-fix » one;
        // the original sdxl_vae then overflows into an all-white picture.
        ctx_params.force_sdxl_vae_conv_scale = true;
    }
    // The VAE decode unfolded every 3x3 convolution of a 512x768 picture into
    // one huge buffer (im2col): the app peaked at 3.2 GB and Android had 71 MB
    // left, so the last stage took 1.5 to 3.5 minutes. Direct convolution
    // computes the same result in small chunks.
    ctx_params.vae_conv_direct = true;

    sd_ctx_t* ctx = new_sd_ctx(&ctx_params);
    if (ctx == nullptr) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        if (g_last_error.empty()) g_last_error = "model load failed";
        return nullptr;
    }

    {
        std::lock_guard<std::mutex> lock(g_ctx_mutex);
        g_ctx = ctx;
    }
    if (g_cancel_requested.load()) {
        {
            std::lock_guard<std::mutex> lock(g_ctx_mutex);
            g_ctx = nullptr;
        }
        free_sd_ctx(ctx);
        set_last_error("cancelled");
        return nullptr;
    }

    sd_img_gen_params_t gen_params;
    sd_img_gen_params_init(&gen_params);
    gen_params.prompt = prompt.c_str();
    gen_params.negative_prompt = negative_prompt.c_str();
    gen_params.width = width;
    gen_params.height = height;
    gen_params.seed = seed;
    gen_params.batch_count = 1;
    gen_params.sample_params.sample_steps = steps;
    gen_params.sample_params.guidance.txt_cfg = cfgScale;
    // Empty or unknown name: the model's own default (e.g. LCM models need "lcm").
    sample_method_t sample_method = sd_get_default_sample_method(ctx);
    if (!sample_method_name.empty()) {
        const sample_method_t requested = str_to_sample_method(sample_method_name.c_str());
        if (requested != SAMPLE_METHOD_COUNT) sample_method = requested;
    }
    gen_params.sample_params.sample_method = sample_method;
    gen_params.sample_params.scheduler = sd_get_default_scheduler(ctx, gen_params.sample_params.sample_method);
    if (init_rgb != nullptr) {
        gen_params.init_image = sd_image_t{static_cast<uint32_t>(width), static_cast<uint32_t>(height), 3, init_rgb};
        gen_params.strength = strength;
    }

    sd_image_t* images = nullptr;
    int image_count = 0;
    const bool ok = generate_image(ctx, &gen_params, &images, &image_count);

    {
        std::lock_guard<std::mutex> lock(g_ctx_mutex);
        g_ctx = nullptr;
    }

    jbyteArray result = nullptr;
    if (ok && image_count > 0 && images != nullptr && images[0].data != nullptr && images[0].channel >= 3) {
        const sd_image_t& image = images[0];
        const size_t pixel_count = static_cast<size_t>(image.width) * image.height;
        std::vector<uint8_t> rgb(pixel_count * 3);
        if (image.channel == 3) {
            std::memcpy(rgb.data(), image.data, rgb.size());
        } else {
            for (size_t i = 0; i < pixel_count; ++i) {
                rgb[i * 3] = image.data[i * image.channel];
                rgb[i * 3 + 1] = image.data[i * image.channel + 1];
                rgb[i * 3 + 2] = image.data[i * image.channel + 2];
            }
        }
        result = env->NewByteArray(static_cast<jsize>(rgb.size()));
        if (result != nullptr) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<const jbyte*>(rgb.data()));
        }
        if (jOutSize != nullptr && env->GetArrayLength(jOutSize) >= 2) {
            const jint size[2] = {static_cast<jint>(image.width), static_cast<jint>(image.height)};
            env->SetIntArrayRegion(jOutSize, 0, 2, size);
        }
    } else {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        if (g_cancel_requested.load()) {
            g_last_error = "cancelled";
        } else if (g_last_error.empty()) {
            g_last_error = "generation failed";
        }
    }

    if (images != nullptr) free_sd_images(images, image_count);
    free_sd_ctx(ctx);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_localia_imagegen_StableDiffusionNative_nativeGenerate(
        JNIEnv* env, jobject,
        jstring jModelPath, jstring jPrompt, jstring jNegativePrompt,
        jint width, jint height, jint steps, jfloat cfgScale, jlong seed, jint threads,
        jstring jSampleMethod, jintArray jOutSize,
        jboolean convertToQ8, jstring jParamsBackend, jboolean mmap, jstring jVaePath) {
    return run_generation(env, jModelPath, jPrompt, jNegativePrompt, width, height, steps, cfgScale, seed, threads,
                          jSampleMethod, jOutSize, nullptr, 1.f, convertToQ8, jParamsBackend, mmap, jVaePath);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_localia_imagegen_StableDiffusionNative_nativeImg2Img(
        JNIEnv* env, jobject,
        jstring jModelPath, jstring jPrompt, jstring jNegativePrompt, jbyteArray jRgb,
        jint width, jint height, jint steps, jfloat cfgScale, jfloat strength, jlong seed, jint threads,
        jstring jSampleMethod, jintArray jOutSize,
        jboolean convertToQ8, jstring jParamsBackend, jboolean mmap, jstring jVaePath) {
    const jsize length = env->GetArrayLength(jRgb);
    if (length != width * height * 3) {
        set_last_error("init image size mismatch");
        return nullptr;
    }
    std::vector<uint8_t> rgb(static_cast<size_t>(length));
    env->GetByteArrayRegion(jRgb, 0, length, reinterpret_cast<jbyte*>(rgb.data()));
    return run_generation(env, jModelPath, jPrompt, jNegativePrompt, width, height, steps, cfgScale, seed, threads,
                          jSampleMethod, jOutSize, rgb.data(), strength, convertToQ8, jParamsBackend, mmap, jVaePath);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localia_imagegen_StableDiffusionNative_nativeCancel(JNIEnv*, jobject) {
    g_cancel_requested.store(true);
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (g_ctx != nullptr) sd_cancel_generation(g_ctx, SD_CANCEL_ALL);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localia_imagegen_StableDiffusionNative_nativeProgress(JNIEnv*, jobject) {
    const int64_t step = g_step.load();
    const int64_t steps = g_steps.load();
    return static_cast<jlong>((step << 32) | (steps & 0xffffffffLL));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localia_imagegen_StableDiffusionNative_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

// ESRGAN enlargement (x4 for the RealESRGAN models offered by the app).
// Shares the generation lock: an upscale never runs next to a generation.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_localia_imagegen_StableDiffusionNative_nativeUpscale(
        JNIEnv* env, jobject,
        jstring jModelPath, jbyteArray jRgb, jint width, jint height, jint threads, jint tileSize,
        jintArray jOutSize) {
    std::lock_guard<std::mutex> generation_lock(g_generation_mutex);
    set_last_error("");
    sd_set_log_callback(log_callback, nullptr);

    const std::string model_path = to_std_string(env, jModelPath);
    const jsize input_length = jRgb != nullptr ? env->GetArrayLength(jRgb) : 0;
    if (width <= 0 || height <= 0 || input_length != width * height * 3) {
        set_last_error("invalid input image");
        return nullptr;
    }
    std::vector<uint8_t> input(static_cast<size_t>(input_length));
    env->GetByteArrayRegion(jRgb, 0, input_length, reinterpret_cast<jbyte*>(input.data()));

#ifdef LIA_SD_GPU
    const char* upscaler_backend = "vulkan";
#else
    const char* upscaler_backend = nullptr;
#endif
    upscaler_ctx_t* upscaler = new_upscaler_ctx(model_path.c_str(), false, threads, tileSize, upscaler_backend, nullptr);
    if (upscaler == nullptr) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        if (g_last_error.empty()) g_last_error = "upscaler load failed";
        return nullptr;
    }

    sd_image_t source = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 3, input.data()};
    sd_image_t* outputs = nullptr;
    int output_count = 0;
    const bool ok = upscale(upscaler, source, 4, &outputs, &output_count);

    jbyteArray result = nullptr;
    if (ok && output_count > 0 && outputs != nullptr && outputs[0].data != nullptr && outputs[0].channel >= 3) {
        const sd_image_t& image = outputs[0];
        const size_t pixel_count = static_cast<size_t>(image.width) * image.height;
        std::vector<uint8_t> rgb(pixel_count * 3);
        if (image.channel == 3) {
            std::memcpy(rgb.data(), image.data, rgb.size());
        } else {
            for (size_t i = 0; i < pixel_count; ++i) {
                rgb[i * 3] = image.data[i * image.channel];
                rgb[i * 3 + 1] = image.data[i * image.channel + 1];
                rgb[i * 3 + 2] = image.data[i * image.channel + 2];
            }
        }
        result = env->NewByteArray(static_cast<jsize>(rgb.size()));
        if (result != nullptr) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<const jbyte*>(rgb.data()));
        }
        if (jOutSize != nullptr && env->GetArrayLength(jOutSize) >= 2) {
            const jint size[2] = {static_cast<jint>(image.width), static_cast<jint>(image.height)};
            env->SetIntArrayRegion(jOutSize, 0, 2, size);
        }
    } else {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        if (g_last_error.empty()) g_last_error = "upscale failed";
    }

    if (outputs != nullptr) free_sd_images(outputs, output_count);
    free_upscaler_ctx(upscaler);
    return result;
}

// Writes [jInput] (a 16-bit .safetensors/.ckpt checkpoint) as a GGUF file
// with 8-bit weights and a 16-bit decoder — what every generation otherwise
// redid in memory first (2 min 30 for DreamShaper 8). Done once per model.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_localia_imagegen_StableDiffusionNative_nativeConvertToQ8(
        JNIEnv* env, jobject, jstring jInput, jstring jOutput) {
    std::lock_guard<std::mutex> generation_lock(g_generation_mutex);
    set_last_error("");
    sd_set_log_callback(log_callback, nullptr);
    const std::string input = to_std_string(env, jInput);
    const std::string output = to_std_string(env, jOutput);
    const bool ok = convert(input.c_str(), nullptr, output.c_str(), SD_TYPE_Q8_0, "first_stage_model=f16", false);
    if (!ok) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        if (g_last_error.empty()) g_last_error = "conversion failed";
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}
