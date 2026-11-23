package com.application.nox

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.pillButton
import com.application.nox.Ui.text
import com.localia.imagegen.LocalImage
import com.localia.imagegen.LocalImageGenerator
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Images créées sur le téléphone, sans internet : le moteur stable-diffusion.cpp de Lia et ses modèles
 * GGUF (mêmes fichiers et mêmes réglages que Lia). Gemma est mis en pause le temps du dessin (la RAM ne
 * suffit pas pour les deux), puis rechargé.
 */
object LocalImages {

    data class Model(val id: String, val label: String, val url: String, val mb: Int, val steps: Int, val cfg: Float,
                     val minFreeMb: Int, val note: String, val negative: String = "",
                     val sampler: String = "", val stream: Boolean = false, val scale: Float = 1f)

    val MODELS = listOf(
        Model("sdxs-512", "SDXS 512 · rapide",
            "https://huggingface.co/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/main/sdxs-512-tinySDdistilled_Q8_0.gguf?download=true",
            683, 1, 1f, 1200, "Le plus rapide (1 étape) : à privilégier sur ce téléphone."),
        Model("sd-turbo", "SD-Turbo · meilleure qualité",
            "https://huggingface.co/gpustack/stable-diffusion-v2-1-turbo-GGUF/resolve/main/stable-diffusion-v2-1-turbo-Q8_0.gguf?download=true",
            2400, 4, 1f, 3000, "4 étapes : nettement plus fidèle, mais long sur ce téléphone."),
        Model("realistic-vision-hyper", "Realistic Vision · photo",
            "https://huggingface.co/second-state/Realistic_Vision_V6.0_B1-GGUF/resolve/main/realisticVisionV60B1_v51HyperVAE-Q8_0.gguf?download=true",
            1770, 5, 1.5f, 2500, "Photos et visages réalistes, 1 à 2 minutes par image.",
            "nsfw, nude, deformed, disfigured, bad anatomy, extra limbs, extra fingers, blurry, out of focus, bokeh, lowres, cartoon, painting, watermark, text"),
        Model("realvisxl-v5-lightning", "RealVisXL · SDXL 768 px",
            "https://huggingface.co/off-grid-ai/realvisxl-v5.0-lightning-GGUF/resolve/main/realvisxl-v5.0-lightning-Q8_0.gguf?download=true",
            4180, 6, 1.5f, 1500, "Photo SDXL dessinée en 768 px, détails fins. Lu par morceaux depuis le fichier : plusieurs minutes par image.",
            "nsfw, nude, naked, deformed, disfigured, bad anatomy, extra limbs, extra fingers, blurry, out of focus, bokeh, lowres, watermark, text",
            sampler = "euler", stream = true, scale = 1.5f),
    )

    private val main = Handler(Looper.getMainLooper())
    @Volatile var downloading: String? = null; private set

    /** 4 fils comme Lia : les cœurs rapides ; au-delà, les petits cœurs lents freinent tout le calcul. */
    const val THREADS = 4

    /** Garde le processeur éveillé pendant un dessin (écran éteint ou Nox en arrière-plan), comme Lia. */
    private val wake by lazy {
        Nox.app.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Nox:image").apply { setReferenceCounted(false) }
    }
    fun awake(on: Boolean) = try { if (on) wake.acquire(60 * 60_000L) else if (wake.isHeld) wake.release() } catch (_: Throwable) {}

