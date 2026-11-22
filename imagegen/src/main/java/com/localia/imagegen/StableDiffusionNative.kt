package com.localia.imagegen

/** Raw JNI declarations for `sd_bridge.cpp`. Use [LocalImageGenerator] instead. */
internal object StableDiffusionNative {
    /** Which build of the engine is loaded: « gpu vulkan », « armv8.2 dotprod+fp16 » or « armv8.0 », for the session log. */
    val variant: String

    /** True when liasd_gpu (see :imagegen-gpu) was loaded. */
    val gpu: Boolean

    init {
        // liasd_gpu only when « GPU (essai) » asked for it before the first
        // image; the fast build (see :imagegen-fast) only where the CPU has
        // the instructions it was compiled for; anything else the plain one.
        val fastCpu = CpuFeatures.hasDotProdAndFp16()
        gpu = LocalImageGenerator.preferGpu && fastCpu && runCatching { System.loadLibrary("liasd_gpu") }.isSuccess
        variant = when {
            gpu -> "gpu vulkan"
            fastCpu && runCatching { System.loadLibrary("liasd_fast") }.isSuccess -> "armv8.2 dotprod+fp16"
            else -> {
                System.loadLibrary("liasd")
                "armv8.0"
            }
        }
        LocalImageGenerator.engineLoaded = true
    }

    /** Loads [modelPath], generates one image, frees everything. Returns tightly packed RGB bytes (actual size written to [outSize] as width, height), or null on failure — see [nativeLastError]. Blocking, can take tens of seconds. */
    external fun nativeGenerate(
        modelPath: String,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        seed: Long,
        threads: Int,
        /** stable-diffusion.cpp sampler name ("lcm", "euler_a"…); empty for the model's default. */
        sampleMethod: String,
        outSize: IntArray,
        convertToQ8: Boolean,
        paramsBackend: String,
        mmap: Boolean,
        /** A separate decoder (VAE) file; empty for the model's own. */
        vaePath: String,
    ): ByteArray?

    /** Img2img pass over [rgb] ([width]x[height], tightly packed RGB): [strength] of a [steps]-step schedule is redone. Same return contract as [nativeGenerate]. */
    external fun nativeImg2Img(
        modelPath: String,
        prompt: String,
        negativePrompt: String,
        rgb: ByteArray,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        strength: Float,
        seed: Long,
        threads: Int,
        sampleMethod: String,
        outSize: IntArray,
        convertToQ8: Boolean,
        paramsBackend: String,
        mmap: Boolean,
        /** A separate decoder (VAE) file; empty for the model's own. */
        vaePath: String,
    ): ByteArray?

    /** ESRGAN-enlarges a tightly packed RGB image with the model at [modelPath] (x4 for RealESRGAN), loaded and freed in this call. Same return contract as [nativeGenerate]. */
    external fun nativeUpscale(
        modelPath: String,
        rgb: ByteArray,
        width: Int,
        height: Int,
        threads: Int,
        tileSize: Int,
        outSize: IntArray,
    ): ByteArray?

    /** Writes [inputPath] as an 8-bit GGUF at [outputPath] (decoder kept 16-bit). Blocking, minutes. */
    external fun nativeConvertToQ8(inputPath: String, outputPath: String): Boolean

    external fun nativeCancel()

    /** Current step in the high 32 bits, total steps in the low 32 bits. */
    external fun nativeProgress(): Long

    external fun nativeLastError(): String
}
