package com.application.nox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.localia.imagegen.LocalImage
import com.localia.imagegen.LocalImageGenerator
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * « Agrandir en HD » : Real-ESRGAN ×4 sur le téléphone, par le moteur de Lia (stable-diffusion.cpp).
 * Comme dans Lia, l'image FLUX (1024 px) est d'abord ramenée à ~512×768 px, puis agrandie ×4 :
 * environ 2048 px nets au final. Modèle « photo » (64 Mo) ou « illustration » (17 Mo), téléchargé une fois.
 */
object ImageHd {

    data class Upscaler(val id: String, val label: String, val url: String, val mb: Int)

    val PHOTO = Upscaler("realesrgan-x4plus", "photo", "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth", 64)
    val ILLUSTRATION = Upscaler("realesrgan-x4plus-anime-6b", "illustration",
        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth", 17)

    private const val MAX_SOURCE_PIXELS = 512 * 768
    private val PHOTO_WORDS = Regex("\\b(?:photos?|photographi\\w*|r[ée]alistes?|realistic|photorealistic|portrait|paysage)\\b", RegexOption.IGNORE_CASE)

    fun upscalerFor(prompt: String) = if (PHOTO_WORDS.containsMatchIn(prompt) || !Regex("\\b(?:dessin|illustration|manga|bd|cartoon|aquarelle|peinture|logo|affiche)\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)) PHOTO else ILLUSTRATION

    private fun file(u: Upscaler) = File(Nox.store.dir(), "${u.id}.pth")

    /** Agrandit [jpeg] ; [stage] reçoit l'étape (téléchargement, agrandissement). Bloquant : à lancer hors du fil principal. */
    fun enlarge(jpeg: ByteArray, prompt: String, stage: (String) -> Unit): ByteArray {
        val u = upscalerFor(prompt)
        val model = file(u)
        if (!model.exists() || model.length() < u.mb * 900_000L) download(u, model, stage)
        stage("Agrandissement HD (modèle ${u.label}) : souvent 30 s à 2 min")
        val source = decode(jpeg)
        val t0 = System.currentTimeMillis()
        val out = LocalImageGenerator.upscale(model.absolutePath, source, threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
        NoxLog.p("IMAGE", "Agrandissement ${source.width}×${source.height} → ${out.width}×${out.height} en ${System.currentTimeMillis() - t0} ms (${u.id}, moteur ${LocalImageGenerator.engineVariant})")
        return toJpeg(out)
    }

    private fun download(u: Upscaler, target: File, stage: (String) -> Unit) {
        stage("Téléchargement du modèle d'agrandissement (${u.mb} Mo, une seule fois)")
        val tmp = File(target.path + ".part")
        val c = URL(u.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("téléchargement refusé (HTTP ${c.responseCode})")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L; var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last && pct % 5 == 0) { last = pct; stage("Téléchargement du modèle d'agrandissement ${if (pct >= 0) "$pct %" else "${done / 1_000_000} Mo"}") }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IllegalStateException("enregistrement du modèle impossible")
        } finally {
            c.disconnect()
            tmp.delete()
        }
    }

    private fun decode(jpeg: ByteArray): LocalImage {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw IllegalArgumentException("image illisible")
        val px = src.width.toLong() * src.height
        val bmp = if (px > MAX_SOURCE_PIXELS) {
            val k = sqrt(MAX_SOURCE_PIXELS.toDouble() / px)
            Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true).also { src.recycle() }
        } else src
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        val rgb = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[i * 3] = (p shr 16 and 0xff).toByte(); rgb[i * 3 + 1] = (p shr 8 and 0xff).toByte(); rgb[i * 3 + 2] = (p and 0xff).toByte()
        }
        return LocalImage(w, h, rgb)
    }

    private fun toJpeg(img: LocalImage): ByteArray {
        val pixels = IntArray(img.width * img.height)
        val rgb = img.rgb
        for (i in pixels.indices) {
            pixels[i] = (0xff shl 24) or ((rgb[i * 3].toInt() and 0xff) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xff) shl 8) or (rgb[i * 3 + 2].toInt() and 0xff)
        }
        val bmp = Bitmap.createBitmap(pixels, img.width, img.height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it); bmp.recycle() }.toByteArray()
    }
}
