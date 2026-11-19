package com.application.nox

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
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
 * Le harnais de Nox autour de LiteRT-LM :
 *  - cascade de démarrage GPU+MTP → GPU → CPU (+ repli sans audio, + repli 4K) ;
 *  - écoute native : l'audio va directement dans Gemma (sans reconnaissance vocale intermédiaire) ;
 *  - compétences (function calling) exécutées automatiquement par le moteur ;
 *  - mémoire longue injectée dans la consigne système, préambule pré-calculé au démarrage ;
 *  - compactage automatique du contexte, filtre des blocs de réflexion.
 */
class LlmEngine(
    private val ctx: Context,
    private val prefs: Prefs,
    private val store: ModelStore,
    private val skills: Skills,
    private val library: Library,
) {

    interface StreamListener {
        fun onChunk(text: String)
        fun onDone(stats: String)
        fun onError(msg: String)
        /** Écoute native : ce que Gemma a entendu (pour remplir la bulle de l'utilisateur). */
        fun onTranscript(text: String) {}
        /** Bibliothèque : n extraits injectés automatiquement avant la réponse. */
        fun onContext(n: Int) {}
    }

    private data class Attempt(val kind: String, val mtp: Boolean, val ctx: Int, val audio: Boolean, val vision: Boolean)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nox-llm").apply { priority = Thread.MAX_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile var busy = false; private set
    @Volatile var ready = false; private set

    var backendLabel = ""; private set
    var modelLabel = ""; private set
    /** Le modèle chargé accepte l'audio (écoute native possible). */
    var audioReady = false; private set
    /** Le modèle chargé voit les images (vue 📷). */
    var visionReady = false; private set
    /** Les compétences sont branchées sur la conversation en cours. */
    var skillsActive = false; private set

    private var toolsCapable = false
    private var ctxTokens = 4096
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
            val file = File(path)
            val spec = store.specForFile(file.name)
            modelLabel = spec?.title?.substringBefore("  ") ?: file.nameWithoutExtension
            val loadClock = NoxLog.chrono()
            NoxLog.i("MOTEUR", "Chargement de ${file.name} (${NoxLog.gb(file.length())}) · demandé : ${prefs.backend.uppercase()}, turbo=${prefs.turbo}, " +
                "contexte=${prefs.contextTokens}, écoute=${prefs.listenMode}, vue=${prefs.vision}, compétences=${prefs.skillsEnabled}")
            NoxLog.i("MOTEUR", "Avant chargement : ${NoxLog.memSnapshot()}")

            val probeClock = NoxLog.chrono()
            val info = cachedProbe(file)
            NoxLog.i("MOTEUR", "Capacités du fichier (${probeClock.ms()} ms) : MTP=${info.mtp}, outils déclarés=${info.tools}")
            val mtpCapable = prefs.turbo && info.mtp
            // Les fichiers Gemma 4 E de litert-community ne déclarent pas le function calling dans leurs
            // métadonnées, alors que le modèle le gère : on l'active quand même (repli automatique si refus).
            toolsCapable = info.tools || isGemmaE(file.name)
            if (!info.tools && toolsCapable) NoxLog.i("MOTEUR", "Outils forcés : Gemma série E sait appeler des fonctions")
            val multimodal = isGemmaE(file.name)
            val wantAudio = prefs.listenMode == "native" && multimodal
            val wantVision = prefs.vision && multimodal
            val wantGpu = prefs.backend == "gpu" && spec?.cpuOnly != true
            val ctxWanted = minOf(prefs.contextTokens, spec?.maxCtx ?: Int.MAX_VALUE)

            // Cascade : d'abord tout (oreille + vue), puis on lâche la vue, puis l'oreille, puis le GPU.
            val attempts = mutableListOf<Attempt>()
            val modes = linkedSetOf(wantAudio to wantVision, wantAudio to false, false to false).toList()
            for ((audio, vision) in modes) {
                if (wantGpu) {
                    if (mtpCapable) attempts += Attempt("GPU", true, ctxWanted, audio, vision)
                    attempts += Attempt("GPU", false, ctxWanted, audio, vision)
                }
                if (mtpCapable && !wantGpu) attempts += Attempt("CPU", true, ctxWanted, audio, vision)
                attempts += Attempt("CPU", false, ctxWanted, audio, vision)
            }
            if (ctxWanted > 4096) attempts += Attempt("CPU", false, 4096, false, false)

            NoxLog.d("MOTEUR", "Cascade prévue : " + attempts.joinToString(" → ") { "${it.kind}${if (it.mtp) "+MTP" else ""}${if (it.audio) "+A" else ""}${if (it.vision) "+V" else ""}/${it.ctx}" })
            var lastErr: Throwable? = null
            for ((idx, a) in attempts.withIndex()) {
                post { onStatus("Démarrage ${a.kind}${if (a.mtp) " + turbo" else ""}${if (a.audio) " + oreille" else ""}${if (a.vision) " + vue" else ""}…") }
                val t0 = System.currentTimeMillis()
                try {
                    engine = createEngine(path, a)
                    ctxTokens = a.ctx
                    audioReady = a.audio
                    visionReady = a.vision
                    backendLabel = a.kind + (if (a.mtp) "·MTP" else "")
                    NoxLog.p("MOTEUR", "Moteur prêt en ${System.currentTimeMillis() - t0} ms · essai ${idx + 1}/${attempts.size} · " +
                        "${a.kind}${if (a.mtp) "+MTP" else ""} · oreille=${a.audio} · vue=${a.vision} · contexte=${a.ctx}")
                    break
                } catch (t: Throwable) {
                    NoxLog.w("MOTEUR", "Essai ${idx + 1} échoué en ${System.currentTimeMillis() - t0} ms (${a.kind}${if (a.mtp) "+MTP" else ""}, oreille=${a.audio}, vue=${a.vision})", t)
                    lastErr = t
                }
            }
            if (engine == null) {
                NoxLog.e("MOTEUR", "Aucune configuration n'a démarré", lastErr)
                post { onResult(false, "Impossible de charger le modèle : ${lastErr?.message ?: "erreur inconnue"}") }
                return@execute
            }
            try {
                post { onStatus("Préparation de la mémoire…") }
                val prefill = NoxLog.chrono()
                conversation = newConversation(history)
                NoxLog.p("MOTEUR", "Conversation prête en ${prefill.ms()} ms (consigne + ${if (skillsActive) "outils + " else ""}historique pré-calculés, ≈${(charsInContext / CHARS_PER_TOKEN).toInt()} jetons)")
                NoxLog.p("MOTEUR", "Chargement total ${loadClock.ms()} ms · ${NoxLog.memSnapshot()}")
                ready = true
                val extras = listOfNotNull(if (audioReady) "oreille" else null, if (visionReady) "vue" else null, if (skillsActive) "compétences" else null)
                post { onResult(true, "$modelLabel · $backendLabel" + if (extras.isEmpty()) "" else " · " + extras.joinToString(" + ")) }
            } catch (t: Throwable) {
                NoxLog.e("MOTEUR", "Création de la conversation impossible", t)
                closeInternal()
                post { onResult(false, "Erreur conversation : ${t.message}") }
            }
        }
    }

    private data class Probe(val mtp: Boolean, val tools: Boolean)

    /** L'analyse du fichier coûte ~2 s : on la garde en cache (clé = nom + taille). */
    private fun cachedProbe(f: File): Probe {
        val sp = ctx.getSharedPreferences("nox_probe", Context.MODE_PRIVATE)
        val key = "${f.name}:${f.length()}"
        sp.getString(key, null)?.split(',')?.let { v -> if (v.size == 2) return Probe(v[0] == "1", v[1] == "1") }
        val p = probe(f.absolutePath)
        sp.edit().putString(key, "${if (p.mtp) 1 else 0},${if (p.tools) 1 else 0}").apply()
        return p
    }

    private fun probe(path: String): Probe = try {
        ModelInfo.from(path).use { info ->
            if (info is ModelInfo.Llm) Probe(info.hasSpeculativeDecodingSupport(), info.supportsFunctionCalling())
            else Probe(false, false)
        }
    } catch (t: Throwable) {
        Probe(false, true) // inconnu : on tente, la conversation se rabat sans outils si besoin
    }

    /** Les modèles « E » de Gemma (3n, 4) embarquent des encodeurs audio et image. */
    private fun isGemmaE(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return (n.contains("gemma-4") || n.contains("gemma-3n") || n.contains("gemma4") || n.contains("gemma3n")) &&
            (n.contains("e2b") || n.contains("e4b"))
    }

    @OptIn(ExperimentalApi::class)
    private fun createEngine(path: String, a: Attempt): Engine {
        val backend: Backend = if (a.kind == "GPU") Backend.GPU() else Backend.CPU(threadCount = bigCores())
        val cfg = EngineConfig(
            modelPath = path,
            backend = backend,
            audioBackend = if (a.audio) Backend.CPU(threadCount = 2) else null, // l'encodeur audio doit être sur CPU
            visionBackend = if (a.vision) (if (a.kind == "GPU") Backend.GPU() else Backend.CPU(threadCount = bigCores())) else null,
            maxNumTokens = a.ctx,
            cacheDir = File(ctx.cacheDir, "litert").apply { mkdirs() }.absolutePath,
        )
        ExperimentalFlags.enableSpeculativeDecoding = a.mtp
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

    /** Snapdragon 6 Gen 4 : 1 + 4 cœurs performants. On laisse les petits cœurs au système et à la voix. */
    private fun bigCores(): Int = (Runtime.getRuntime().availableProcessors() - 3).coerceIn(2, 6)

    private fun systemPrompt(withSkills: Boolean): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.FRANCE).format(Date())
        val sb = StringBuilder(prefs.persona.trim())
        sb.append("\n\nNous sommes le ").append(now).append('.')
        if (prefs.userName.isNotBlank()) sb.append(" Ton interlocuteur s'appelle ").append(prefs.userName.trim()).append('.')
        if (withSkills) {
            sb.append("\n\nTu agis vraiment sur le téléphone grâce à tes outils. Dès qu'on te demande une action ou une info du téléphone ")
                .append("(heure, minuteur, rappel, liste, musique, appel, SMS, trajet, appli, réglage, agenda…), appelle l'outil au lieu de répondre de tête. ")
                .append("Tout calcul passe par « calculer ». Si l'utilisateur évoque quelque chose qu'il t'a déjà dit, noté ou importé, cherche dans « bibliotheque ». ")
                .append("Après une action, confirme en une phrase naturelle, sans détailler l'outil.")
        }
        sb.append(skills.memory.promptBlock())
        return sb.toString()
    }

    private fun newConversation(history: List<ChatMsg>): Conversation {
        val e = engine ?: error("moteur absent")
        val budgetChars = (ctxTokens * CHARS_PER_TOKEN * 0.40).toInt()
        val kept = ArrayList<ChatMsg>()
        var used = 0
        for (m in history.asReversed()) {
            if (m.text.isBlank()) continue
            if (used + m.text.length > budgetChars) break
            kept.add(0, m); used += m.text.length
        }
        while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeAt(0)
        val initial = kept.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) }
        val sampler = SamplerConfig(topK = 40, topP = 0.95, temperature = prefs.temperature.toDouble())

        skills.conversationStart = kept.firstOrNull()?.time ?: System.currentTimeMillis()

        fun build(withSkills: Boolean): Conversation {
            val sys = systemPrompt(withSkills)
            charsInContext = used + sys.length + if (withSkills) SKILLS_PROMPT_CHARS else 0
            return e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(sys),
                    initialMessages = initial,
                    tools = if (withSkills) skills.providers() else emptyList(),
                    samplerConfig = sampler,
                    extraContext = mapOf("enable_thinking" to false),
                    // Consigne + outils + historique calculés UNE fois maintenant : le 1er mot arrive bien plus vite.
                    prefillPrefaceOnInit = true,
                )
            )
        }

        val want = prefs.skillsEnabled && toolsCapable
        if (want) {
            try {
                return build(true).also { skillsActive = true }
            } catch (t: Throwable) {
                NoxLog.w("MOTEUR", "Compétences refusées par ce modèle, on continue sans", t)
            }
        }
        skillsActive = false
        return build(false)
    }

    fun resetConversation(history: List<ChatMsg>, done: (() -> Unit)? = null) {
        worker.execute {
            try { conversation?.close() } catch (_: Throwable) {}
            conversation = null
            if (engine != null) {
                try { conversation = newConversation(history) } catch (t: Throwable) { NoxLog.e("MOTEUR", "Réinitialisation de la conversation", t) }
            }
            post { done?.invoke() }
        }
    }

    // ------------------------------------------------------------------ génération

    /**
     * Message texte. La bibliothèque est interrogée d'abord (quelques ms) : si des extraits nettement
     * pertinents existent, ils sont glissés devant le message — Gemma répond avec, sans appel d'outil.
     */
    fun send(text: String, history: List<ChatMsg>, l: StreamListener, image: ByteArray? = null) {
        if (busy) return
        busy = true
        worker.execute {
            val ragClock = NoxLog.chrono()
            val hits = try {
                if (prefs.ragAuto) library.search(text, k = 3, excludeChatsAfter = skills.conversationStart, strict = true) else emptyList()
            } catch (t: Throwable) { NoxLog.e("RAG", "Recherche automatique", t); emptyList() }
            lastRagMs = ragClock.ms(); lastRagHits = hits.size
            if (hits.isNotEmpty()) NoxLog.i("RAG", "Injection auto ${hits.size} extrait(s) en $lastRagMs ms : " + hits.joinToString(" | ") { "${it.chunk.title.take(30)} (${it.why})" })
            else NoxLog.d("RAG", "Rien d'assez pertinent ($lastRagMs ms)")
            val ctxBlock = library.contextBlock(hits)
            if (hits.isNotEmpty()) post { l.onContext(hits.size) }
            val full = if (ctxBlock.isEmpty()) text else ctxBlock + "\nMessage de l'utilisateur : " + text
            val msg = if (image != null && visionReady)
                Message.user(Contents.of(Content.ImageBytes(image), Content.Text(full.ifBlank { "Décris cette image." })))
            else Message.user(full)
            val extra = if (image != null) (280 * CHARS_PER_TOKEN).toInt() else 0
            if (image != null) NoxLog.i("VUE", "Photo envoyée à Gemma : ${image.size / 1024} Ko")
            submitNow(msg, full.length + extra, null, history, l, if (image != null) "photo" else "texte")
        }
    }

    /** Écoute native : le son brut part directement dans Gemma, qui transcrit puis répond. */
    fun sendAudio(wav: ByteArray, durationMs: Long, history: List<ChatMsg>, l: StreamListener) {
        val msg = Message.user(Contents.of(Content.AudioBytes(wav), Content.Text(AUDIO_INSTRUCTION)))
        // ~25 jetons audio par seconde chez Gemma
        val approxChars = ((durationMs / 1000.0) * 25 * CHARS_PER_TOKEN).toInt() + AUDIO_INSTRUCTION.length
        if (busy) return
        busy = true
        lastRagMs = 0; lastRagHits = 0
        NoxLog.i("OREILLE", "Message vocal envoyé à Gemma : ${durationMs} ms d'audio, ${wav.size / 1024} Ko")
        worker.execute { submitNow(msg, approxChars, TranscriptExtractor(), history, l, "voix") }
    }

    /** Toujours appelé sur le fil du modèle. */
    private fun submitNow(msg: Message, approxChars: Int, transcript: TranscriptExtractor?, history: List<ChatMsg>, l: StreamListener, mode: String) {
        run {
            val projected = charsInContext + approxChars + 1200
            if (projected > ctxTokens * CHARS_PER_TOKEN * 0.80) {
                val c = NoxLog.chrono()
                try { conversation?.close() } catch (_: Throwable) {}
                conversation = try { newConversation(history) } catch (t: Throwable) { NoxLog.e("MOTEUR", "Compactage raté", t); null }
                NoxLog.p("MOTEUR", "Compactage du contexte (plein à ${(projected * 100 / (ctxTokens * CHARS_PER_TOKEN)).toInt()} %) refait en ${c.ms()} ms")
            }
            val conv = conversation
            if (conv == null) {
                busy = false
                post { l.onError("Le modèle n'est pas prêt") }
                return@run
            }
            charsInContext += approxChars
            skills.callsThisTurn.set(0)
            NoxLog.d("GEN", "Envoi ($mode, ≈${(approxChars / CHARS_PER_TOKEN).toInt()} jetons) · ${NoxLog.memSnapshot()}")
            val t0 = System.nanoTime()
            var tFirst = 0L
            var chars = 0
            val think = ThinkFilter()
            try {
                conv.sendMessageAsync(msg, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        var chunk = think.feed(message.toString())
                        if (transcript != null) {
                            chunk = transcript.feed(chunk)
                            transcript.take()?.let { heard -> post { l.onTranscript(heard) } }
                        }
                        if (chunk.isEmpty()) return
                        if (tFirst == 0L) tFirst = System.nanoTime()
                        chars += chunk.length
                        post { l.onChunk(chunk) }
                    }

                    override fun onDone() {
                        transcript?.flush()?.let { rest -> if (rest.isNotEmpty()) { chars += rest.length; post { l.onChunk(rest) } } }
                        charsInContext += chars
                        busy = false
                        val stats = stats(t0, tFirst, chars)
                        record(mode, t0, tFirst, chars)
                        post { l.onDone(stats) }
                    }

                    override fun onError(throwable: Throwable) {
                        charsInContext += chars
                        busy = false
                        if (throwable is CancellationException) {
                            NoxLog.i("GEN", "Génération interrompue après $chars caractères")
                            post { l.onDone("interrompu") }
                        } else {
                            NoxLog.e("GEN", "Erreur pendant la génération ($mode)", throwable)
                            post { l.onError(throwable.message ?: "erreur de génération") }
                        }
                    }
                })
            } catch (t: Throwable) {
                busy = false
                NoxLog.e("GEN", "Envoi impossible", t)
                post { l.onError(t.message ?: "erreur") }
            }
        }
    }

    @Volatile private var lastRagMs = 0L
    @Volatile private var lastRagHits = 0

    private fun record(mode: String, t0: Long, tFirst: Long, chars: Int) {
        if (tFirst == 0L) { NoxLog.w("GEN", "Réponse vide ($mode)"); return }
        val now = System.nanoTime()
        val dec = (now - tFirst) / 1_000_000
        val tokens = (chars / CHARS_PER_TOKEN).toInt()
        NoxLog.turn(NoxLog.Turn(System.currentTimeMillis(), mode, (tFirst - t0) / 1_000_000, dec, tokens,
            if (dec > 50) tokens * 1000.0 / dec else 0.0, lastRagMs, lastRagHits, skills.callsThisTurn.get(),
            (charsInContext * 100 / (ctxTokens * CHARS_PER_TOKEN)).toInt(), backendLabel))
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
        audioReady = false
        visionReady = false
        skillsActive = false
        try { conversation?.close() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        conversation = null
        engine = null
        busy = false
    }

    private fun post(r: () -> Unit) = main.post(r)

    companion object {
        const val CHARS_PER_TOKEN = 3.6
        /** Estimation de la place prise par la description des outils dans le contexte. */
        private const val SKILLS_PROMPT_CHARS = 4200

        const val AUDIO_INSTRUCTION =
            "Ceci est un message vocal de ton interlocuteur. Écris d'abord, seul sur la première ligne, " +
            "la transcription fidèle de ce qu'il dit entre doubles crochets, comme ceci : [[texte entendu]]. " +
            "Puis, à la ligne, réponds-lui normalement, comme s'il t'avait parlé de vive voix."
    }
}

