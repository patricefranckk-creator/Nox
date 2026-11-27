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
        /** Bibliothèque : extraits injectés automatiquement avant la réponse (titres). */
        fun onContext(titles: List<String>) {}
        /** Relances proposées par Nox à la fin de sa réponse. */
        fun onSuggestions(list: List<String>) {}
    }

    private data class Attempt(val kind: String, val mtp: Boolean, val ctx: Int, val audio: Boolean, val vision: Boolean) {
        fun key() = kind + (if (mtp) "+MTP" else "") + (if (audio) "+A" else "") + (if (vision) "+V" else "") + "/" + ctx
    }

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
    /** Taille des extraits (web, bibliothèque, écran) glissés dans le dernier message. */
    private var heavyChars = 0
    /** Génération en cours, déclarée au pilotage des ressources (la maintenance attend sa fin). */
    @Volatile private var inference: ResourceManager.Lease? = null
    @Volatile private var lightenPending = false

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
            val ctxWanted = ResourceManager.contextFor(minOf(prefs.contextTokens, spec?.maxCtx ?: Int.MAX_VALUE))

            // Cascade : toutes les variantes GPU, de la plus complète à la plus légère, puis le CPU.
            val attempts = mutableListOf<Attempt>()
            val modes = linkedSetOf(wantAudio to wantVision, wantAudio to false, false to false).toList()
            if (wantGpu) for ((audio, vision) in modes) {
                if (mtpCapable) attempts += Attempt("GPU", true, ctxWanted, audio, vision)
                attempts += Attempt("GPU", false, ctxWanted, audio, vision)
            }
            for ((audio, vision) in modes) {
                if (mtpCapable) attempts += Attempt("CPU", true, ctxWanted, audio, vision)
                attempts += Attempt("CPU", false, ctxWanted, audio, vision)
            }
            // Contexte large refusé (mémoire) : on retente en 4K, GPU d'abord
            if (ctxWanted > 4096) {
                if (wantGpu) attempts += Attempt("GPU", false, 4096, wantAudio, false)
                attempts += Attempt("CPU", false, 4096, false, false)
            }

            NoxLog.d("MOTEUR", "Cascade prévue : " + attempts.joinToString(" → ") { "${it.kind}${if (it.mtp) "+MTP" else ""}${if (it.audio) "+A" else ""}${if (it.vision) "+V" else ""}/${it.ctx}" })
            var lastErr: Throwable? = null
            val bad = prefs.gpuBad
            for ((idx, a) in attempts.withIndex()) {
                val key = a.key()
                if (a.kind == "GPU" && key in bad) { NoxLog.i("MOTEUR", "Essai ${idx + 1} sauté : $key a déjà fait planter le pilote GPU"); continue }
                if (a.kind == "GPU") prefs.gpuPending = key
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
                    if (a.kind == "GPU") prefs.gpuPending = ""
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
                prefs.gpuPending = ""
                ready = true
                val extras = listOfNotNull(if (audioReady) "oreille" else null, if (visionReady) "vue" else null, if (skillsActive) "compétences" else null)
                post { onResult(true, "$modelLabel · $backendLabel" + if (extras.isEmpty()) "" else " · " + extras.joinToString(" + ")) }
            } catch (t: Throwable) {
                prefs.gpuPending = ""
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

    private fun systemPrompt(withSkills: Boolean, recap: String = ""): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.FRANCE).format(Date())
        val sb = StringBuilder(prefs.persona.trim())
        sb.append("\n\nNous sommes le ").append(now).append('.')
        if (prefs.userName.isNotBlank()) sb.append(" Ton interlocuteur s'appelle ").append(prefs.userName.trim()).append('.')
        if (withSkills) {
            sb.append("\n\nPour toute action ou info du téléphone, appelle l'outil au lieu de répondre de tête ; tout calcul passe par « calculer ». ")
                .append("Pour piloter une appli : « ouvrir », puis « ecran » lire, agir, relire après chaque geste, jusqu'au bout. ")
                .append("Avant d'envoyer, appeler, payer ou supprimer, demande confirmation en une phrase ; si oui, confirme=true. ")
                .append("Après une action, confirme en une phrase, sans parler de l'outil.")
        }
        if (withSkills) {
            sb.append(" Ce qu'il te confie de durable sur lui, retiens-le avec « memoire » et sers-t'en pour personnaliser tes réponses.")
        }
        if (prefs.suggestions) {
            sb.append("\n\nTermine chaque réponse par une dernière ligne qui commence par >> suivie de 2 ou 3 relances très courtes ")
                .append("(5 mots maximum), séparées par |, que ton interlocuteur pourrait te dire ensuite. Exemple : >> Raconte-m'en plus | Et demain ?")
        }
        sb.append(skills.memory.promptBlock())
        if (recap.isNotEmpty()) sb.append("\n\nPlus tôt dans cette conversation (résumé) :\n").append(recap)
        return sb.toString()
    }

    /** Texte exact que Gemma reçoit en tête de conversation (consigne, souvenirs, outils), pour le diagnostic. */
    fun promptPreview(): String {
        val withSkills = prefs.skillsEnabled && toolsCapable
        val sb = StringBuilder(systemPrompt(withSkills))
        if (withSkills) {
            sb.append("\n\n[Outils décrits à Gemma]")
            for (k in skills.all) sb.append("\n- ").append(k.name).append(" : ").append(k.description)
                .append(if (k.params.length() > 0) " · paramètres " + k.params.toString() else "")
        }
        return sb.toString()
    }

    /** Résumé sans le modèle : chaque question, et la première phrase de chaque réponse. */
    private fun recap(older: List<ChatMsg>, max: Int): String {
        if (older.isEmpty() || max < 120) return ""
        val lines = older.map { m ->
            val t = m.text.replace(Regex("\\s+"), " ").trim()
            if (m.fromUser) "- Toi : " + t.take(120)
            else "- Nox : " + (Regex("^.{20,160}?[.!?](?=\\s|$)").find(t)?.value ?: t.take(140))
        }
        val out = ArrayList<String>()
        var n = 0
        for (l in lines.asReversed()) { if (n + l.length + 1 > max) break; out.add(0, l); n += l.length + 1 }
        return out.joinToString("\n")
    }

    /** Consigne + outils : la part fixe de la mémoire de travail. */
    private var baseChars = 0

    /** Place libre pour un message (et sa réponse) après un compactage complet. */
    private fun roomAfterCompaction(): Int =
        (ctxTokens * CHARS_PER_TOKEN * USABLE).toInt() - baseChars - ANSWER_RESERVE

    private fun newConversation(history: List<ChatMsg>, reserve: Int = 0): Conversation {
        val e = engine ?: error("moteur absent")
        baseChars = systemPrompt(prefs.skillsEnabled && toolsCapable).length + if (prefs.skillsEnabled && toolsCapable) SKILLS_PROMPT_CHARS else 0
        val budgetChars = minOf((ctxTokens * CHARS_PER_TOKEN * 0.40).toInt(), roomAfterCompaction() - reserve).coerceAtLeast(0)
        val kept = ArrayList<ChatMsg>()
        var used = 0
        val usable = history.filter { it.text.isNotBlank() }
        for (m in usable.asReversed()) {
            if (kept.size >= FULL_MESSAGES || used + m.text.length > budgetChars) break
            kept.add(0, m); used += m.text.length
        }
        while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeAt(0)
        // Les échanges plus anciens ne repartent pas en entier : un court résumé (questions + 1re phrase des réponses)
        val older = usable.take(usable.size - kept.size).takeLast(RECAP_MESSAGES)
        val recap = recap(older, (budgetChars - used).coerceIn(0, RECAP_CHARS))
        if (recap.isNotEmpty()) NoxLog.d("MOTEUR", "Résumé des ${older.size} messages plus anciens : ${recap.length} caractères au lieu de ${older.sumOf { it.text.length }}")
        val initial = kept.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) }
        val sampler = SamplerConfig(topK = 40, topP = 0.95, temperature = prefs.temperature.toDouble())

        skills.conversationStart = kept.firstOrNull()?.time ?: System.currentTimeMillis()

        fun build(withSkills: Boolean): Conversation {
            val sys = systemPrompt(withSkills, recap)
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

    /**
     * Après une réponse appuyée sur des extraits (web, bibliothèque, écran), refait la conversation en fond
     * avec le seul texte échangé : les extraits ne restent pas dans la mémoire de travail, les compactages
     * deviennent rares. La réponse, elle, reste dans l'historique.
     */
    fun lighten(history: List<ChatMsg>) {
        val full = charsInContext > ctxTokens * CHARS_PER_TOKEN * USABLE * ResourceManager.earlyCompact()
        if ((!lightenPending && !full) || busy || engine == null) return
        if (full && !lightenPending) NoxLog.i("MOTEUR", "Mémoire de travail bien remplie : compactage d'avance, pendant que tu lis")
        lightenPending = false
        heavyChars = 0
        val before = charsInContext
        val c = NoxLog.chrono()
        resetConversation(history) { NoxLog.p("MOTEUR", "Extraits retirés de la mémoire de travail : $before → $charsInContext caractères en ${c.ms()} ms") }
    }

    /** La consigne a changé (souvenir ajouté ou oublié) : la conversation sera refaite en fond après la réponse. */
    fun refreshPrompt() { lightenPending = true }

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
    fun send(text: String, history: List<ChatMsg>, l: StreamListener, image: ByteArray? = null,
             context: String? = null, contextTitles: List<String>? = null, hint: String? = null, rag: Boolean = true) {
        if (busy) return
        busy = true
        worker.execute {
            if (context != null) {
                lastRagMs = 0; lastRagHits = 0
                if (!contextTitles.isNullOrEmpty()) post { l.onContext(contextTitles) }
                val room = (roomAfterCompaction() - text.length - 200).coerceAtLeast(600)
                val ctxText = if (context.length <= room) context else {
                    NoxLog.i("GEN", "Contexte réduit de ${context.length} à $room caractères (mémoire de travail ${ctxTokens} jetons)")
                    context.take(room).substringBeforeLast('\n').ifBlank { context.take(room) }
                }
                heavyChars = ctxText.length
                val full = ctxText + "\nMessage de l'utilisateur : " + text
                submitNow(Message.user(full), full.length, null, history, l, "texte")
                return@execute
            }
            val ragClock = NoxLog.chrono()
            val hits = try {
                // une photo parle d'elle-même : pas de fiches ajoutées automatiquement
                if (prefs.ragAuto && rag && image == null) library.search(text, k = 3, excludeChatsAfter = skills.conversationStart, strict = true) else emptyList()
            } catch (t: Throwable) { NoxLog.e("RAG", "Recherche automatique", t); emptyList() }
            lastRagMs = ragClock.ms(); lastRagHits = hits.size
            if (hits.isNotEmpty()) NoxLog.i("RAG", "Injection auto ${hits.size} extrait(s) en $lastRagMs ms : " + hits.joinToString(" | ") { "${it.chunk.title.take(30)} (${it.why})" })
            else if (!rag) NoxLog.d("RAG", "Message parlé : pas de fiches (seulement si demandé)")
            else NoxLog.d("RAG", "Rien d'assez pertinent ($lastRagMs ms)")
            val ctxBlock = library.contextBlock(hits)
            heavyChars = ctxBlock.length
            if (hits.isNotEmpty()) { val titles = hits.map { it.chunk.title }; post { l.onContext(titles) } }
            val asked = if (hint != null && skillsActive) "$text\n(outil adapté : « $hint »)" else text
            if (hint != null && skillsActive) NoxLog.d("GEN", "Indice d'outil : $hint")
            val full = if (ctxBlock.isEmpty()) asked else ctxBlock + "\nMessage de l'utilisateur : " + asked
            val msg = if (image != null && visionReady)
                Message.user(Contents.of(Content.ImageBytes(image), Content.Text(PHOTO_INSTRUCTION + "\n\n" + full.ifBlank { "Décris cette image." })))
            else Message.user(full)
            val extra = if (image != null) (280 * CHARS_PER_TOKEN).toInt() else 0
            if (image != null) NoxLog.i("VUE", "Photo envoyée à Gemma : ${image.size / 1024} Ko")
            submitNow(msg, full.length + extra, null, history, l, if (image != null) "photo" else "texte")
        }
    }

    /** Écoute native : le son brut part directement dans Gemma, qui transcrit puis répond. */
    fun sendAudio(wavs: List<ByteArray>, durationMs: Long, history: List<ChatMsg>, l: StreamListener) {
        if (busy) return
        busy = true
        lastRagMs = 0; lastRagHits = 0
        heavyChars = 0
        NoxLog.i("OREILLE", "Message vocal envoyé à Gemma : ${durationMs} ms d'audio en ${wavs.size} clip(s), ${wavs.sumOf { it.size } / 1024} Ko")
        worker.execute {
            // à l'oral, pas de fiches automatiques : Gemma garde sa manière d'être, ce qu'il sait de toi et les échanges récents
            NoxLog.d("RAG", "Message vocal : pas de fiches automatiques")
            val parts = ArrayList<Content>()
            wavs.forEach { parts += Content.AudioBytes(it) }
            parts += Content.Text(AUDIO_INSTRUCTION)
            val msg = Message.user(Contents.of(parts))
            // estimation prudente du coût de l'audio dans le contexte
            val approxChars = ((durationMs / 1000.0) * AUDIO_TOKENS_PER_S * CHARS_PER_TOKEN).toInt() + AUDIO_INSTRUCTION.length
            submitNow(msg, approxChars, TranscriptExtractor(), history, l, "voix")
        }
    }

    /** Toujours appelé sur le fil du modèle. */
    private fun submitNow(msg: Message, approxChars: Int, transcript: TranscriptExtractor?, history: List<ChatMsg>, l: StreamListener, mode: String,
                          retried: Boolean = false) {
        run {
            val projected = charsInContext + approxChars + ANSWER_RESERVE
            if (retried || projected > ctxTokens * CHARS_PER_TOKEN * USABLE) {
                val c = NoxLog.chrono()
                try { conversation?.close() } catch (_: Throwable) {}
                val keep = if (retried) emptyList() else history
                conversation = try { newConversation(keep, approxChars) } catch (t: Throwable) { NoxLog.e("MOTEUR", "Compactage raté", t); null }
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
            inference?.release()
            inference = ResourceManager.begin(ResourcePolicy.Activity.INFERENCE)
            NoxLog.d("GEN", "Envoi ($mode, ≈${(approxChars / CHARS_PER_TOKEN).toInt()} jetons) · ${NoxLog.memSnapshot()}")
            val t0 = System.nanoTime()
            var tFirst = 0L
            var chars = 0
            val think = ThinkFilter()
            val leak = LeakedCallFilter(skills.all.associate { s ->
                s.name to (s.params.optJSONArray("required")?.optString(0)?.takeIf { it.isNotEmpty() } ?: s.params.optJSONObject("properties")?.keys()?.asSequence()?.firstOrNull())
            })
            val suggest = if (prefs.suggestions) SuggestFilter() else null
            try {
                conv.sendMessageAsync(msg, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        var chunk = leak.feed(think.feed(message.toString()))
                        if (transcript != null) {
                            chunk = transcript.feed(chunk)
                            transcript.take()?.let { heard -> post { l.onTranscript(heard) } }
                        }
                        if (suggest != null) chunk = suggest.feed(chunk)
                        if (chunk.isEmpty()) return
                        if (tFirst == 0L) tFirst = System.nanoTime()
                        chars += chunk.length
                        post { l.onChunk(chunk) }
                    }

                    override fun onDone() {
                        leak.finish()?.let { rest ->
                            val shown = rest.ifEmpty { "" }
                            if (shown.isNotEmpty()) { if (tFirst == 0L) tFirst = System.nanoTime(); chars += shown.length; post { l.onChunk(shown) } }
                        }
                        leak.call?.let { (name, args) ->
                            val t = NoxLog.chrono()
                            val out = try { skills.all.first { it.name == name }.run(args) } catch (e: Throwable) { "Je n'ai pas pu utiliser l'outil $name : ${e.message}" }
                            NoxLog.w("OUTIL", "Appel d'outil écrit en texte par le modèle, exécuté quand même : $name($args) → ${out.take(80)} · ${t.ms()} ms")
                            if (tFirst == 0L) tFirst = System.nanoTime()
                            chars += out.length
                            post { l.onChunk(out) }
                        }
                        transcript?.flush()?.let { rest -> if (rest.isNotEmpty()) { chars += rest.length; post { l.onChunk(rest) } } }
                        suggest?.let { sf ->
                            sf.flushVisible().let { rest -> if (rest.isNotEmpty()) { chars += rest.length; post { l.onChunk(rest) } } }
                            val list = sf.suggestions()
                            if (list.isNotEmpty()) { NoxLog.d("GEN", "Relances : ${list.joinToString(" | ")}"); post { l.onSuggestions(list) } }
                        }
                        charsInContext += chars
                        if (heavyChars >= LIGHTEN_MIN) lightenPending = true
                        busy = false
                        inference?.release()
                        val stats = stats(t0, tFirst, chars)
                        record(mode, t0, tFirst, chars)
                        post { l.onDone(stats) }
                    }

                    override fun onError(throwable: Throwable) {
                        charsInContext += chars
                        busy = false
                        inference?.release()
                        if (throwable is CancellationException) {
                            NoxLog.i("GEN", "Génération interrompue après $chars caractères")
                            post { l.onDone("interrompu") }
                        } else if (!retried && chars == 0 && isContextFull(throwable)) {
                            NoxLog.w("GEN", "Mémoire de travail pleine : reprise sur une conversation allégée", throwable)
                            busy = true
                            worker.execute { submitNow(msg, approxChars, transcript, history, l, mode, retried = true) }
                        } else {
                            NoxLog.e("GEN", "Erreur pendant la génération ($mode)", throwable)
                            post { l.onError(friendly(throwable)) }
                        }
                    }
                })
            } catch (t: Throwable) {
                busy = false
                inference?.release()
                NoxLog.e("GEN", "Envoi impossible", t)
                post { l.onError(t.message ?: "erreur") }
            }
        }
    }

    /** Caractères de contexte (web, bibliothèque) qu'un message peut apporter sans déborder. */
    fun contextBudget(messageChars: Int): Int = (roomAfterCompaction() - messageChars - 200).coerceAtLeast(600)

    private fun isContextFull(t: Throwable) = (t.message ?: "").let { it.contains("exceeds available state", true) || it.contains("Status Code: 9") }

    private fun friendly(t: Throwable): String = when {
        isContextFull(t) -> "Ma mémoire de travail est pleine, même allégée. Raccourcis la demande ou passe le contexte à 8K dans Réglages."
        else -> "Erreur du moteur : ${t.message ?: "inconnue"}"
    }

    /** Remplissage estimé de la mémoire de travail (pour les jauges). */
    fun contextPct(): Int = if (!ready) 0 else (charsInContext * 100 / (ctxTokens * CHARS_PER_TOKEN)).toInt().coerceIn(0, 100)

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
        ResourceManager.onTurn((tFirst - t0) / 1_000_000, if (dec > 50) tokens * 1000.0 / dec else 0.0,
            (charsInContext * 100 / (ctxTokens * CHARS_PER_TOKEN)).toInt(), backendLabel, mode == "texte" && skills.callsThisTurn.get() == 0)
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

    /** Libère le modèle (mémoire) sans arrêter le moteur : un load() le recharge ensuite. [done] sur le fil principal. */
    fun unload(done: () -> Unit) {
        stop()
        worker.execute { closeInternal(); System.gc(); post(done) }
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
        /** Part de la mémoire de travail qu'on s'autorise à remplir (l'estimation caractères→jetons est approximative). */
        private const val USABLE = 0.78
        /** Place gardée pour la réponse (≈ 350 jetons). */
        private const val ANSWER_RESERVE = 1300
        /** Au-delà de ce volume d'extraits, la conversation est allégée après la réponse. */
        private const val LIGHTEN_MIN = 500
        /** Derniers messages repris mot pour mot ; les précédents sont résumés. */
        private const val FULL_MESSAGES = 6
        private const val RECAP_MESSAGES = 16
        private const val RECAP_CHARS = 1100
        /** Estimation de la place prise par la description des outils dans le contexte. */
        private const val SKILLS_PROMPT_CHARS = 2400
        /** Jetons audio par seconde (estimation prudente pour la gestion du contexte). */
        private const val AUDIO_TOKENS_PER_S = 12

        /**

         * Consigne jointe à chaque photo : la règle « une phrase ou deux » de la personnalité ne doit pas

         * réduire une description à « je vois un ours en peluche ».

         */

        private const val PHOTO_INSTRUCTION = "Regarde la photo attentivement avant de répondre. Pour une description, sois précis et concret, " +

            "en quatre à six phrases naturelles : le sujet principal, sa forme, ses couleurs et ses matières, tout texte, marque ou étiquette lisible " +

            "(recopie-le), son état (neuf, usé, taché…), puis le fond et le contexte. Si la question porte sur un détail, réponds sur ce détail. " +

            "Ne parle que de ce qui est visible ; si quelque chose est incertain, dis-le."

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

/**
 * Rattrape un appel d'outil que le modèle écrit en texte au lieu de l'appeler
 * (ex. « calculer{expression:<|"|>7 * 8<|"|>} » ou « calculer(expression: "7 * 8") ») : rien n'est affiché,
 * l'outil est exécuté à la fin.
 */
class LeakedCallFilter(private val firstParam: Map<String, String?>) {
    private val names = firstParam.keys
    private val head = StringBuilder()
    private var decided = false
    private var leaking = false
    var call: Pair<String, org.json.JSONObject>? = null; private set

    fun feed(chunk: String): String {
        if (decided && !leaking) return chunk
        head.append(chunk)
        if (leaking) return ""
        val h = head.toString().trimStart().removePrefix("call:")
        val name = h.takeWhile { it.isLetter() || it == '_' }
        val stillPossible = name.length == h.length && names.any { it.startsWith(name) }
        return when {
            name.length < h.length && (h[name.length] == '{' || h[name.length] == '(') && name in names -> { leaking = true; decided = true; "" }
            stillPossible && h.length < 24 -> ""
            else -> { decided = true; head.toString().also { head.setLength(0) } }
        }
    }

    /** Fin du flux : texte retenu à afficher (null s'il n'y a rien), et [call] rempli si c'était un appel. */
    fun finish(): String? {
        if (!leaking) return if (decided || head.isEmpty()) null else head.toString().also { head.setLength(0) }
        val h = head.toString().trimStart().removePrefix("call:").replace("<|\"|>", "\"").replace("<tool_call|>", "")
        val open = h.indexOfFirst { it == '{' || it == '(' }
        val name = h.substring(0, open.coerceAtLeast(0))
        val close = if (open >= 0 && h[open] == '(') ')' else '}'
        val body = h.substring(open + 1).substringBeforeLast(close)
        val args = org.json.JSONObject()
        Regex("(\\w+)\\s*[:=]\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,})]+))").findAll(body).forEach { m ->
            val k = m.groupValues[1]
            val str = m.groups[2]?.value
            if (str != null) args.put(k, str) else {
                val raw = m.groupValues[3].trim()
                args.put(k, raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw.toBooleanStrictOrNull() ?: raw)
            }
        }
        // « calculer(7 * 8) » : une seule valeur sans nom → premier paramètre de l'outil
        val bare = body.trim().trim('"', '\'')
        if (args.length() == 0 && bare.isNotEmpty()) firstParam[name]?.let { args.put(it, bare) }
        call = name to args
        return null
    }
}

/**
 * Isole la ligne « >> relance 1 | relance 2 » que Nox écrit en fin de réponse : elle n'est ni affichée
 * dans le texte ni lue à voix haute, mais transformée en boutons de relance.
 */
class SuggestFilter {
    private val hold = StringBuilder()
    private val tail = StringBuilder()
    private var inTail = false
    private var atLineStart = true
    private var prevSpace = false

    fun feed(chunk: String): String {
        if (inTail) { tail.append(chunk); return "" }
        val out = StringBuilder()
        for (ch in chunk) {
            if (inTail) { tail.append(ch); continue }
            if (hold.isNotEmpty() || ((atLineStart || prevSpace) && ch == '>')) {
                hold.append(ch)
                val h = hold.toString()
                if (h.trimStart().startsWith(">>")) { inTail = true; tail.append(h.trimStart().removePrefix(">>")); hold.setLength(0); continue }
                if (h.trimStart() == ">") continue
                out.append(h); hold.setLength(0)
                atLineStart = ch == '\n'; prevSpace = ch == ' '
                continue
            }
            out.append(ch)
            atLineStart = ch == '\n'; prevSpace = ch == ' '
        }
        return out.toString()
    }

    /** Texte retenu en attente qui n'était finalement pas une relance. */
    fun flushVisible(): String = if (inTail) "" else hold.toString().also { hold.setLength(0) }

    fun suggestions(): List<String> {
        if (!inTail) return emptyList()
        return tail.toString().lines().first().split('|')
            .map { it.trim().trim('"', '«', '»', '*', '-', ' ').trim() }
            .filter { it.length in 2..60 }
            .take(3)
    }
}
