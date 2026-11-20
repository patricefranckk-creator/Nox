package com.application.nox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * La bibliothèque de Nox : un RAG hybride 100 % local.
 *
 *  - Sources : documents importés, notes/listes, anciennes conversations (mémoire épisodique).
 *  - Recherche par mots-clés (BM25, toujours dispo) + recherche par le sens (EmbeddingGemma 2, si installé),
 *    fusionnées par Reciprocal Rank Fusion : on garde le meilleur des deux mondes.
 *  - Tout est stocké dans une base SQLite du téléphone ; l'index tient en mémoire (rapide).
 */
class Library(private val ctx: Context, private val store: ModelStore) {

    data class Chunk(val id: Long, val source: String, val title: String, val text: String, val time: Long, var vec: FloatArray?) {
        val terms: List<String> by lazy { Text.terms(title + " " + text) }
    }

    data class Hit(val chunk: Chunk, val score: Double, val why: String)

    private val db = Db(ctx)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nox-rag").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val chunks = ArrayList<Chunk>()
    private val lock = Any()

    // BM25
    private val df = HashMap<String, Int>()
    private var avgLen = 1.0

    /** Mis à vrai par le moteur pendant une génération : la vectorisation de fond se met en pause. */
    @Volatile var hold = false

    @Volatile private var embedder: EmbeddingEngine? = null
    @Volatile var semantic = false; private set
    private val embedLock = Any()

    init {
        worker.execute {
            synchronized(lock) { chunks.addAll(db.loadAll()); rebuildStats() }
            enableSemantic()
        }
    }

    // ================================================================ moteur sémantique