/**
 * Extrait la ligne [[transcription]] que Gemma écrit en tête de réponse en écoute native,
 * pour l'afficher dans la bulle de l'utilisateur sans la lire à voix haute.
 */
class TranscriptExtractor {
    private val buf = StringBuilder()
    private var done = false
    private var heard: String? = null

    private var trimNext = true

    fun feed(chunk: String): String {
        if (done) {
            if (!trimNext) return chunk
            val t = chunk.trimStart()
            if (t.isNotEmpty()) trimNext = false
            return t
        }
        buf.append(chunk)
        val s = buf.toString()
        val trimmed = s.trimStart()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("[[") && !"[[".startsWith(trimmed)) { done = true; trimNext = false; buf.setLength(0); return s }
        val end = trimmed.indexOf("]]")
        if (end >= 0) {
            heard = trimmed.substring(2, end).trim().trim('"', '«', '»', ' ')
            done = true
            buf.setLength(0)
            val rest = trimmed.substring(end + 2).trimStart()
            if (rest.isNotEmpty()) trimNext = false
            return rest
        }
        if (trimmed.length > 600) { done = true; trimNext = false; buf.setLength(0); return trimmed } // pas de fermeture : on abandonne
        return ""
    }

    /** Transcription disponible (une seule fois). */
    fun take(): String? = heard.also { heard = null }

    fun flush(): String? {
        if (done) return null
        done = true
        val rest = buf.toString().removePrefix("[[").trim()
        buf.setLength(0)
        return rest
    }
}

/**
 * Masque les blocs <think>…</think> que certains modèles (Qwen3, Phi-4 reasoning…) écrivent
 * avant leur réponse, même si une balise arrive coupée entre deux morceaux du flux.
 */
class ThinkFilter {
    private val pending = StringBuilder()
    private var inThink = false
    private var started = false

    fun feed(chunk: String): String {
        pending.append(chunk)
        val out = StringBuilder()
        while (pending.isNotEmpty()) {
            val tag = if (inThink) "</think>" else "<think>"
            val idx = pending.indexOf(tag)
            if (idx >= 0) {
                if (!inThink) out.append(pending, 0, idx)
                pending.delete(0, idx + tag.length)
                inThink = !inThink
                continue
            }
            var keep = 0
            for (k in minOf(tag.length - 1, pending.length) downTo 1) {
                if (tag.startsWith(pending.substring(pending.length - k))) { keep = k; break }
            }
            if (!inThink) out.append(pending, 0, pending.length - keep)
            pending.delete(0, pending.length - keep)
            break
        }
        var text = out.toString()
        if (!started) {
            text = text.trimStart()
            if (text.isNotEmpty()) started = true
        }
        return text
    }
}
