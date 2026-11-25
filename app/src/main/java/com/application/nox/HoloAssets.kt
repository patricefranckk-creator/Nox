package com.application.nox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Pack vidéo « Hologramme HQ » : quatre vidéos carrées sur fond noir, une par état de Nox, plus un
 * manifeste JSON. Le pack d'origine est livré dans l'APK (assets/holo) ; un pack importé (ZIP choisi
 * par l'utilisateur) le remplace. Rien ne passe par Gemma.
 */
object HoloAssets {
    const val REQ_IMPORT = 42
    const val DEFAULT_TRANSITION_MS = 250L

    /** États reconnus, dans l'ordre de lecture du manifeste. */
    val STATES = listOf("startup", "idle", "listening", "thinking", "speaking")
    private val ALIASES = mapOf(
        "demarrage" to "startup", "boot" to "startup", "intro" to "startup", "start" to "startup",
        "repos" to "idle", "attente" to "idle", "ecoute" to "listening", "listen" to "listening",
        "reflexion" to "thinking", "think" to "thinking", "processing" to "thinking",
        "parole" to "speaking", "speak" to "speaking", "talking" to "speaking",
    )

    /** Une vidéo d'état : fichier importé ou vidéo livrée dans l'APK, lecture en boucle ou non, vitesse. */
    class Clip(val state: String, val name: String, val file: File?, val asset: String?, val loop: Boolean, val speed: Float) {
        val id get() = file?.absolutePath ?: "asset:$asset"
    }

    class Pack(val clips: Map<String, Clip>, val transitionMs: Long) {
        fun clip(state: String): Clip? = clips[state]
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "holo")

    private const val ASSETS = "holo"

    private fun imported(ctx: Context) = listOf("listening", "thinking", "speaking").all { File(dir(ctx), "nox_$it.mp4").length() > 0 }

    private fun bundled(ctx: Context): Set<String> = runCatching { ctx.assets.list(ASSETS)?.toSet() }.getOrNull() ?: emptySet()

    fun ready(ctx: Context): Boolean = imported(ctx) || listOf("listening", "thinking", "speaking").all { "nox_$it.mp4" in bundled(ctx) }

    fun pick(a: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "video/mp4"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        a.startActivityForResult(i, REQ_IMPORT)
    }

    fun onResult(a: MainActivity, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        data.data?.let { if (it !in uris) uris += it }
        if (uris.isEmpty()) return
        a.toast("Import de l'hologramme en cours…")
        Thread {
            val report = try { import(a, uris) } catch (t: Throwable) {
                NoxLog.e("HOLO", "Import de l'hologramme impossible", t)
                "Import impossible : ${t.message}"
            }
            a.runOnUiThread { a.holoImported(report) }
        }.start()
    }

    /** Lit le manifeste rangé (ou les réglages par défaut) : quel fichier pour quel état, boucle, transition. */
    fun load(ctx: Context): Pack {
        val d = dir(ctx)
        val fromFiles = imported(ctx)
        val assets = if (fromFiles) emptySet() else bundled(ctx)
        val text = if (fromFiles) File(d, "manifest.json").takeIf { it.exists() }?.readText()
            else runCatching { ctx.assets.open("$ASSETS/manifest.json").bufferedReader().use { it.readText() } }.getOrNull()
        val manifest = text?.let { runCatching { JSONObject(it) }.getOrNull() }
        val wanted = HashMap<String, Triple<String, Boolean?, Float?>>()
        manifest?.let { collect(it, wanted) }
        val clips = HashMap<String, Clip>()
        for (s in STATES) {
            val (path, loop, speed) = wanted[s] ?: Triple("nox_$s.mp4", null, null)
            val candidates = listOf(path.substringAfterLast('/').lowercase(), "nox_$s.mp4")
            val l = loop ?: (s != "startup")
            val v = (speed ?: 1f).coerceIn(0.25f, 2f)
            if (fromFiles) {
                val f = candidates.map { File(d, it) }.firstOrNull { it.length() > 0 } ?: continue
                clips[s] = Clip(s, f.name, f, null, l, v)
            } else {
                val n = candidates.firstOrNull { it in assets } ?: continue
                clips[s] = Clip(s, n, null, "$ASSETS/$n", l, v)
            }
        }
        val ms = manifest?.let { transition(it) } ?: DEFAULT_TRANSITION_MS
        return Pack(clips, ms.coerceIn(0L, 2000L))
    }

    // ------------------------------------------------------------ manifeste

    private fun stateOf(key: String): String? {
        val k = Text.fold(key).replace(Regex("^nox_|\\.mp4$"), "").trim()
        return if (k in STATES) k else ALIASES[k]
    }

    /** Parcourt tout le manifeste : un état peut y être décrit par un nom de fichier ou par un objet. */
    private fun collect(any: Any?, out: HashMap<String, Triple<String, Boolean?, Float?>>, hint: String? = null) {
        when (any) {
            is JSONObject -> {
                val file = listOf("file", "fichier", "src", "path", "video", "name").firstNotNullOfOrNull { k -> any.optString(k).takeIf { it.endsWith(".mp4", true) } }
                val state = hint ?: listOf("state", "etat", "id", "role").firstNotNullOfOrNull { k -> any.optString(k).takeIf { it.isNotEmpty() }?.let { stateOf(it) } }
                    ?: file?.let { stateOf(it.substringAfterLast('/')) }
                if (file != null && state != null) {
                    val loop = listOf("loop", "boucle", "looping", "repeat").firstOrNull { any.has(it) }?.let { k ->
                        when (val v = any.opt(k)) { is Boolean -> v; is Number -> v.toInt() != 1; is String -> v.lowercase() in setOf("true", "oui", "yes", "infinite", "loop", "boucle"); else -> null }
                    } ?: listOf("play_once", "once", "une_fois").firstOrNull { any.optBoolean(it, false) }?.let { false }
                    val speed = listOf("speed", "vitesse", "playback_rate", "rate").firstOrNull { any.has(it) }?.let { any.optDouble(it, 1.0).toFloat() }
                    out[state] = Triple(file, loop, speed)
                }
                for (k in any.keys()) {
                    val v = any.opt(k)
                    val s = stateOf(k)
                    if (s != null && v is String && v.endsWith(".mp4", true)) out[s] = Triple(v, out[s]?.second, out[s]?.third)
                    else if (v is JSONObject || v is JSONArray) collect(v, out, s)
                }
            }
            is JSONArray -> for (i in 0 until any.length()) collect(any.opt(i), out, null)
        }
    }

    /** Durée du fondu : « transition_ms: 250 » ou « transition: { duration_ms: 250 } ». */
    private fun transition(any: Any?, inside: Boolean = false): Long? = when (any) {
        is JSONObject -> {
            any.keys().asSequence().firstNotNullOfOrNull { k ->
                val f = Text.fold(k)
                val about = f.contains("transition") || f.contains("crossfade") || f.contains("fondu")
                val v = any.opt(k)
                when {
                    v is Number && (about || (inside && (f.contains("duration") || f.contains("duree") || f == "ms"))) ->
                        (if (f.endsWith("_s") || f.endsWith("sec") || v.toDouble() < 5) v.toDouble() * 1000 else v.toDouble()).toLong()
                    v is JSONObject && about -> transition(v, true)
                    else -> null
                }
            }
        }
        else -> null
    }

    // ------------------------------------------------------------ import

    private fun import(ctx: Context, uris: List<Uri>): String {
        val c = NoxLog.chrono()
        val tmp = File(ctx.cacheDir, "holo_import").apply { deleteRecursively(); mkdirs() }
        val got = ArrayList<String>()
        for (u in uris) {
            val name = displayName(ctx, u).lowercase()
            ctx.contentResolver.openInputStream(u)?.use { input ->
                if (name.endsWith(".zip")) ZipInputStream(input.buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val n = e.name.substringAfterLast('/')
                        if (e.isDirectory || n.startsWith(".")) continue
                        if (n.endsWith(".mp4", true) || n.equals("manifest.json", true) || n.endsWith(".json", true)) {
                            val target = File(tmp, if (n.endsWith(".json", true)) "manifest.json" else n.lowercase())
                            target.outputStream().use { z.copyTo(it, 1 shl 16) }
                            got += n
                        }
                    }
                } else if (name.endsWith(".mp4") || name.endsWith(".json")) {
                    File(tmp, if (name.endsWith(".json")) "manifest.json" else name).outputStream().use { input.copyTo(it, 1 shl 16) }
                    got += name
                }
            }
        }
        NoxLog.i("HOLO", "Import : ${got.size} fichier(s) reçu(s) : ${got.joinToString()}")
        if (got.none { it.endsWith(".mp4", true) }) { tmp.deleteRecursively(); return "Aucune vidéo .mp4 trouvée dans ce que tu as choisi." }

        val out = dir(ctx)
        out.deleteRecursively()
        if (!tmp.renameTo(out)) { out.mkdirs(); tmp.listFiles()?.forEach { it.copyTo(File(out, it.name), true) }; tmp.deleteRecursively() }

        val pack = load(ctx)
        val lines = ArrayList<String>()
        val notes = ArrayList<String>()
        for (s in STATES) {
            val clip = pack.clip(s)
            val label = LABELS[s] ?: s
            if (clip == null) {
                when (s) {
                    "startup" -> notes += "Pas de vidéo de démarrage : Nox ouvrira directement sur l'état en cours."
                    "idle" -> notes += "Pas de vidéo de repos : la boucle d'écoute sert aussi au repos."
                    else -> notes += "⚠ Il manque la vidéo « $label » (nox_$s.mp4)."
                }
                continue
            }
            val info = probe(clip.file ?: continue)
            lines += "✓ $label : ${clip.name} · ${info.text}" + (if (clip.loop) " · en boucle" else " · une fois")
            if (info.w > 0 && kotlin.math.abs(info.w - info.h) > 2) notes += "${clip.name} n'est pas carrée (${info.w}×${info.h}) : affichée entière, sans déformation."
            if (!info.hardware) notes += "${clip.name} : aucun décodeur matériel trouvé pour ${info.mime}, lecture logicielle (plus gourmande)."
            NoxLog.i("HOLO", "$label : ${clip.name} · ${info.text} · ${if (info.hardware) "décodage matériel" else "décodage logiciel"}")
        }
        if (File(out, "manifest.json").exists()) lines += "Manifeste lu · transition ${pack.transitionMs} ms"
        else notes += "Pas de manifeste JSON : réglages par défaut (transition ${pack.transitionMs} ms)."
        NoxLog.i("HOLO", "Hologramme importé en ${c.ms()} ms")
        return (lines + notes).joinToString("\n") + "\n\nTouche « Holo HQ » dans la page vocale pour le voir."
    }

    class Info(val w: Int, val h: Int, val ms: Long, val mime: String, val hardware: Boolean, val text: String)

    fun probe(f: File): Info {
        var w = 0; var h = 0; var ms = 0L; var mime = "?"; var fps = 0
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            NoxLog.w("HOLO", "Lecture des infos de ${f.name} impossible : ${t.message}")
        } finally { r.release() }
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val m = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) { mime = m; if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE); break }
            }
        } catch (_: Throwable) {} finally { ex.release() }
        val hw = mime != "?" && android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            !ci.isEncoder && ci.isHardwareAccelerated && ci.supportedTypes.any { it.equals(mime, true) }
        }
        val mb = f.length() / 1_048_576.0
        val text = "${w}×${h} · ${"%.1f".format(ms / 1000.0)} s" + (if (fps > 0) " · $fps i/s" else "") + " · ${mime.removePrefix("video/")} · ${"%.1f".format(mb)} Mo"
        return Info(w, h, ms, mime, hw, text)
    }

    /** Résumé pour le diagnostic. */
    fun describe(ctx: Context): String {
        if (!ready(ctx)) return "absent"
        val p = load(ctx)
        return (if (imported(ctx)) "pack importé · " else "pack d'origine · ") +
            STATES.mapNotNull { s -> p.clip(s)?.let { "${LABELS[s]} ${it.name}" } }.joinToString(", ") + " · transition ${p.transitionMs} ms"
    }

    val LABELS = mapOf("startup" to "démarrage", "idle" to "repos", "listening" to "écoute", "thinking" to "réflexion", "speaking" to "parole")

    private fun displayName(ctx: Context, u: Uri): String {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0)?.let { return it }
        }
        return u.lastPathSegment ?: "fichier"
    }
}