    /** Active la recherche par le sens si EmbeddingGemma 2 est installé (appelable plusieurs fois). */
    fun enableSemantic() {
        worker.execute {
            if (embedder != null) return@execute
            val f = store.embedFile() ?: return@execute
            val c = NoxLog.chrono()
            try {
                val e = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = f.absolutePath,
                        backend = Backend.CPU(threadCount = 2),   // 2 cœurs : le cerveau principal garde les autres
                        cacheDir = File(ctx.cacheDir, "litert").apply { mkdirs() }.absolutePath,
                        maxInputLength = 512,
                    )
                )
                e.initialize()
                embedder = e
                semantic = true
                NoxLog.p("RAG", "EmbeddingGemma 2 prêt en ${c.ms()} ms · ${NoxLog.memSnapshot()}")
                embedPending()
            } catch (t: Throwable) {
                NoxLog.e("RAG", "EmbeddingGemma indisponible, recherche par mots-clés seulement", t)
                semantic = false
            }
        }
    }

    fun disableSemantic() {
        worker.execute {
            synchronized(embedLock) { try { embedder?.close() } catch (_: Throwable) {} ; embedder = null }
            semantic = false
        }
    }

    private fun embed(text: String, query: Boolean): FloatArray? {
        val e = embedder ?: return null
        val prefix = if (query) "task: search query | text: " else "task: search result | text: "
        return try {
            synchronized(embedLock) {
                val v = e.computeEmbedding(listOf(InputData.Text(prefix + text.trim())), EmbeddingOptions(normalize = true)).embedding
                normalize(v)
            }
        } catch (t: Throwable) {
            NoxLog.w("RAG", "Vectorisation impossible", t); null
        }
    }

    /** Vectorise en tâche de fond les extraits qui ne le sont pas encore. */
    private fun embedPending() {
        if (embedder == null) return
        val todo = synchronized(lock) { chunks.filter { it.vec == null } }
        if (todo.isEmpty()) return
        val clock = NoxLog.chrono()
        var ok = 0
        for ((i, c) in todo.withIndex()) {
            while (hold) Thread.sleep(150)           // le cerveau principal passe d'abord
            if (i > 0 && i % 100 == 0) NoxLog.i("RAG", "Vectorisation… $i/${todo.size}")
            val v = embed(c.title + "\n" + c.text, query = false) ?: continue
            c.vec = v
            db.setVec(c.id, v)
            ok++
        }
        NoxLog.p("RAG", "Vectorisation de $ok/${todo.size} extrait(s) en ${clock.ms()} ms (${if (ok > 0) clock.ms() / ok else 0} ms/extrait)")
    }

    // ================================================================ ajout / suppression

    /** Ajoute (ou remplace) un document. Renvoie le nombre d'extraits créés. */
    fun addDocument(title: String, text: String, onDone: ((Int) -> Unit)? = null) {
        worker.execute {
            val source = "doc:" + title
            removeSourceNow(source)
            val c = NoxLog.chrono()
            val n = insert(source, title, Text.chunk(text))
            NoxLog.i("RAG", "Document « $title » : ${text.length} caractères → $n extrait(s) en ${c.ms()} ms")
            embedPending()
            onDone?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb(n) } }
        }
    }

    /** Réindexe une liste de notes (appelé à chaque modification par la compétence notes). */
    fun syncNotes(list: String, items: List<String>) {
        worker.execute {
            val source = "note:$list"
            removeSourceNow(source)
            if (items.isNotEmpty()) insert(source, "Liste « $list »", listOf(items.joinToString(" ; ")))
            embedPending()
        }
    }

    /** Mémoire épisodique : chaque échange terminé devient cherchable plus tard. */
    fun addExchange(user: String, reply: String) {
        if (user.isBlank() || reply.isBlank() || reply.startsWith("⚠")) return
        worker.execute {
            val day = SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date())
            val txt = "Utilisateur : ${user.take(600)}\nNox : ${reply.take(900)}"
            insert("chat:$day", "Conversation du $day", listOf(txt))
            embedPending()
            pruneChats()
        }
    }

    fun removeSource(source: String, done: (() -> Unit)? = null) {
        worker.execute {
            removeSourceNow(source)
            done?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        }
    }

    fun clearConversations(done: (() -> Unit)? = null) {
        worker.execute {
            val srcs = synchronized(lock) { chunks.map { it.source }.filter { it.startsWith("chat:") }.toSet() }
            srcs.forEach { removeSourceNow(it) }
            done?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        }
    }

    // ================================================================ Freekipedia

    data class FkResult(val fiches: Int, val passages: Int, val nouvelles: Int, val maj: Int, val info: String)

    /**
     * Intègre un fichier produit par Freekipedia (« lia_maj_….db » ou « lia_base_complete_….db »).
     * Même logique que l'IntegrateurMaj de Lia : chaque fiche remplace sa version précédente (idempotent).
     * Les vecteurs e5 de Freekipedia ne sont pas compatibles avec EmbeddingGemma : les passages sont
     * revectorisés ici, en tâche de fond.
     */
    fun importFreekipedia(file: File, onDone: (FkResult?, String?) -> Unit) {
        worker.execute {
            val clock = NoxLog.chrono()
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                val src = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    fun has(t: String) = src.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(t)).use { it.moveToFirst() }
                    require(has("fiches") && has("chunks")) { "ce fichier n'est pas une mise à jour Freekipedia" }
                    val meta = HashMap<String, String>()
                    if (has("meta")) src.rawQuery("SELECT cle, valeur FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) ?: "" }
                    val existing = synchronized(lock) { chunks.map { it.source }.filter { it.startsWith("fk:") }.toHashSet() }
                    val fiches = ArrayList<Array<String?>>()
                    src.rawQuery("SELECT uid, titre, source, licence, url FROM fiches", null).use { c ->
                        while (c.moveToNext()) fiches += arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
                    }
                    var passages = 0; var nouvelles = 0; var maj = 0
                    val w = db.writableDatabase
                    w.beginTransaction()
                    try { for (f in fiches) {
                        val uid = f[0] ?: continue
                        val parts = ArrayList<String>()
                        src.rawQuery("SELECT texte FROM chunks WHERE fiche_uid = ? ORDER BY ordre", arrayOf(uid)).use { c ->
                            while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { parts += it }
                        }
                        val source = "fk:$uid"
                        if (source in existing) maj++ else nouvelles++
                        removeSourceNow(source, rebuild = false)
                        val origin = listOfNotNull(f[2], f[3]).filter { it.isNotBlank() }.joinToString(", ")
                        val title = (f[1] ?: "Fiche") + if (origin.isNotEmpty()) " ($origin)" else ""
                        passages += insert(source, title, parts, rebuild = false)
                    }
                        w.setTransactionSuccessful()
                    } finally { w.endTransaction() }
                    synchronized(lock) { rebuildStats() }
                    val info = listOfNotNull(meta["date"]?.let { "du $it" }, meta["producteur"],
                        if (meta["complet"] == "1") "base complète" else "mise à jour").joinToString(" · ")
                    val r = FkResult(fiches.size, passages, nouvelles, maj, info)
                    NoxLog.p("RAG", "Freekipedia intégré en ${clock.ms()} ms : ${r.fiches} fiches ($nouvelles nouvelles, $maj remplacées), $passages passages · $info")
                    main.post { onDone(r, null) }
                } finally {
                    src.close()
                    file.delete()
                }
                embedPending()
            } catch (t: Throwable) {
                NoxLog.e("RAG", "Import Freekipedia impossible", t)
                main.post { onDone(null, t.message ?: "fichier illisible") }
            }
        }
    }

    fun freekipediaStats(): Pair<Int, Int> = synchronized(lock) {
        val fk = chunks.filter { it.source.startsWith("fk:") }
        fk.map { it.source }.toSet().size to fk.size
    }

    fun removeFreekipedia(done: (() -> Unit)? = null) {
        worker.execute {
            db.deleteLike("fk:%")
            synchronized(lock) { chunks.removeAll { it.source.startsWith("fk:") }; rebuildStats() }
            NoxLog.i("RAG", "Fiches Freekipedia retirées")
            done?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        }
    }

    private fun insert(source: String, title: String, parts: List<String>, rebuild: Boolean = true): Int {
        val now = System.currentTimeMillis()
        val added = parts.filter { it.isNotBlank() }.map { t ->
            val id = db.insert(source, title, t, now)
            Chunk(id, source, title, t, now, null)
        }
        synchronized(lock) { chunks.addAll(added); if (rebuild) rebuildStats() }
        return added.size
    }

    private fun removeSourceNow(source: String, rebuild: Boolean = true) {
        db.deleteSource(source)
        synchronized(lock) { chunks.removeAll { it.source == source }; if (rebuild) rebuildStats() }
    }

    /** On garde les ~1500 derniers échanges (largement assez, et la recherche reste instantanée). */
    private fun pruneChats() {
        val chats = synchronized(lock) { chunks.filter { it.source.startsWith("chat:") }.sortedBy { it.time } }
        val extra = chats.size - 1500
        if (extra <= 0) return
        val gone = chats.take(extra)
        gone.forEach { db.deleteId(it.id) }
        val ids = gone.map { it.id }.toHashSet()
        synchronized(lock) { chunks.removeAll { it.id in ids }; rebuildStats() }
    }

    // ================================================================ recherche

    /**
     * Recherche hybride. Appelée depuis le fil du modèle (jamais l'UI).
     * @param excludeChatsAfter ignore les échanges plus récents (déjà dans le contexte de la conversation).
     * @param strict ne renvoie que des résultats nettement pertinents (injection automatique).
     */
    fun search(query: String, k: Int = 4, excludeChatsAfter: Long = Long.MAX_VALUE, strict: Boolean = false): List<Hit> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val qTerms = Text.terms(q).distinct()
        val dfQ = HashMap<String, Int>()
        var avg: Double
        val pool = synchronized(lock) {
            qTerms.forEach { t -> dfQ[t] = df[t] ?: 0 }
            avg = avgLen
            chunks.filter { !(it.source.startsWith("chat:") && it.time >= excludeChatsAfter) }
        }
        if (pool.isEmpty()) return emptyList()

        // --- BM25
        val n = pool.size.toDouble()
        val bm = HashMap<Long, Double>()
        if (qTerms.isNotEmpty()) for (c in pool) {
            val tf = HashMap<String, Int>()
            for (t in c.terms) if (t in qTerms) tf[t] = (tf[t] ?: 0) + 1
            if (tf.isEmpty()) continue
            var s = 0.0
            val len = c.terms.size.toDouble()
            for ((t, f) in tf) {
                val d = (dfQ[t] ?: 1).coerceAtLeast(1).toDouble()
                val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
                s += idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * len / avg))
            }
            // part des termes de la requête couverts : évite qu'un seul mot commun suffise
            s *= 0.5 + 0.5 * tf.size / qTerms.size
            bm[c.id] = s
        }

        // --- Sens (cosinus)
        val cos = HashMap<Long, Double>()
        val qv = if (semantic) embed(q, query = true) else null
        if (qv != null) for (c in pool) {
            val v = c.vec ?: continue
            if (v.size != qv.size) continue
            var dot = 0.0
            for (i in v.indices) dot += v[i] * qv[i]
            cos[c.id] = dot
        }

        // --- Fusion RRF
        val rrf = HashMap<Long, Double>()
        bm.entries.sortedByDescending { it.value }.take(30).forEachIndexed { r, e -> rrf[e.key] = (rrf[e.key] ?: 0.0) + 1.0 / (60 + r) }
        cos.entries.sortedByDescending { it.value }.take(30).forEachIndexed { r, e -> rrf[e.key] = (rrf[e.key] ?: 0.0) + 1.0 / (60 + r) }

        NoxLog.d("RAG", "Recherche « ${q.take(50)} » : ${pool.size} extraits, BM25 ${bm.size} touchés, sens ${if (qv != null) cos.size else "off"}")
        val byId = pool.associateBy { it.id }
        return rrf.entries.sortedByDescending { it.value }.mapNotNull { e ->
            val c = byId[e.key] ?: return@mapNotNull null
            val b = bm[e.key] ?: 0.0
            val s = cos[e.key] ?: 0.0
            val relevant = if (strict) (s >= COS_STRONG || b >= BM25_STRONG) else (s >= COS_WEAK || b > 0.0)
            if (!relevant) null else Hit(c, e.value, if (s > 0) String.format(Locale.ROOT, "sens %.2f", s) else "mots-clés")
        }.take(k)
    }

    /** Bloc de contexte compact à placer devant le message de l'utilisateur. */
    fun contextBlock(hits: List<Hit>, maxChars: Int = 1600): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder("[Extraits trouvés dans la bibliothèque de l'utilisateur. Utilise-les seulement s'ils aident à répondre.]\n")
        for (h in hits) {
            val line = "• ${h.chunk.title} : ${h.chunk.text.replace('\n', ' ')}\n"
            if (sb.length + line.length > maxChars) { if (sb.length < maxChars - 200) sb.append(line.take(maxChars - sb.length - 4)).append("…\n"); break }
            sb.append(line)
        }
        return sb.toString()
    }

    // ================================================================ infos

    fun documents(): List<Pair<String, Int>> = synchronized(lock) {
        chunks.filter { it.source.startsWith("doc:") }.groupBy { it.source }.map { (s, l) -> s.removePrefix("doc:") to l.size }.sortedBy { it.first }
    }

    fun stats(): String = synchronized(lock) {
        val docs = chunks.count { it.source.startsWith("doc:") }
        val chats = chunks.count { it.source.startsWith("chat:") }
        val notes = chunks.count { it.source.startsWith("note:") }
        val fk = chunks.count { it.source.startsWith("fk:") }
        val vec = chunks.count { it.vec != null }
        "$docs extraits de documents · $fk passages Freekipedia · $notes listes · $chats échanges mémorisés" +
            if (semantic) " · ${vec}/${chunks.size} vectorisés" else " · recherche par mots-clés"
    }

    fun release() {
        worker.execute { try { embedder?.close() } catch (_: Throwable) {}; embedder = null; db.close() }
        worker.shutdown()
    }

    private fun rebuildStats() {
        df.clear()
        var total = 0L
        for (c in chunks) { total += c.terms.size; for (t in c.terms.toSet()) df[t] = (df[t] ?: 0) + 1 }
        avgLen = if (chunks.isEmpty()) 1.0 else total.toDouble() / chunks.size
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        if (n == 0f) return v
        return FloatArray(v.size) { v[it] / n }
    }

    companion object {
        /** Seuils d'injection automatique (prudents : mieux vaut rien qu'un hors-sujet). */
        const val COS_STRONG = 0.55
        const val COS_WEAK = 0.35
        const val BM25_STRONG = 4.0
    }

    // ================================================================ stockage

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "bibliotheque.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE chunks(id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT, title TEXT, text TEXT, time INTEGER, vec BLOB)")
            d.execSQL("CREATE INDEX idx_source ON chunks(source)")
        }

        override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}

        fun loadAll(): List<Chunk> {
            val out = ArrayList<Chunk>()
            readableDatabase.rawQuery("SELECT id, source, title, text, time, vec FROM chunks", null).use { c ->
                while (c.moveToNext()) {
                    val blob = c.getBlob(5)
                    out += Chunk(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), blob?.let { toFloats(it) })
                }
            }
            return out
        }

        fun insert(source: String, title: String, text: String, time: Long): Long =
            writableDatabase.insert("chunks", null, ContentValues().apply {
                put("source", source); put("title", title); put("text", text); put("time", time)
            })

        fun setVec(id: Long, v: FloatArray) {
            writableDatabase.update("chunks", ContentValues().apply { put("vec", toBytes(v)) }, "id=?", arrayOf(id.toString()))
        }

        fun deleteSource(source: String) { writableDatabase.delete("chunks", "source=?", arrayOf(source)) }
        fun deleteLike(pattern: String) { writableDatabase.delete("chunks", "source LIKE ?", arrayOf(pattern)) }
        fun deleteId(id: Long) { writableDatabase.delete("chunks", "id=?", arrayOf(id.toString())) }

        private fun toBytes(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { bb.putFloat(it) }
            return bb.array()
        }

        private fun toFloats(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { bb.float }
        }
    }
}

