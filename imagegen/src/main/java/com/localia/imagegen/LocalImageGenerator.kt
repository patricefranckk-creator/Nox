package com.localia.imagegen

import java.util.concurrent.atomic.AtomicInteger

/** A generated image as tightly packed RGB bytes (3 bytes per pixel, row by row). */
class LocalImage(val width: Int, val height: Int, val rgb: ByteArray)

class LocalImageGenerationException(message: String) : Exception(message)

/**
 * How a model's weights are held during a generation. [STANDARD]: the part
 * that draws stays in memory (16-bit files turned into 8-bit), the text
 * encoder and the decoder are read from the file only while they run.
 * [STREAMING]: every part is read from the file when it runs, as stored —
 * for models bigger than the free memory (SDXL), slower but not killed.
 */
enum class WeightMode(val convertToQ8: Boolean, val paramsBackend: String) {
    STANDARD(true, "te=disk,vae=disk"),
    STREAMING(false, "all=disk"),
}

/** On-device text-to-image through stable-diffusion.cpp (GGUF models), fully offline. */
object LocalImageGenerator {

    private val running = AtomicInteger(0)

    /** Which build of the engine runs on this phone (« gpu vulkan », « armv8.2 dotprod+fp16 » or « armv8.0 »); loads it if needed. */
    val engineVariant: String get() = StableDiffusionNative.variant

    /**
     * Load the GPU (Vulkan) build when the engine is first needed. Read once:
     * set it before the first image; a later change applies on the next
     * start of the app.
     */
    @Volatile
    var preferGpu: Boolean = false

    /** True once the engine is loaded: [preferGpu] no longer changes anything until the app restarts. */
    @Volatile
    var engineLoaded: Boolean = false
        internal set

    /** True when the loaded engine is the GPU one (loads it if needed). */
    val usesGpu: Boolean get() = StableDiffusionNative.gpu

    /** True while a generation is in progress (lets callers avoid freeing memory it needs). */
    val isBusy: Boolean get() = running.get() > 0

    /** Blocking — call from a background dispatcher. One generation at a time; a concurrent call waits for the previous one. */
    fun generate(
        modelPath: String,
        prompt: String,
        negativePrompt: String = "",
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        seed: Long = -1L,
        threads: Int = 4,
        sampleMethod: String = "",
        weights: WeightMode = WeightMode.STANDARD,
        /** A separate full-precision decoder file; empty for the model's own. */
        vaePath: String = "",
    ): LocalImage {
        val outSize = IntArray(2)
        running.incrementAndGet()
        val result: ByteArray? = try {
            StableDiffusionNative.nativeGenerate(
                modelPath, prompt, negativePrompt, width, height, steps, cfgScale, seed, threads, sampleMethod, outSize,
                weights.convertToQ8, weights.paramsBackend, true, vaePath,
            )
        } finally {
            running.decrementAndGet()
        }
        val rgb = result ?: throw LocalImageGenerationException(StableDiffusionNative.nativeLastError().ifBlank { "generation failed" })
        val actualWidth = if (outSize[0] > 0) outSize[0] else width
        val actualHeight = if (outSize[1] > 0) outSize[1] else height
        return LocalImage(actualWidth, actualHeight, rgb)
    }

    /**
     * Blocking img2img pass over [image] at its own size: [strength] of a
     * [steps]-step schedule is redone (0.4 of 10 = 4 steps). Used for the
     * « Détails renforcés » second pass over an enlarged picture.
     */
    fun refine(
        modelPath: String,
        prompt: String,
        negativePrompt: String = "",
        image: LocalImage,
        steps: Int,
        cfgScale: Float,
        strength: Float,
        seed: Long = -1L,
        threads: Int = 4,
        sampleMethod: String = "",
        weights: WeightMode = WeightMode.STANDARD,
        /** A separate full-precision decoder file; empty for the model's own. */
        vaePath: String = "",
    ): LocalImage {
        val outSize = IntArray(2)
        running.incrementAndGet()
        val result: ByteArray? = try {
            StableDiffusionNative.nativeImg2Img(
                modelPath, prompt, negativePrompt, image.rgb, image.width, image.height, steps, cfgScale, strength, seed, threads, sampleMethod, outSize,
                weights.convertToQ8, weights.paramsBackend, true, vaePath,
            )
        } finally {
            running.decrementAndGet()
        }
        val rgb = result ?: throw LocalImageGenerationException(StableDiffusionNative.nativeLastError().ifBlank { "refine failed" })
        return LocalImage(if (outSize[0] > 0) outSize[0] else image.width, if (outSize[1] > 0) outSize[1] else image.height, rgb)
    }

    /** Blocking ESRGAN enlargement of [image] (x4 for RealESRGAN models). Processed in [tileSize] px tiles to bound memory. Not cancellable. */
    fun upscale(modelPath: String, image: LocalImage, threads: Int = 4, tileSize: Int = 128): LocalImage {
        val outSize = IntArray(2)
        running.incrementAndGet()
        val result: ByteArray? = try {
            StableDiffusionNative.nativeUpscale(modelPath, image.rgb, image.width, image.height, threads, tileSize, outSize)
        } finally {
            running.decrementAndGet()
        }
        val rgb = result ?: throw LocalImageGenerationException(StableDiffusionNative.nativeLastError().ifBlank { "upscale failed" })
        return LocalImage(outSize[0], outSize[1], rgb)
    }

    /**
     * Writes the 16-bit checkpoint [inputPath] as an 8-bit GGUF file at
     * [outputPath], once, so later pictures skip that conversion. Blocking.
     */
    fun convertToQ8(inputPath: String, outputPath: String) {
        running.incrementAndGet()
        val ok = try {
            StableDiffusionNative.nativeConvertToQ8(inputPath, outputPath)
        } finally {
            running.decrementAndGet()
        }
        if (!ok) throw LocalImageGenerationException(StableDiffusionNative.nativeLastError().ifBlank { "conversion failed" })
    }

    fun cancel() = StableDiffusionNative.nativeCancel()

    /** (current step, total steps) of the running generation. */
    fun progress(): Pair<Int, Int> {
        val packed = StableDiffusionNative.nativeProgress()
        return (packed ushr 32).toInt() to (packed and 0xffffffffL).toInt()
    }
}
