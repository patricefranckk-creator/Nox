package com.application.nox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Cerveau local de Nox : LiteRT-LM (Google) avec accélération GPU Adreno,
 * décodage spéculatif MTP et repli automatique sur CPU.
 */
class LlmEngine(private val ctx: Context, private val prefs: Prefs) {

    interface StreamListener {
        fun onChunk(text: String)
        fun onDone(stats: String)
        fun onError(msg: String)
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nox-llm").apply { priority = Thread.MAX_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile var busy = false; private set
    @Volatile var ready = false; private set

    var backendLabel = ""; private set
    var modelLabel = ""; private set

    /** Nombre de caractères injectés dans la conversation courante (estimation de l'occupation du contexte). */
    private var charsInContext = 0

    // ------------------------------------------------------------------ chargement

    fun load(history: List<ChatMsg>, onStatus: (String) -> Unit, onResult: (ok: Boolean, msg: String) -> Unit) {
        val path = prefs.modelPath
        worker.execute {
            closeInternal()
            if (path.isBlank() || !File(path).exists()) {
                post { onResult(false, "Aucun modèle installé") }
                return@execute
            }
            modelLabel = File(path).nameWithoutExtension
            val wantGpu = prefs.backend == "gpu"
            val mtpCapable = prefs.turbo && supportsMtp(path)

            // Cascade : GPU+MTP → GPU → CPU (le premier qui démarre gagne)
            val attempts = mutableListOf<Pair<String, Boolean>>()
            if (wantGpu) {
                if (mtpCapable) attempts += "GPU" to true
                attempts += "GPU" to false
            }
            if (mtpCapable && !wantGpu) attempts += "CPU" to true
            attempts += "CPU" to false

            var lastErr: Throwable? = null
            for ((kind, mtp) in attempts) {
                post { onStatus("Démarrage $kind${if (mtp) " + turbo MTP" else ""}…") }
                val t0 = System.currentTimeMillis()
                try {
                    val e = createEngine(path, kind, mtp)
                    engine = e
                    backendLabel = kind + if (mtp) "·MTP" else ""
                    Log.i(TAG, "Moteur prêt en ${System.currentTimeMillis() - t0} ms ($backendLabel)")
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "Échec $kind mtp=$mtp", t)
                    lastErr = t
                }
            }
            if (engine == null) {
                post { onResult(false, "Impossible de charger le modèle : ${lastErr?.message ?: "erreur inconnue"}") }
                return@execute
            }
            try {
                conversation = newConversation(history)
                ready = true
                post { onResult(true, "$modelLabel · $backendLabel") }
            } catch (t: Throwable) {
                Log.e(TAG, "createConversation", t)
                closeInternal()
                post { onResult(false, "Erreur conversation : ${t.message}") }
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun createEngine(path: String, kind: String, mtp: Boolean): Engine {
        val backend: Backend = if (kind == "GPU") Backend.GPU() else Backend.CPU(threadCount = bigCores())
        val cfg = EngineConfig(
            modelPath = path,
            backend = backend,
            maxNumTokens = prefs.contextTokens,
            cacheDir = File(ctx.cacheDir, "litert").apply { mkdirs() }.absolutePath,
        )
        ExperimentalFlags.enableSpeculativeDecoding = mtp
        val e = Engine(cfg)
        try {
            e.initialize()
        } catch (t: Throwable) {
            try { e.close() } catch (_: Throwable) {}
            throw t
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = false
        }
        return e
    }

    private fun supportsMtp(path: String): Boolean = try {
        ModelInfo.from(path).use { info -> info is ModelInfo.Llm && info.hasSpeculativeDecodingSupport() }
    } catch (t: Throwable) {
        false
    }

    /** Snapdragon 6 Gen 4 : 1 + 4 cœurs performants. On laisse les petits cœurs au système et à la voix. */
    private fun bigCores(): Int = (Runtime.getRuntime().availableProcessors() - 3).coerceIn(2, 6)

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.FRANCE).format(Date())
        val sb = StringBuilder(prefs.persona.trim())
        sb.append("\n\nNous sommes le ").append(now).append('.')
        if (prefs.userName.isNotBlank()) sb.append(" Ton interlocuteur s'appelle ").append(prefs.userName.trim()).append('.')
        return sb.toString()
    }

