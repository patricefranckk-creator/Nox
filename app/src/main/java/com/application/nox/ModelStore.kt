package com.application.nox

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.Executors

/** Catalogue, téléchargement et import des modèles .litertlm. */
class ModelStore(private val ctx: Context) {

    data class Spec(
        val id: String,
        val title: String,
        val file: String,
        val url: String,
        val sizeBytes: Long,
        val pitch: String,
        /** Le GPU est déconseillé (crashs ou plus lent que le CPU sur les téléphones 8 Go). */
        val cpuOnly: Boolean = false,
        /** Taille de contexte maximale compilée dans le fichier. */
        val maxCtx: Int = 8192,
        /** Taille minimale pour considérer le fichier complet. */
        val minBytes: Long = (sizeBytes * 0.9).toLong(),
    )

    val catalog = listOf(
        Spec(
            "gemma4-e2b", "Gemma 4 E2B  ·  recommandé",
            "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            2_588_147_712L,
            "Le meilleur équilibre pour ton Snapdragon 6 Gen 4 et 8 Go : rapide sur GPU, turbo MTP, très bon français.",
        ),
        Spec(
            "gemma4-e4b", "Gemma 4 E4B  ·  puissance max",
            "gemma-4-E4B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            3_650_000_000L,
            "Nettement plus intelligent, environ 2× plus lent. Ferme les autres applis : la RAM sera juste.",
        ),
    )

    /**
     * Mémoire sémantique : EmbeddingGemma 2 (même famille que le cœur), utilisé par la bibliothèque (RAG)
     * pour chercher par le sens. Seul l'encodeur texte est chargé (~200 Mo de RAM).
     */
    val embedSpec = Spec(
        "embeddinggemma2", "EmbeddingGemma 2  ·  mémoire sémantique",
        "embeddinggemma-2-740m.litertlm",
        "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm",
        485_000_000L,
        minBytes = 120_000_000L,
        pitch = "Fait chercher Nox par le sens dans tes notes, documents et anciennes conversations (« dentiste » retrouve « RDV Dr Martin »). Sans lui, la recherche se fait par mots-clés.",
    )

    fun embedFile(): File? = fileFor(embedSpec).takeIf { isInstalled(embedSpec) && activeDownloadId(embedSpec) < 0 }

    fun specForFile(name: String): Spec? = catalog.firstOrNull { it.file == name }

    private fun isEmbedding(name: String) = name.lowercase().contains("embedding")

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val dm = ctx.getSystemService(DownloadManager::class.java)

    fun dir(): File = (ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models")).apply { mkdirs() }

    fun fileFor(s: Spec) = File(dir(), s.file)

    fun isInstalled(s: Spec): Boolean {
        val f = fileFor(s)
        return f.exists() && f.length() > s.minBytes
    }

    /** Tous les .litertlm présents (catalogue + imports). */
    fun localModels(): List<File> {
        val busy = downloadingFiles()
        return dir().listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.name !in busy && !isEmbedding(f.name) }
            ?.sortedBy { it.name }.orEmpty()
    }

    /** Fichiers en cours de téléchargement (catalogue + liens perso) : jamais proposés tant qu'incomplets. */
    fun downloadingFiles(): Set<String> {
        val out = HashSet<String>()
        (catalog + embedSpec).forEach { if (activeDownloadId(it) >= 0) out += it.file }
        customDownloads().forEach { (file, id) -> if (isActive(id)) out += file }
        return out
    }

    private fun isActive(id: Long): Boolean {
        val st = statusOf(id)?.first ?: return false
        return st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING || st == DownloadManager.STATUS_PAUSED
    }

    fun totalRamGb(): Double {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1e9
    }

    fun freeSpaceBytes(): Long = dir().usableSpace

    // ----------------------------------------------------------- téléchargement