    /** Image prête : notification (utile si Nox était en arrière-plan), toucher = l'ouvrir. */
    fun notifyReady(uri: Uri, label: String) {
        try {
            val nm = Nox.app.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("nox_images", "Images créées", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val open = android.app.PendingIntent.getActivity(Nox.app, 7, android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "image/jpeg").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(7301, android.app.Notification.Builder(Nox.app, "nox_images")
                .setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("Ton image est prête")
                .setContentText("$label · touche pour l'ouvrir (Images/Nox)").setContentIntent(open).setAutoCancel(true).build())
        } catch (t: Throwable) { NoxLog.w("IMAGE", "Notification d'image impossible", t) }
    }

    fun file(m: Model) = File(Nox.store.dir(), "${m.id}.gguf")
    fun ready(m: Model) = file(m).let { it.exists() && it.length() > m.mb * 900_000L }
    fun chosen(): Model = MODELS.firstOrNull { it.id == Nox.prefs.localImageModel && ready(it) }
        ?: MODELS.firstOrNull { ready(it) } ?: MODELS.first { it.id == Nox.prefs.localImageModel.ifBlank { "sdxs-512" } }

    /** Tailles des modèles SD 1.x (multiples de 64, base 512), comme Lia. */
    fun size(request: String, scale: Float = 1f): Pair<Int, Int> {
        val f = Text.fold(request)
        val (w, h) = when {
            Regex("\\b(?:portrait|vertical|verticale|fond d'ecran|story)\\b").containsMatchIn(f) -> 512 to 768
            Regex("\\b(?:paysage|panoramique|horizontal|horizontale|banniere)\\b").containsMatchIn(f) -> 768 to 512
            else -> 512 to 512
        }
        fun r(v: Int) = ((v * scale / 64f).toInt() * 64)
        return r(w) to r(h)
    }

    // ---------------------------------------------------------------- traduction (les petits modèles ne lisent que l'anglais)

    private val FRENCH = Regex("[àâäçéèêëîïôöùûüÿœæ]|\\b(?:le|la|les|un|une|des|du|de|au|aux|et|avec|sur|sous|dans|pour|qui|est|petit|petite|grand|grande|chat|chien|maison|rouge|bleu|vert|noir|blanc|fille|garcon|femme|homme)\\b", RegexOption.IGNORE_CASE)

    fun looksFrench(s: String) = FRENCH.containsMatchIn(s)

    /** Consigne de traduction (Lia : courte, chaque jeton compte sur le téléphone). */
    fun translationInstruction(p: String) = "Translate to English, keep every detail, reply with the translation only:\n$p"

    fun cleanTranslation(raw: String, original: String): String? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val c = line.replace(Regex("^(english( translation)?|translation|description)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .trim().removeSurrounding("\"").removeSurrounding("«", "»").trim()
        return c.takeIf { it.isNotBlank() && it.length <= original.length * 4 + 80 }
    }

    // ---------------------------------------------------------------- création

    /** Dessine [prompt] (anglais) ; [progress] reçoit « étape 2/4 ». Bloquant. Renvoie le JPEG. */
    fun generate(m: Model, prompt: String, width: Int, height: Int, progress: (String) -> Unit): ByteArray {
        if (!ready(m)) throw IllegalStateException("le modèle ${m.label} n'est pas téléchargé")
        val free = freeMb()
        if (free < m.minFreeMb * 0.85) throw IllegalStateException("pas assez de mémoire libre ($free Mo, il en faut environ ${m.minFreeMb}) : ferme d'autres applis")
        val parts = mutableListOf(prompt)
        if (Regex("\\b(?:child|kid|girl|boy|baby|teen)\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)) parts += "fully clothed in everyday clothes"
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread {
            while (!done.get()) {
                val (step, steps) = LocalImageGenerator.progress()
                if (steps in 1..50) progress(if (step > 0) "étape $step/$steps" else "chargement du modèle") else if (steps > 50) progress("finition")
                Thread.sleep(500)
            }
        }.start()
        val t0 = System.currentTimeMillis()
        val img = try {
            LocalImageGenerator.generate(file(m).absolutePath, parts.joinToString(", "), m.negative, width, height, m.steps, m.cfg,
                threads = THREADS, sampleMethod = m.sampler,
                weights = if (m.stream) com.localia.imagegen.WeightMode.STREAMING else com.localia.imagegen.WeightMode.STANDARD)
        } finally { done.set(true) }
        NoxLog.p("IMAGE", "Image locale ${m.id} ${img.width}×${img.height} en ${System.currentTimeMillis() - t0} ms (moteur ${LocalImageGenerator.engineVariant}, $free Mo libres avant)")
        return jpeg(img)
    }

    fun cancel() = try { LocalImageGenerator.cancel() } catch (_: Throwable) {}

    private fun freeMb(): Int {
        val mi = android.app.ActivityManager.MemoryInfo()
        Nox.app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
        return (mi.availMem / 1_048_576).toInt()
    }

    private fun jpeg(img: LocalImage): ByteArray {
        val px = IntArray(img.width * img.height)
        val rgb = img.rgb
        for (i in px.indices) px[i] = (0xff shl 24) or ((rgb[i * 3].toInt() and 0xff) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xff) shl 8) or (rgb[i * 3 + 2].toInt() and 0xff)
        val bmp = Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it); bmp.recycle() }.toByteArray()
    }

