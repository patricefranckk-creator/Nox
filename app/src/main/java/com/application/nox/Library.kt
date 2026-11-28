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

    data class Hit(val chunk: Chunk, val score: Double, val why: String, val cos: Double = 0.0, val bm25: Double = 0.0)

    private val db = Db(ctx)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nox-rag").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val chunks = ArrayList<Chunk>()
    /**
     * Résultats récents, mutualisés entre l'aiguillage et la génération : même requête, mêmes filtres,
     * même état de la bibliothèque ([gen] change à chaque ajout, retrait ou vectorisation) → même réponse.
     */
    @Volatile private var gen = 0
    private val recent = object : LinkedHashMap<String, List<Hit>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Hit>>?) = size > 8
    }
    @Volatile var reused = 0; private set
    @Volatile var searched = 0; private set
    private val lock = Any()

    // BM25
    private val df = HashMap<String, Int>()
    private var avgLen = 1.0

    /** Mis à vrai par le moteur pendant une génération : la vectorisation de fond se met en pause. */

    @Volatile private var embedder: EmbeddingEngine? = null
    @Volatile var semantic = false; private set
    /** EmbeddingGemma comprend aussi l'audio : la question parlée cherche directement dans la bibliothèque. */
    @Volatile var audioSemantic = false; private set
    private val embedLock = Any()

    init {
        worker.execute {
            synchronized(lock) { chunks.addAll(db.loadAll()); rebuildStats(); gen++ }
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
            fun build(audio: Boolean) = EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = f.absolutePath,
                    backend = Backend.CPU(threadCount = 2),   // 2 cœurs : le cerveau principal garde les autres
                    audioBackend = if (audio) Backend.CPU(threadCount = 2) else null,
                    cacheDir = File(ctx.cacheDir, "litert").apply { mkdirs() }.absolutePath,
                    maxInputLength = 512,
                )
            ).also { it.initialize() }
            try {
                val e = try { build(audio = true).also { audioSemantic = true } } catch (t: Throwable) {
                    NoxLog.w("RAG", "EmbeddingGemma sans encodeur audio : recherche vocale par le texte seulement", t)
                    audioSemantic = false
                    build(audio = false)
                }
                embedder = e
                semantic = true
                NoxLog.p("RAG", "EmbeddingGemma 2 prêt en ${c.ms()} ms (audio ${if (audioSemantic) "oui" else "non"}) · ${NoxLog.memSnapshot()}")
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
            audioSemantic = false
        }
    }

    /** Vecteurs des dernières questions : la même question cherchée deux fois (aiguillage puis réponse) n'est calculée qu'une fois. */
    private val queryCache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 12
    }
    private val prefetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "nox-rag-avance").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile private var prefetchWanted = ""

    private fun queryVector(q: String): FloatArray? {
        val key = q.trim().lowercase()
        synchronized(queryCache) { queryCache[key] }?.let { NoxLog.d("RAG", "Vecteur de la question déjà prêt (calculé pendant que tu parlais)"); return it }
        val v = embed(q, query = true) ?: return null
        synchronized(queryCache) { queryCache[key] = v }
        return v
    }

    /** Pendant que tu parles : on vectorise déjà le texte reconnu, la recherche finale sera plus rapide. */
    fun prefetch(partial: String) {
        val q = partial.trim()
        if (!semantic || q.length < 8 || q == prefetchWanted) return
        prefetchWanted = q
        prefetcher.execute {
            if (prefetchWanted != q) return@execute
            val key = q.lowercase()
            if (synchronized(queryCache) { queryCache.containsKey(key) }) return@execute
            embed(q, query = true)?.let { v -> synchronized(queryCache) { queryCache[key] = v } }
        }
    }

    private fun embed(text: String, query: Boolean): FloatArray? {
        val e = embedder ?: return null
        val prefix = if (query) QUERY_PREFIX else DOC_PREFIX
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
            ResourceManager.awaitMaintenance()       // le pilotage des ressources dit quand avancer
            if (i > 0 && i % 100 == 0) NoxLog.i("RAG", "Vectorisation… $i/${todo.size}")
            val v = embed(c.title + "\n" + c.text, query = false) ?: continue
            c.vec = v
            gen++
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

    data class FkResult(val fiches: Int, val passages: Int, val nouvelles: Int, val maj: Int, val info: String, val vecteurs: Int = 0)

    /**
     * Intègre un fichier produit par Freekipedia : format Nox (« nox_….db », Freekipedia 4), format 3
     * (« lia_memoire_….db ») ou ancien format (« lia_maj_….db »). Chaque fiche remplace sa version
     * précédente (idempotent).
     * Si le fichier contient la table « nox_passages » calculée avec le même modèle et le même préfixe que
     * Nox (EmbeddingGemma 2, voir [DOC_PREFIX]), les vecteurs sont repris tels quels ; sinon les passages
     * sont vectorisés ici, en tâche de fond.
     */
    fun importFreekipedia(file: File, onDone: (FkResult?, String?) -> Unit) {
        worker.execute {
            val clock = NoxLog.chrono()
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                val src = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    fun has(t: String) = src.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(t)).use { it.moveToFirst() }
                    val v3 = has("passages")
                    require(has("fiches") && (v3 || has("chunks"))) { "ce fichier n'est pas un fichier Freekipedia" }
                    val meta = HashMap<String, String>()
                    if (has("meta")) src.rawQuery("SELECT cle, valeur FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) ?: "" }
                    // Freekipedia 4 : fichier fait pour Nox (« nox_biblio »), vecteurs dans passages.embedding
                    val biblio = meta["type"] == NOX_BIBLIO
                    val noxVectors = if (biblio)
                        meta["format"] == NOX_FORMAT && meta["modele"] == store.embedSpec.file && meta["prefixe"] == DOC_PREFIX
                    else v3 && has("nox_passages") && meta["nox_format"] == NOX_FORMAT &&
                        meta["nox_modele"] == store.embedSpec.file && meta["nox_prefixe"] == DOC_PREFIX
                    if ((biblio || (v3 && has("nox_passages"))) && !noxVectors)
                        NoxLog.w("RAG", "Vecteurs du fichier ignorés (modèle ${meta["modele"] ?: meta["nox_modele"]}) : recalcul")
                    val existing = synchronized(lock) { chunks.map { it.source }.filter { it.startsWith("fk:") }.toHashSet() }
                    val fiches = ArrayList<Array<String?>>()
                    val fichesSql = when {
                        biblio -> "SELECT uid, titre, source, licence, url, titre_nox FROM fiches"
                        v3 -> "SELECT uid, titre, source, provider, url, NULL FROM fiches"
                        else -> "SELECT uid, titre, source, licence, url, NULL FROM fiches"
                    }
                    src.rawQuery(fichesSql, null).use { c ->
                        while (c.moveToNext()) fiches += arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
                    }
                    val passagesSql = when {
                        biblio && noxVectors -> "SELECT texte, NULL, embedding FROM passages WHERE fiche_uid = ? ORDER BY ordre"
                        biblio -> "SELECT texte, NULL, NULL FROM passages WHERE fiche_uid = ? ORDER BY ordre"
                        noxVectors -> "SELECT p.texte, n.titre, n.embedding FROM passages p LEFT JOIN nox_passages n ON n.passage_id = p.id WHERE p.fiche_uid = ? ORDER BY p.ordre"
                        v3 -> "SELECT texte, NULL, NULL FROM passages WHERE fiche_uid = ? ORDER BY ordre"
                        else -> "SELECT texte, NULL, NULL FROM chunks WHERE fiche_uid = ? ORDER BY ordre"
                    }
                    var passages = 0; var nouvelles = 0; var maj = 0; var vecteurs = 0
                    val w = db.writableDatabase
                    w.beginTransaction()
                    try { for (f in fiches) {
                        val uid = f[0] ?: continue
                        val texts = ArrayList<String>()
                        val vecs = ArrayList<FloatArray?>()
                        var noxTitle: String? = null
                        src.rawQuery(passagesSql, arrayOf(uid)).use { c ->
                            while (c.moveToNext()) {
                                val t = c.getString(0)
                                if (t.isNullOrBlank()) continue
                                if (noxTitle == null) noxTitle = c.getString(1)
                                texts += t
                                vecs += if (c.isNull(2)) null else toFloats(c.getBlob(2))
                            }
                        }
                        val source = "fk:$uid"
                        if (source in existing) maj++ else nouvelles++
                        removeSourceNow(source)
                        val title = f[5] ?: noxTitle ?: if (v3) {
                            (f[1] ?: "Fiche") + (f[3]?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                        } else {
                            val origin = listOfNotNull(f[2], f[3]).filter { it.isNotBlank() }.joinToString(", ")
                            (f[1] ?: "Fiche") + if (origin.isNotEmpty()) " ($origin)" else ""
                        }
                        vecteurs += vecs.count { it != null }
                        passages += insert(source, title, texts, vecs = vecs)
                    }
                        w.setTransactionSuccessful()
                    } finally { w.endTransaction() }
                    val info = listOfNotNull(meta["date"]?.let { "du $it" }, meta["producteur"],
                        if (v3 || biblio) meta["requete"]?.let { "« $it »" } else if (meta["complet"] == "1") "base complète" else "mise à jour").joinToString(" · ")
                    val r = FkResult(fiches.size, passages, nouvelles, maj, info, vecteurs)
                    NoxLog.p("RAG", "Freekipedia intégré en ${clock.ms()} ms : ${r.fiches} fiches ($nouvelles nouvelles, $maj remplacées), $passages passages, $vecteurs vecteurs repris · $info")
                    main.post { onDone(r, null) }
                } finally {
                    src.close()
                    for (suffix in listOf("", "-journal", "-wal", "-shm")) java.io.File(file.path + suffix).delete()
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
            synchronized(lock) { val out = chunks.filter { it.source.startsWith("fk:") }; chunks.removeAll(out.toSet()); unstat(out); gen++ }
            NoxLog.i("RAG", "Fiches Freekipedia retirées")
            done?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        }
    }

    private fun insert(source: String, title: String, parts: List<String>, vecs: List<FloatArray?>? = null): Int {
        val now = System.currentTimeMillis()
        val added = parts.indices.filter { parts[it].isNotBlank() }.map { i ->
            val t = parts[i]
            val v = vecs?.getOrNull(i)
            val id = db.insert(source, title, t, now, v)
            Chunk(id, source, title, t, now, v)
        }
        synchronized(lock) { chunks.addAll(added); stat(added); gen++ }
        return added.size
    }

    private fun removeSourceNow(source: String) {
        db.deleteSource(source)
        synchronized(lock) { val out = chunks.filter { it.source == source }; chunks.removeAll(out.toSet()); unstat(out); gen++ }
    }

    /** On garde les ~1500 derniers échanges (largement assez, et la recherche reste instantanée). */
    private fun pruneChats() {
        val chats = synchronized(lock) { chunks.filter { it.source.startsWith("chat:") }.sortedBy { it.time } }
        val extra = chats.size - 1500
        if (extra <= 0) return
        val gone = chats.take(extra)
        gone.forEach { db.deleteId(it.id) }
        val ids = gone.map { it.id }.toHashSet()
        synchronized(lock) { val out = chunks.filter { it.id in ids }; chunks.removeAll(out.toSet()); unstat(out); gen++ }
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
        val key = "$gen|$k|$excludeChatsAfter|$strict|$q"
        synchronized(recent) { recent[key] }?.let { reused++; NoxLog.d("RAG", "Recherche réutilisée (« ${q.take(40)} »)"); return it }
        val lease = ResourceManager.begin(ResourcePolicy.Activity.RETRIEVING)
        try {
            val hits = searchNow(q, k, excludeChatsAfter, strict)
            searched++
            synchronized(recent) { recent[key] = hits }
            return hits
        } finally { lease.release() }
    }

    private fun searchNow(q: String, k: Int, excludeChatsAfter: Long, strict: Boolean): List<Hit> {
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
        val qv = if (semantic) queryVector(q) else null
        if (qv != null) for (c in pool) {
            val v = c.vec ?: continue
            if (v.size != qv.size) continue
            var dot = 0.0
            for (i in v.indices) dot += v[i] * qv[i]
            cos[c.id] = dot
        }

        // --- Fusion RRF
        val rrf = HashMap<Long, Double>()
        top(bm, 30).forEachIndexed { r, e -> rrf[e.key] = (rrf[e.key] ?: 0.0) + 1.0 / (60 + r) }
        top(cos, 30).forEachIndexed { r, e -> rrf[e.key] = (rrf[e.key] ?: 0.0) + 1.0 / (60 + r) }

        NoxLog.d("RAG", "Recherche « ${q.take(50)} » : ${pool.size} extraits, BM25 ${bm.size} touchés, sens ${if (qv != null) cos.size else "off"}")
        val byId = pool.associateBy { it.id }
        return rrf.entries.sortedByDescending { it.value }.mapNotNull { e ->
            val c = byId[e.key] ?: return@mapNotNull null
            val b = bm[e.key] ?: 0.0
            val s = cos[e.key] ?: 0.0
            val relevant = when {
                // sans EmbeddingGemma, seuls les mots décident : on exige qu'ils soient tous dans l'extrait
                qv == null -> if (strict) keywordsOnlyOk(c, qTerms, b) else b > 0.0
                strict -> s >= COS_STRONG || (b >= BM25_STRONG && s >= COS_MID)
                else -> s >= COS_WEAK || (b > 0.0 && s >= COS_WEAK - 0.04)
            }
            if (!relevant) null else Hit(c, e.value, if (s > 0) String.format(Locale.ROOT, "sens %.2f", s) else "mots-clés", s, b)
        }.take(k)
    }

    /**
     * Recherche à partir de la voix elle-même (EmbeddingGemma audio) : les 10 premières secondes
     * de la question sont comparées par le sens aux extraits de la bibliothèque.
     */
    fun searchAudio(wav: ByteArray, k: Int = 3, excludeChatsAfter: Long = Long.MAX_VALUE): List<Hit> {
        val e = embedder ?: return emptyList()
        if (!audioSemantic) return emptyList()
        val clock = NoxLog.chrono()
        val qv = try {
            synchronized(embedLock) { normalize(e.computeEmbedding(listOf(InputData.Audio(firstSeconds(wav, 10))), EmbeddingOptions(normalize = true)).embedding) }
        } catch (t: Throwable) {
            NoxLog.w("RAG", "Vectorisation de la voix impossible : recherche vocale coupée", t)
            audioSemantic = false
            return emptyList()
        }
        // les anciennes conversations ressemblent toutes à une voix qui parle : jamais par l'audio
        val pool = synchronized(lock) { chunks.filter { it.vec != null && !it.source.startsWith("chat:") } }
        val scored = pool.mapNotNull { c ->
            val v = c.vec!!
            if (v.size != qv.size) return@mapNotNull null
            var dot = 0.0
            for (i in v.indices) dot += v[i] * qv[i]
            c to dot
        }.sortedByDescending { it.second }
        NoxLog.p("RAG", "Recherche vocale en ${clock.ms()} ms · meilleurs scores " +
            scored.take(3).joinToString(" | ") { String.format(Locale.ROOT, "%.2f %s", it.second, it.first.title.take(24)) })
        return scored.filter { it.second >= COS_AUDIO }.take(k).map { (c, sc) -> Hit(c, sc, String.format(Locale.ROOT, "voix %.2f", sc)) }
    }

    /** Coupe un WAV PCM 16 bits mono 16 kHz (en-tête 44 octets) à ses premières secondes. */
    private fun firstSeconds(wav: ByteArray, seconds: Int): ByteArray {
        val max = 44 + seconds * 32_000
        if (wav.size <= max || wav.size < 44) return wav
        val out = wav.copyOf(max)
        val data = max - 44
        fun le(pos: Int, v: Int) { out[pos] = v.toByte(); out[pos + 1] = (v shr 8).toByte(); out[pos + 2] = (v shr 16).toByte(); out[pos + 3] = (v shr 24).toByte() }
        le(4, 36 + data); le(40, data)
        return out
    }

    /** Bloc de contexte compact à placer devant le message de l'utilisateur. */
    fun contextBlock(hits: List<Hit>, maxChars: Int = 1600): String {
        if (hits.isEmpty()) return ""
        // cadre net : ces notes viennent de Nox, pas de l'utilisateur, et ne doivent pas être commentées
        val sb = StringBuilder("[Notes de référence trouvées automatiquement par Nox, pas envoyées par l'utilisateur. " +
            "Sers-t'en seulement si elles répondent à son message ; sinon ignore-les complètement et n'en parle pas.]\n")
        for (h in hits) {
            val line = "• ${h.chunk.title} : ${h.chunk.text.replace('\n', ' ')}\n"
            if (sb.length + line.length > maxChars) { if (sb.length < maxChars - 200) sb.append(line.take(maxChars - sb.length - 4)).append("…\n"); break }
            sb.append(line)
        }
        return sb.toString()
    }

    // ================================================================ exploration du contenu

    enum class Kind(val emoji: String, val label: String) {
        FREEKIPEDIA("🌐", "Freekipedia"), DOC("📄", "Documents"), NOTE("📝", "Listes"), CHAT("💬", "Conversations"), OTHER("•", "Autres");

        companion object {
            fun of(source: String): Kind = when {
                source.startsWith("fk:") -> FREEKIPEDIA
                source.startsWith("doc:") -> DOC
                source.startsWith("note:") -> NOTE
                source.startsWith("chat:") -> CHAT
                else -> OTHER
            }
        }
    }

    /** Une source de la bibliothèque (une fiche, un document, une liste, une journée de conversation). */
    data class SourceInfo(val source: String, val title: String, val kind: Kind, val extracts: Int,
                          val vectorized: Int, val chars: Int, val time: Long)

    /**
     * Toutes les sources, les plus récentes d'abord.
     * @param query filtre facultatif sur le titre ou le texte (sans tenir compte des accents ni des majuscules).
     */
    fun sources(query: String = "", kind: Kind? = null): List<SourceInfo> {
        val snapshot = synchronized(lock) { ArrayList(chunks) }
        val q = Text.fold(query.trim())
        return snapshot.groupBy { it.source }.mapNotNull { (source, list) ->
            val k = Kind.of(source)
            if (kind != null && k != kind) return@mapNotNull null
            if (q.isNotEmpty() && list.none { Text.fold(it.title).contains(q) || Text.fold(it.text).contains(q) }) return@mapNotNull null
            SourceInfo(source, list.first().title, k, list.size, list.count { it.vec != null },
                list.sumOf { it.text.length }, list.maxOf { it.time })
        }.sortedByDescending { it.time }
    }

    /** Nombre de sources par type (pour les filtres). */
    fun kindCounts(): Map<Kind, Int> = synchronized(lock) {
        chunks.map { it.source }.toSet().groupingBy { Kind.of(it) }.eachCount()
    }

    /** Les extraits d'une source, dans l'ordre où ils ont été ajoutés. */
    fun extracts(source: String): List<Chunk> = synchronized(lock) {
        chunks.filter { it.source == source }.sortedBy { it.id }
    }

    /** Dimension des vecteurs actuellement stockés (0 si aucun). */
    fun vectorSize(): Int = synchronized(lock) { chunks.firstOrNull { it.vec != null }?.vec?.size ?: 0 }

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

    /** Total des mots de tous les extraits (pour la longueur moyenne BM25), tenu à jour pas à pas. */
    private var totalTerms = 0L

    private fun rebuildStats() {
        df.clear()
        totalTerms = 0L
        stat(chunks)
    }

    /** Statistiques BM25 incrémentales : un ajout ou un retrait ne recalcule que les extraits concernés. */
    private fun stat(list: Collection<Chunk>) {
        for (c in list) { totalTerms += c.terms.size; for (t in c.terms.toSet()) df[t] = (df[t] ?: 0) + 1 }
        avgLen = if (chunks.isEmpty()) 1.0 else totalTerms.toDouble() / chunks.size
    }

    private fun unstat(list: Collection<Chunk>) {
        for (c in list) {
            totalTerms -= c.terms.size
            for (t in c.terms.toSet()) { val n = (df[t] ?: 0) - 1; if (n <= 0) df.remove(t) else df[t] = n }
        }
        avgLen = if (chunks.isEmpty()) 1.0 else totalTerms.coerceAtLeast(0).toDouble() / chunks.size
    }

    /**
     * Injection automatique sans recherche par le sens. Un seul mot (« merci », « politesse ») ne suffit
     * que s'il est le sujet même de la fiche (dans son titre) ; les anciennes conversations ne sont jamais
     * injectées ainsi (un « merci » s'y trouve toujours).
     */
    private fun keywordsOnlyOk(c: Chunk, qTerms: List<String>, bm25: Double): Boolean {
        if (c.source.startsWith("chat:")) return false
        if (bm25 < BM25_KEYWORDS_ONLY || !covers(c, qTerms)) return false
        if (qTerms.size >= 2) return true
        val title = Text.terms(c.title).toHashSet()
        return qTerms.all { it in title }
    }

    private fun covers(c: Chunk, qTerms: List<String>): Boolean {
        if (qTerms.isEmpty()) return false
        val have = c.terms.toHashSet()
        return qTerms.all { it in have }
    }

    /** Les [n] meilleurs scores sans trier toute la bibliothèque (tas de taille n). */
    private fun <K> top(m: Map<K, Double>, n: Int): List<Map.Entry<K, Double>> {
        val heap = java.util.PriorityQueue<Map.Entry<K, Double>>(n + 1, compareBy { it.value })
        for (e in m.entries) { heap.add(e); if (heap.size > n) heap.poll() }
        return heap.sortedByDescending { it.value }
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
        // EmbeddingGemma 2 : un texte sans rapport obtient déjà ≈ 0,67–0,72 (mesuré par le diagnostic)
        const val COS_STRONG = 0.76
        const val COS_MID = 0.73
        const val COS_WEAK = 0.70
        /** Voix ↔ texte : les scores croisés sont plus bas que texte ↔ texte. */
        const val COS_AUDIO = 0.80
        const val BM25_STRONG = 4.0
        /** Injection automatique sans recherche par le sens : score fort et tous les mots de la question présents. */
        const val BM25_KEYWORDS_ONLY = 5.0

        /**
         * Préfixes EmbeddingGemma de la bibliothèque. Freekipedia (ModeleNox) utilise exactement le même
         * préfixe document et le même texte « titre + saut de ligne + passage » : ses vecteurs sont repris tels quels.
         */
        const val DOC_PREFIX = "task: search result | text: "
        const val QUERY_PREFIX = "task: search query | text: "
        const val NOX_FORMAT = "1"
        const val NOX_BIBLIO = "nox_biblio"

        fun toFloats(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { bb.float }
        }
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
                    out += Chunk(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), blob?.let { Library.toFloats(it) })
                }
            }
            return out
        }

        fun insert(source: String, title: String, text: String, time: Long, vec: FloatArray? = null): Long =
            writableDatabase.insert("chunks", null, ContentValues().apply {
                put("source", source); put("title", title); put("text", text); put("time", time)
                if (vec != null) put("vec", toBytes(vec))
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