    fun download(s: Spec): Long {
        val prefs = ctx.getSharedPreferences("nox_dl", Context.MODE_PRIVATE)
        val old = prefs.getLong(s.id, -1)
        if (old >= 0 && statusOf(old)?.first in setOf(DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED)) {
            return old
        }
        fileFor(s).delete()
        val req = DownloadManager.Request(Uri.parse(s.url))
            .setTitle("Nox · ${s.title.substringBefore("  ")}")
            .setDescription("Cerveau local de Nox")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, "models", s.file)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        prefs.edit().putLong(s.id, id).apply()
        return id
    }

    fun activeDownloadId(s: Spec): Long {
        val id = ctx.getSharedPreferences("nox_dl", Context.MODE_PRIVATE).getLong(s.id, -1)
        if (id < 0) return -1
        val st = statusOf(id)?.first ?: return -1
        return if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING || st == DownloadManager.STATUS_PAUSED) id else -1
    }

    fun cancel(s: Spec) {
        val id = activeDownloadId(s)
        if (id >= 0) dm.remove(id)
    }

    /** (statut, octets reçus, total) */
    fun statusOf(id: Long): Triple<Int, Long, Long>? {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return Triple(st, got, tot)
        }
        return null
    }

    // ----------------------------------------------------------- lien Hugging Face perso

    private val customPrefs get() = ctx.getSharedPreferences("nox_dl_custom", Context.MODE_PRIVATE)

    /** fichier → id DownloadManager */
    fun customDownloads(): Map<String, Long> =
        customPrefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    /**
     * Accepte un lien Hugging Face vers un .litertlm (page « blob » ou lien direct « resolve »).
     * @return null si OK, sinon le message d'erreur.
     */
    fun downloadUrl(raw: String): String? {
        var url = raw.trim()
        if (!url.startsWith("http")) url = "https://$url"
        url = url.substringBefore('?').substringBefore('#')
        if (url.contains("huggingface.co") && url.contains("/blob/")) url = url.replace("/blob/", "/resolve/")
        val name = Uri.decode(url.substringAfterLast('/'))
        if (!name.endsWith(".litertlm")) return "Le lien doit pointer vers un fichier .litertlm"
        if (name.contains('/') || name.startsWith(".")) return "Nom de fichier invalide"
        val old = customPrefs.getLong(name, -1)
        if (old >= 0 && isActive(old)) return "Ce fichier est déjà en cours de téléchargement"
        File(dir(), name).delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Nox · $name")
            .setDescription("Modèle personnalisé")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, "models", name)
            .setAllowedOverMetered(true)
        val id = try { dm.enqueue(req) } catch (t: Throwable) { return "Lien refusé : ${t.message}" }
        customPrefs.edit().putLong(name, id).apply()
        return null
    }

    fun cancelCustom(file: String) {
        val id = customPrefs.getLong(file, -1)
        if (id >= 0) dm.remove(id)
        customPrefs.edit().remove(file).apply()
    }

    /** Oublie les téléchargements perso terminés (réussis ou échoués). Renvoie les échecs. */
    fun pruneCustom(): List<String> {
        val failed = mutableListOf<String>()
        customDownloads().forEach { (file, id) ->
            val st = statusOf(id)?.first
            if (st == null || st == DownloadManager.STATUS_SUCCESSFUL || st == DownloadManager.STATUS_FAILED) {
                if (st == DownloadManager.STATUS_FAILED) { failed += file; File(dir(), file).delete() }
                customPrefs.edit().remove(file).apply()
            }
        }
        return failed
    }

    // ----------------------------------------------------------- import

    /** Copie un .litertlm choisi dans le stockage du téléphone vers le dossier de Nox. */
    fun import(uri: Uri, progress: (Int) -> Unit, done: (File?, String?) -> Unit) {
        io.execute {
            try {
                var name = "importe.litertlm"
                var size = -1L
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni) ?: name
                        if (si >= 0) size = c.getLong(si)
                    }
                }
                if (!name.endsWith(".litertlm")) {
                    main.post { done(null, "Choisis un fichier .litertlm (format LiteRT-LM)") }
                    return@execute
                }
                if (size > 0 && size > freeSpaceBytes() - 200_000_000L) {
                    main.post { done(null, "Pas assez d'espace libre") }
                    return@execute
                }
                val out = File(dir(), name)
                val tmp = File(dir(), "$name.part")
                ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "lecture impossible" }
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        var lastPct = -1
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            o.write(buf, 0, r)
                            copied += r
                            if (size > 0) {
                                val pct = (copied * 100 / size).toInt()
                                if (pct != lastPct) { lastPct = pct; main.post { progress(pct) } }
                            }
                        }
                    }
                }
                tmp.renameTo(out)
                main.post { done(out, null) }
            } catch (t: Throwable) {
                main.post { done(null, t.message ?: "échec de l'import") }
            }
        }
    }

    fun delete(f: File) = f.delete()

    companion object {
        fun human(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> String.format("%.2f Go", bytes / 1e9)
            bytes >= 1_000_000L -> String.format("%.0f Mo", bytes / 1e6)
            else -> "${bytes / 1000} Ko"
        }
    }
}