    private fun newConversation(history: List<ChatMsg>): Conversation {
        val e = engine ?: error("moteur absent")
        // On réinjecte la fin de l'historique dans ~40 % du contexte : Nox garde le fil, même après redémarrage.
        val budgetChars = (prefs.contextTokens * CHARS_PER_TOKEN * 0.40).toInt()
        val kept = ArrayList<ChatMsg>()
        var used = 0
        for (m in history.asReversed()) {
            if (m.text.isBlank()) continue
            if (used + m.text.length > budgetChars) break
            kept.add(0, m); used += m.text.length
        }
        while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeAt(0) // doit commencer par l'utilisateur
        val initial = kept.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) }
        val sys = systemPrompt()
        charsInContext = used + sys.length
        val sampler = SamplerConfig(topK = 40, topP = 0.95, temperature = prefs.temperature.toDouble())
        return e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(sys),
                initialMessages = initial,
                samplerConfig = sampler,
            )
        )
    }

    /** Recrée la conversation (nouveau sujet, réglages modifiés…) sans recharger le modèle. */
    fun resetConversation(history: List<ChatMsg>, done: (() -> Unit)? = null) {
        worker.execute {
            try {
                conversation?.close()
            } catch (_: Throwable) {}
            conversation = null
            if (engine != null) {
                try { conversation = newConversation(history) } catch (t: Throwable) { Log.e(TAG, "reset", t) }
            }
            post { done?.invoke() }
        }
    }

    // ------------------------------------------------------------------ génération

    /**
     * @param history historique AVANT ce message (sert à compacter la mémoire si le contexte sature).
     */
    fun send(text: String, history: List<ChatMsg>, l: StreamListener) {
        if (busy) return
        busy = true
        worker.execute {
            // Contexte bientôt plein ? On repart d'une conversation fraîche avec la fin de l'historique.
            val projected = charsInContext + text.length + 1200
            if (projected > prefs.contextTokens * CHARS_PER_TOKEN * 0.80) {
                Log.i(TAG, "Compactage du contexte")
                try { conversation?.close() } catch (_: Throwable) {}
                conversation = try { newConversation(history) } catch (t: Throwable) { null }
            }
            val conv = conversation
            if (conv == null) {
                busy = false
                post { l.onError("Le modèle n'est pas prêt") }
                return@execute
            }
            charsInContext += text.length
            val t0 = System.nanoTime()
            var tFirst = 0L
            var chars = 0
            try {
                conv.sendMessageAsync(Message.user(text), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val chunk = message.toString()
                        if (chunk.isEmpty()) return
                        if (tFirst == 0L) tFirst = System.nanoTime()
                        chars += chunk.length
                        post { l.onChunk(chunk) }
                    }

                    override fun onDone() {
                        charsInContext += chars
                        busy = false
                        val stats = stats(t0, tFirst, chars)
                        post { l.onDone(stats) }
                    }

                    override fun onError(throwable: Throwable) {
                        charsInContext += chars
                        busy = false
                        if (throwable is CancellationException) {
                            post { l.onDone("interrompu") }
                        } else {
                            Log.e(TAG, "génération", throwable)
                            post { l.onError(throwable.message ?: "erreur de génération") }
                        }
                    }
                })
            } catch (t: Throwable) {
                busy = false
                Log.e(TAG, "sendMessageAsync", t)
                post { l.onError(t.message ?: "erreur") }
            }
        }
    }

    private fun stats(t0: Long, tFirst: Long, chars: Int): String {
        if (tFirst == 0L) return ""
        val now = System.nanoTime()
        val ttft = (tFirst - t0) / 1e9
        val dec = (now - tFirst) / 1e9
        val tokens = chars / CHARS_PER_TOKEN
        val tps = if (dec > 0.05) tokens / dec else 0.0
        return String.format(Locale.FRANCE, "%.1f s avant 1er mot · ≈%.0f tok/s · %s", ttft, tps, backendLabel)
    }

    fun stop() {
        try { conversation?.cancelProcess() } catch (_: Throwable) {}
    }

    fun release() {
        stop()
        worker.execute { closeInternal() }
        worker.shutdown()
    }

    private fun closeInternal() {
        ready = false
        try { conversation?.close() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        conversation = null
        engine = null
        busy = false
    }

    private fun post(r: () -> Unit) = main.post(r)

    companion object {
        private const val TAG = "NoxLLM"
        /** Approximation pour le français avec le tokenizer Gemma. */
        const val CHARS_PER_TOKEN = 3.6
    }
}