    /** Enregistre dans Images/Nox et renvoie l'adresse (affichée dans le chat). */
    fun saveToGallery(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "nox_local_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Nox")
        }
        val r = Nox.app.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("galerie inaccessible")
        (r.openOutputStream(uri) ?: throw IllegalStateException("écriture impossible dans la galerie")).use { it.write(bytes) }
        NoxLog.i("IMAGE", "Image enregistrée dans Images/Nox (${bytes.size / 1024} Ko) : $uri")
        return uri
    }

    // ---------------------------------------------------------------- téléchargement

    fun download(m: Model, onProgress: (Int) -> Unit, onDone: (String?) -> Unit) {
        if (downloading != null) { onDone("un téléchargement est déjà en cours"); return }
        downloading = m.id
        Thread {
            val target = file(m)
            val tmp = File(target.path + ".part")
            val err = try {
                val c = URL(m.url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 20_000; c.readTimeout = 60_000
                try {
                    if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                    val total = c.contentLengthLong
                    var done = 0L; var last = -1
                    c.inputStream.use { input -> tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != last) { last = pct; main.post { onProgress(pct) } }
                        }
                    } }
                } finally { c.disconnect() }
                if (!tmp.renameTo(target)) throw IllegalStateException("enregistrement impossible")
                NoxLog.i("IMAGE", "Modèle d'image local ${m.id} téléchargé (${target.length() / 1_048_576} Mo)")
                null
            } catch (t: Throwable) {
                tmp.delete(); NoxLog.w("IMAGE", "Téléchargement ${m.id} raté", t); t.message ?: "erreur"
            }
            downloading = null
            main.post { onDone(err) }
        }.start()
    }

    /** Menu › Images locales : choisir, télécharger, supprimer un modèle. */
    fun showDialog(a: MainActivity) {
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(4), 0, a.dp(4), a.dp(8)) }
        body.addView(a.text("Dans le chat local, dis « crée une image de… » : Nox dessine sur le téléphone, sans internet. " +
            "Gemma est mis en pause pendant le dessin puis rechargé. Mêmes modèles que Lia.", 13f, Ui.MUTED).apply { setPadding(0, 0, 0, a.dp(10)) })
        body.addView(android.widget.Switch(a).apply {
            text = "Agrandir automatiquement en HD (Real-ESRGAN ×4 : 512 → 2048 px, 768 → 3072 px)"
            setTextColor(Ui.TEXT); textSize = 14.5f
            isChecked = Nox.prefs.localImageHd
            setPadding(0, 0, 0, a.dp(10))
            setOnCheckedChangeListener { _, on -> Nox.prefs.localImageHd = on }
        })
        MODELS.forEach { m -> body.addView(card(a, m)) }
        NoxDialog.sheet(a).setTitle("Images locales").setView(ScrollView(a).apply { addView(body) }).setNegativeButton("Fermer", null).show()
    }

    private fun card(a: MainActivity, m: Model): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, a.dp(16).toFloat(), if (Nox.prefs.localImageModel == m.id) Ui.ACCENT else Ui.STROKE, a.dp(1))
            setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(10) }
        }
        box.addView(a.text(m.label, 16f, Ui.TEXT, bold = true))
        box.addView(a.text("${m.mb} Mo · ${m.note}", 12.5f, Ui.MUTED).apply { setPadding(0, a.dp(2), 0, a.dp(8)) })
        val state = a.text("", 12.5f, Ui.FAINT)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lateinit var main: TextView
        fun refresh() {
            val ok = ready(m)
            val sel = Nox.prefs.localImageModel == m.id || (Nox.prefs.localImageModel.isBlank() && ok && chosen().id == m.id)
            main.text = when { downloading == m.id -> "Téléchargement…"; !ok -> "Télécharger"; sel -> "Choisi ✓"; else -> "Choisir" }
            state.text = if (ok) "Sur le téléphone" else ""
            box.background = Ui.rounded(Ui.CARD, a.dp(16).toFloat(), if (sel && ok) Ui.ACCENT else Ui.STROKE, a.dp(1))
        }
        main = a.pillButton("") {
            when {
                downloading != null -> Unit
                !ready(m) -> download(m, { pct -> main.text = if (pct >= 0) "$pct %" else "…" }) { err ->
                    if (err != null) a.toast("Téléchargement raté : $err") else Nox.prefs.localImageModel = m.id
                    refresh()
                }
                else -> { Nox.prefs.localImageModel = m.id; refresh(); a.toast("${m.label} choisi") }
            }
        }
        row.addView(main)
        row.addView(a.pillButton("Supprimer", filled = false) {
            if (file(m).delete()) { a.toast("${m.label} supprimé"); refresh() }
        }, LinearLayout.LayoutParams(-2, a.dp(44)).apply { marginStart = a.dp(8) })
        box.addView(row)
        box.addView(state.apply { setPadding(0, a.dp(6), 0, 0) })
        refresh()
        return box
    }
}