/** Outils de texte pour le RAG : découpage et normalisation française. */
object Text {
    private val STOP = setOf(
        "le", "la", "les", "un", "une", "des", "du", "de", "d", "l", "et", "ou", "a", "au", "aux", "en", "dans", "par",
        "pour", "sur", "avec", "sans", "ce", "cet", "cette", "ces", "se", "sa", "son", "ses", "mon", "ma", "mes", "ton", "ta",
        "tes", "notre", "nos", "votre", "vos", "leur", "leurs", "je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles",
        "me", "te", "lui", "y", "que", "qui", "quoi", "dont", "ne", "pas", "plus", "est", "es", "suis", "sont", "etait", "ete",
        "avoir", "ai", "as", "avons", "avez", "ont", "etre", "fait", "faire", "c", "j", "m", "n", "s", "t", "qu", "mais",
        "donc", "car", "si", "tout", "tous", "toute", "toutes", "tres", "bien", "aussi", "comme", "quand", "est-ce", "moi", "toi",
    )

    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(Locale.FRANCE), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")

    /** Mots normalisés + racine légère (pluriels, quelques suffixes) pour que « courses » trouve « course ». */
    fun terms(s: String): List<String> = fold(s).split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && it !in STOP }
        .map { stem(it) }

    private fun stem(w: String): String {
        var t = w
        for (suf in listOf("ements", "ement", "ations", "ation", "euses", "euse", "eurs", "eur", "ees", "es", "ee", "s", "x")) {
            if (t.length - suf.length >= 4 && t.endsWith(suf)) { t = t.dropLast(suf.length); break }
        }
        return t
    }

    /** Découpe un texte en extraits d'environ 700 caractères, aux frontières de paragraphes puis de phrases. */
    fun chunk(text: String, size: Int = 700, overlap: Int = 120): List<String> {
        val clean = text.replace("\r", "").replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (clean.length <= size) return if (clean.isEmpty()) emptyList() else listOf(clean)
        val pieces = clean.split(Regex("(?<=\\n\\n)|(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (p in pieces) {
            if (cur.length + p.length > size && cur.isNotEmpty()) {
                out += cur.toString().trim()
                val tail = cur.takeLast(overlap).toString()
                cur.setLength(0)
                cur.append(tail.substringAfter(' ', tail)).append(' ')
            }
            if (p.length > size) {               // phrase géante : découpe brute
                p.chunked(size).forEach { part -> if (cur.length + part.length > size && cur.isNotEmpty()) { out += cur.toString().trim(); cur.setLength(0) }; cur.append(part) }
            } else cur.append(p).append(' ')
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }
}
