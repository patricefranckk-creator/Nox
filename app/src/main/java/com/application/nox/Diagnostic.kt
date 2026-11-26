package com.application.nox

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.pillButton
import com.application.nox.Ui.text
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Diagnostic complet : une série d'auto-tests (moteur, génération, bibliothèque, RAG, web, voix,
 * pilotage, autorisations…) puis un rapport texte très détaillé, enregistré dans
 * Téléchargements/Nox et partageable en un geste.
 */
class Diagnostic(private val a: MainActivity) {

    enum class Status(val icon: String, val color: Int) {
        OK("✅", 0xFF5FE3B0.toInt()), WARN("⚠️", 0xFFFFC266.toInt()), FAIL("❌", 0xFFFF6B81.toInt()), INFO("ℹ️", Ui.CYAN)
    }

    data class Result(val name: String, val status: Status, val summary: String, val details: List<String>, val ms: Long)

    private class Check(val name: String) {
        var status = Status.OK
        var summary = ""
        val details = ArrayList<String>()
        fun line(s: String) { details += s }
        fun warn(s: String) { if (status != Status.FAIL) status = Status.WARN; details += "⚠ $s" }
        fun fail(s: String) { status = Status.FAIL; details += "✖ $s" }
    }

    private val main = Handler(Looper.getMainLooper())
    private val results = ArrayList<Result>()
    private val started = System.currentTimeMillis()
    @Volatile private var cancelled = false
    private var savedUri: Uri? = null
    private var savedName = ""
    private var report = ""

    private lateinit var progress: TextView
    private lateinit var list: TextView
    private lateinit var scroll: ScrollView
    private lateinit var actions: LinearLayout
    private var dialog: NoxDialog? = null

    fun show() {
        if (running) { a.toast("Un diagnostic est déjà en cours"); return }
        running = true
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(4), a.dp(2), a.dp(4), 0) }
        body.addView(a.text("Nox se teste lui-même puis écrit un rapport complet dans Téléchargements/Nox. " +
            "Envoie-moi ce fichier : il contient tout ce qu'il faut pour perfectionner l'application.", 13.5f, Ui.MUTED))
        progress = a.text("Préparation…", 14f, Ui.CYAN, bold = true).apply { setPadding(0, a.dp(12), 0, a.dp(8)) }
        body.addView(progress)
        list = TextView(a).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(Ui.TEXT)
            setTextIsSelectable(true)
            setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
        }
        scroll = ScrollView(a).apply {
            background = Ui.rounded(Ui.CARD, a.dp(14).toFloat(), Ui.STROKE, a.dp(1))
            addView(list)
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.5).toInt()))
        actions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(12), 0, a.dp(4)); visibility = View.GONE }
        actions.addView(a.pillButton("Partager le rapport") { share() })
        actions.addView(a.pillButton("Copier", filled = false) {
            a.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Diagnostic Nox", report.take(900_000)))
            a.toast("Rapport copié")
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
        body.addView(actions)

        dialog = NoxDialog.sheet(a)
            .setTitle("Diagnostic complet")
            .setView(body)
            .setOnDismissListener { cancelled = true }
            .show()
        NoxLog.i("DIAG", "Diagnostic complet lancé")
        Thread({ runAll() }, "nox-diagnostic").start()
    }

    // ================================================================== déroulé

    private fun runAll() {
        val steps: List<Pair<String, (Check) -> Unit>> = listOf(
            "Appareil" to ::device,
            "Mémoire & température" to ::memory,
            "Stockage" to ::storage,
            "Modèles installés" to ::models,
            "Moteur Gemma" to ::engine,
            "Génération (test réel)" to ::generation,
            "Calculatrice" to ::math,
            "Aiguillage des messages" to ::router,
            "Commandes internet" to ::webCommands,
            "Bibliothèque" to ::library,
            "RAG : retrouve ses propres fiches" to ::ragRecall,
            "RAG : vraies questions" to ::ragQuestions,
            "RAG : bruit hors sujet" to ::ragNoise,
            "Réseau & recherche web" to ::web,
            "Voix de Nox (synthèse)" to ::tts,
            "Écoute (reconnaissance vocale)" to ::stt,
            "Pilotage des applis" to ::piloting,
            "Autorisations" to ::permissions,
            "Réglages" to ::settings,
            "Conversations & performances" to ::conversations,
            "Fermetures précédentes" to ::exits,
            "Erreurs récentes" to ::errors,
        )
        steps.forEachIndexed { i, (name, fn) ->
            if (cancelled) { running = false; return }
            ui { progress.text = "Test ${i + 1}/${steps.size} · $name…" }
            val c = Check(name)
            val clock = NoxLog.chrono()
            try { fn(c) } catch (t: Throwable) { c.fail("Exception : ${t.javaClass.simpleName} : ${t.message}"); c.line(NoxLog.stack(t)) }
            val r = Result(name, c.status, c.summary, c.details, clock.ms())
            synchronized(results) { results += r }
            NoxLog.log(if (r.status == Status.FAIL) NoxLog.Lvl.E else if (r.status == Status.WARN) NoxLog.Lvl.W else NoxLog.Lvl.I,
                "DIAG", "${r.status.icon} $name · ${r.summary} (${r.ms} ms)")
            ui { render() }
        }
        report = buildReport()
        save()
        running = false
        ui {
            val ok = results.count { it.status == Status.OK }
            val warn = results.count { it.status == Status.WARN }
            val fail = results.count { it.status == Status.FAIL }
            progress.text = "Terminé · ✅ $ok  ⚠️ $warn  ❌ $fail" + if (savedUri != null) "\nEnregistré : Téléchargements/Nox/$savedName" else "\nEnregistrement impossible : utilise Partager"
            actions.visibility = View.VISIBLE
        }
    }

    private fun render() {
        val sb = SpannableStringBuilder()
        synchronized(results) {
            for (r in results) {
                val start = sb.length
                sb.append("${r.status.icon} ${r.name}\n")
                sb.setSpan(ForegroundColorSpan(r.status.color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val s2 = sb.length
                sb.append("   ${r.summary} · ${r.ms} ms\n")
                sb.setSpan(ForegroundColorSpan(Ui.MUTED), s2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        list.text = sb
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun ui(block: () -> Unit) = main.post { if (!cancelled && dialog?.isShowing == true) block() }

    // ================================================================== tests

    private fun device(c: Check) {
        val am = a.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dm = a.resources.displayMetrics
        c.line("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})")
        c.line("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · correctif sécurité ${Build.VERSION.SECURITY_PATCH}")
        c.line("SoC : ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} · matériel ${Build.HARDWARE} · ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        c.line("Cœurs : ${Runtime.getRuntime().availableProcessors()} · fréquences max : ${cpuFreqs()}")
        c.line("RAM totale ${NoxLog.gb(mi.totalMem)} · libre ${NoxLog.gb(mi.availMem)} · seuil bas ${NoxLog.gb(mi.threshold)} · classe mémoire ${am.memoryClass} Mo (large ${am.largeMemoryClass} Mo)")
        c.line("Écran : ${dm.widthPixels}×${dm.heightPixels} px · densité ${dm.densityDpi} dpi · échelle texte ${a.resources.configuration.fontScale}")
        c.line("Langue ${Locale.getDefault()} · fuseau ${TimeZone.getDefault().id} · date ${Date()}")
        c.line("Nox ${AppInfo.version(a)} · build ${AppInfo.build(a)} · commit ${AppInfo.commit(a)} · compilé ${AppInfo.buildDate(a)} · ${AppInfo.ENGINE}")
        val bm = a.getSystemService(BatteryManager::class.java)
        val pm = a.getSystemService(PowerManager::class.java)
        c.line("Batterie ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} %${if (bm.isCharging) " (en charge)" else ""} · économie d'énergie ${oui(pm.isPowerSaveMode)} · " +
            "Nox exempté d'optimisation batterie ${oui(pm.isIgnoringBatteryOptimizations(a.packageName))}")
        if (!pm.isIgnoringBatteryOptimizations(a.packageName)) c.warn("Android peut endormir Nox en arrière-plan (optimisation batterie active)")
        if (pm.isPowerSaveMode) c.warn("Mode économie d'énergie : le moteur sera bridé")
        c.summary = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · RAM ${NoxLog.gb(mi.totalMem)}"
    }

    private fun memory(c: Check) {
        val rt = Runtime.getRuntime()
        val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        c.line(NoxLog.memSnapshot())
        c.line("Tas Java : ${NoxLog.gb(rt.totalMemory() - rt.freeMemory())} utilisés / max ${NoxLog.gb(rt.maxMemory())}")
        c.line("Mémoire réelle de Nox (PSS) : ${NoxLog.gb(pss.totalPss * 1024L)} · natif ${NoxLog.gb(pss.nativePss * 1024L)} · Java ${NoxLog.gb(pss.dalvikPss * 1024L)}")
        pss.memoryStats.forEach { (k, v) -> c.line("  $k = $v Ko") }
        val pm = a.getSystemService(PowerManager::class.java)
        c.line("Marge thermique (0 froid → 1 bridage) : ${pm.getThermalHeadroom(10)}")
        val mi = ActivityManager.MemoryInfo().also { a.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (mi.lowMemory) c.warn("Android signale une mémoire basse")
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) c.warn("Téléphone très chaud : vitesse bridée")
        c.summary = "Nox occupe ${NoxLog.gb(pss.totalPss * 1024L)} · libre ${NoxLog.gb(mi.availMem)}"
    }

    private fun storage(c: Check) {
        val dir = Nox.store.dir()
        val st = StatFs(dir.path)
        val free = st.availableBytes
        c.line("Dossier des modèles : ${dir.path}")
        c.line("Libre : ${NoxLog.gb(free)} sur ${NoxLog.gb(st.totalBytes)}")
        dir.listFiles()?.sortedByDescending { it.length() }?.forEach { c.line("  ${it.name} · ${NoxLog.gb(it.length())}") }
        val db = a.getDatabasePath("x").parentFile
        db?.listFiles()?.forEach { c.line("  base ${it.name} · ${NoxLog.gb(it.length())}") }
        c.line("Cache : ${NoxLog.gb(sizeOf(a.cacheDir))} · fichiers internes : ${NoxLog.gb(sizeOf(a.filesDir))}")
        if (free < 2_000_000_000L) c.warn("Moins de 2 Go libres : un téléchargement de modèle peut échouer")
        c.summary = "${NoxLog.gb(free)} libres"
    }

    private fun models(c: Check) {
        val store = Nox.store
        var installed = 0
        for (s in store.catalog) {
            val f = store.fileFor(s)
            val ok = store.isInstalled(s)
            if (ok) installed++
            c.line("${if (ok) "●" else "○"} ${s.title} · ${s.file} · ${if (f.exists()) NoxLog.gb(f.length()) else "absent"} (attendu ≥ ${NoxLog.gb(s.minBytes)})" +
                (if (s.cpuOnly) " · CPU seul" else "") + " · contexte max ${s.maxCtx}")
            if (f.exists() && !ok) c.warn("${s.file} incomplet (${NoxLog.gb(f.length())}) : téléchargement interrompu ?")
        }
        val e = store.embedFile()
        c.line("Embedding : ${store.embedSpec.file} · ${if (e != null) "installé (${NoxLog.gb(e.length())})" else "absent"}")
        c.line("Modèle choisi : ${Nox.prefs.modelPath.ifBlank { "(aucun)" }}")
        if (installed == 0) c.fail("Aucun modèle Gemma installé")
        if (e == null) c.warn("EmbeddingGemma absent : la bibliothèque cherche seulement par mots-clés et ne lit pas les vecteurs Freekipedia")
        c.summary = "$installed modèle(s) Gemma · embedding ${if (e != null) "présent" else "absent"}"
    }

    private fun engine(c: Check) {
        val en = Nox.engine
        var waited = 0
        while (!en.ready && Nox.loading && waited < 300 && !cancelled) {
            ui { progress.text = "Attente de la fin du chargement du modèle… ${waited} s" }
            Thread.sleep(1000); waited++
        }
        if (waited > 0) c.line("Attente du chargement : $waited s")
        c.line("Prêt : ${oui(en.ready)} · occupé : ${oui(en.busy)} · chargement en cours : ${oui(Nox.loading)}")
        c.line("Modèle : ${en.modelLabel.ifBlank { "—" }} · moteur : ${en.backendLabel.ifBlank { "—" }}")
        c.line("Audio natif : ${oui(en.audioReady)} · vision : ${oui(en.visionReady)} · outils actifs : ${oui(en.skillsActive)}")
        c.line("Contexte rempli : ${en.contextPct()} % · réglage ${Nox.prefs.contextTokens} jetons · backend demandé ${Nox.prefs.backend} · turbo ${oui(Nox.prefs.turbo)}")
        c.line("Configurations GPU écartées (plantage du pilote) : ${Nox.prefs.gpuBad.joinToString().ifBlank { "aucune" }}")
        if (Nox.prefs.backend == "gpu" && en.backendLabel.startsWith("CPU")) c.warn("GPU choisi mais le moteur tourne en CPU")
        if (!en.ready) c.fail("Le modèle n'est pas chargé : les tests de génération seront sautés")
        else if (en.backendLabel.contains("CPU", true)) c.warn("Le modèle tourne sur CPU : plus lent qu'en GPU")
        c.summary = if (en.ready) "${en.modelLabel} · ${en.backendLabel}" else "non chargé"
    }

    private fun generation(c: Check) {
        val en = Nox.engine
        if (!en.ready) { c.status = Status.INFO; c.summary = "sauté (modèle non chargé)"; return }
        if (en.busy) { c.status = Status.INFO; c.summary = "sauté (Nox est en train de répondre)"; return }
        val prompts = listOf(
            "Réponds en une seule phrase courte : quelle est la capitale de la France ?" to listOf("paris"),
            "Réponds uniquement par le résultat : combien font 7 fois 8 ?" to listOf("56"),
        )
        var passed = 0
        val tps = ArrayList<Double>()
        val ttfts = ArrayList<Long>()
        for ((q, expect) in prompts) {
            if (cancelled) return
            val latch = CountDownLatch(1)
            val sb = StringBuilder()
            var err: String? = null
            var statsLine = ""
            val t0 = System.nanoTime()
            var first = 0L
            main.post {
                en.send(q, emptyList(), object : LlmEngine.StreamListener {
                    override fun onChunk(text: String) { if (first == 0L) first = System.nanoTime(); sb.append(text) }
                    override fun onDone(stats: String) { statsLine = stats; latch.countDown() }
                    override fun onError(msg: String) { err = msg; latch.countDown() }
                }, context = "")
            }
            val done = latch.await(120, TimeUnit.SECONDS)
            val total = (System.nanoTime() - t0) / 1_000_000
            val ttft = if (first > 0) (first - t0) / 1_000_000 else -1
            val answer = sb.toString().trim()
            c.line("Q : $q")
            c.line("R : ${answer.take(300).ifBlank { "(vide)" }}")
            c.line("   1er mot ${ttft} ms · total ${total} ms · ${answer.length} caractères · $statsLine")
            NoxLog.lastTurn()?.let { t -> tps += t.tps; c.line("   mesure moteur : ${t.tokens} jetons · ${"%.1f".format(Locale.FRANCE, t.tps)} tok/s · contexte ${t.ctxPct} %") }
            if (ttft >= 0) ttfts += ttft
            when {
                !done -> { c.fail("Pas de réponse en 2 minutes"); main.post { en.stop() } }
                err != null -> c.fail("Erreur : $err")
                expect.any { Text.fold(answer).contains(it) } -> passed++
                else -> c.warn("Réponse inattendue (attendu : ${expect.joinToString()})")
            }
            if (answer.contains("<think", true) || answer.contains("```tool", true)) c.warn("Balises internes visibles dans la réponse")
        }
        c.line("Les deux questions de test restent dans le contexte du modèle (pas dans ton fil) : « Nouvelle conversation » repart de zéro.")
        val medTtft = ttfts.sorted().getOrNull(ttfts.size / 2) ?: -1
        if (medTtft > 6000) c.warn("Plus de 6 s avant le premier mot")
        val avgTps = if (tps.isEmpty()) 0.0 else tps.average()
        if (avgTps in 0.1..4.0) c.warn("Vitesse faible (< 4 tok/s)")
        c.summary = "$passed/${prompts.size} réponses justes · 1er mot ${medTtft} ms · ${"%.1f".format(Locale.FRANCE, avgTps)} tok/s"
    }

    private fun math(c: Check) {
        val cases = listOf("2+3*4" to 14.0, "(2+3)*4" to 20.0, "2^10" to 1024.0, "sqrt(16)" to 4.0, "20% de 150" to 30.0,
            "1,5*4" to 6.0, "10/4" to 2.5, "7×8" to 56.0, "100−1" to 99.0)
        var ok = 0
        for ((e, want) in cases) {
            val got = try { MathEval.eval(e) } catch (t: Throwable) { Double.NaN }
            val good = abs(got - want) < 1e-9
            if (good) ok++ else c.fail("« $e » donne ${MathEval.format(got)} au lieu de ${MathEval.format(want)}")
            c.line("${if (good) "✓" else "✗"} $e = ${MathEval.format(got)}")
        }
        c.summary = "$ok/${cases.size} calculs justes"
    }

    private fun router(c: Check) {
        val knowledge = listOf(
            "Qu'est-ce que la photosynthèse ?" to true, "Quelle est la capitale de l'Espagne ?" to true,
            "Comment fonctionne un moteur à combustion" to true, "Pourquoi le ciel est bleu ?" to true,
            "Ouvre WhatsApp" to false, "Bonjour Nox" to false, "Merci beaucoup" to false,
            "Mets un minuteur de 5 minutes" to false, "Appelle maman" to false,
        )
        val details = listOf("Plus de détails" to true, "Développe" to true, "Dis-m'en plus" to true, "La suite" to true,
            "Ouvre la météo" to false, "Quelle heure est-il ?" to false)
        var ok = 0; var n = 0
        for ((m, want) in knowledge) {
            n++; val got = Router.isKnowledgeQuestion(m)
            if (got == want) ok++ else c.warn("« $m » : question de connaissances = $got (attendu $want)")
            c.line("${if (got == want) "✓" else "✗"} connaissance « $m » → $got")
        }
        for ((m, want) in details) {
            n++; val got = Router.isMoreDetails(m)
            if (got == want) ok++ else c.warn("« $m » : demande de détails = $got (attendu $want)")
            c.line("${if (got == want) "✓" else "✗"} détails « $m » → $got")
        }
        val clock = listOf("Quelle heure est-il ?" to true, "Cherche sur le net l'heure" to true, "On est quel jour ?" to true,
            "À quelle heure ferme la piscine ?" to false, "L'heure de la messe" to false)
        for ((m, want) in clock) {
            n++; val got = Router.timeAnswer(m) != null
            if (got == want) ok++ else c.warn("« $m » : horloge = $got (attendu $want)")
            c.line("${if (got == want) "✓" else "✗"} horloge « $m » → $got")
        }
        val cmds = listOf("Mets un minuteur de 5 minutes" to "horloge", "Rappelle-moi dans 20 minutes de sortir le linge" to "rappel",
            "Allume la lampe" to "telephone", "Ajoute du lait à la liste de courses" to "notes", "Ouvre WhatsApp" to "ouvrir",
            "Ouvre WhatsApp et écris à Paul" to null, "Quelle est la capitale de la France ?" to null)
        for ((m, want) in cmds) {
            n++; val got = Commands.match(m)?.skill
            if (got == want) ok++ else c.warn("« $m » : commande directe = $got (attendu $want)")
            c.line("${if (got == want) "✓" else "✗"} commande « $m » → ${got ?: "modèle"}")
        }
        val specials = listOf("Bonjour Nox" to (if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 12) Intents.BRIEFING else null),
            "Le point du matin" to Intents.BRIEFING, "Résume mon écran" to Intents.SCREEN_SUMMARY, "Propose-moi une réponse" to Intents.SCREEN_REPLY,
            "Mode interprète anglais" to Intents.INTERPRETER, "Note : acheter des piles" to Intents.NOTE,
            "Qu'est-ce que j'ai noté sur les piles ?" to Intents.NOTES_FIND, "Retiens cette page" to Intents.KEEP_PAGE, "Raconte une blague" to null)
        for ((m, want) in specials) {
            n++; val got = Intents.detect(m, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))?.kind
            if (got == want) ok++ else c.warn("« $m » : phrase spéciale = $got (attendu $want)")
            c.line("${if (got == want) "✓" else "✗"} spéciale « $m » → ${got ?: "aucune"}")
        }
        c.line("Point du matin : ville ${Nox.prefs.briefCity} · météo ${oui(Nox.prefs.briefWeather)} · agenda ${oui(Nox.prefs.briefAgenda)} · rappels ${oui(Nox.prefs.briefReminders)} (${ReminderReceiver.upcoming().size} à venir) · courses ${oui(Nox.prefs.briefList)} · actus ${oui(Nox.prefs.briefNews)}")
        c.line("Apparence de la page vocale : ${when (Nox.prefs.look) { "holo" -> "hologramme"; "portrait" -> "portrait animé"; "avatar3d" -> "avatar 3D"; "visage" -> "visage 2,5D"; else -> "médaillon" }} · hologramme : ${HoloAssets.describe(Nox.app)} · portrait : ${if (PortraitAssets.ready(Nox.app)) PortraitAssets.names(Nox.app).joinToString(", ").ifEmpty { "base seule" } else "non importé"} · émotion test « Bonne nouvelle ! » → ${Emotion.of("Bonne nouvelle !")}")
        c.line("Banc d'essai : ${Bench.last(Nox.prefs)} · relais en ligne ${oui(Nox.prefs.cloudRelay)} · souvenirs ${Nox.skills.memory.all().size}")
        c.line("Écran lisible (accessibilité) : ${oui(NoxAccess.instance != null)} · notes vocales : ${Nox.library.sources().count { it.title.startsWith(MainActivity.NOTE_TITLE) }}")
        c.line("Observation : « Tu peux me dire c'est quoi un trou noir ? » → ${Router.isKnowledgeQuestion("Tu peux me dire c'est quoi un trou noir ?")}")
        c.line("Observation : « C'est quoi l'autisme ? » → ${Router.isKnowledgeQuestion("C'est quoi l'autisme ?")}")
        c.line("Réglages : bibliothèque d'abord ${oui(Nox.prefs.ragFirst)} · recherche web ${oui(Nox.prefs.webSearch)} · RAG auto ${oui(Nox.prefs.ragAuto)}")
        c.summary = "$ok/$n aiguillages corrects"
    }

    private fun webCommands(c: Check) {
        val cases = listOf(
            "Cherche sur internet la météo à Pau" to true, "Recherche sur le net les horaires de la piscine" to true,
            "Nox, cherche sur la toile qui a gagné le Tour de France" to true, "Regarde sur Wikipédia la tour Eiffel" to true,
            "Quelle heure est-il ?" to false, "Cherche dans mes notes la liste de courses" to false, "Ouvre Chrome" to false,
        )
        var ok = 0
        for ((m, want) in cases) {
            val q = WebSearch.queryOf(m)
            val good = (q != null) == want
            if (good) ok++ else c.warn("« $m » → ${q ?: "pas de recherche"} (attendu : ${if (want) "recherche" else "pas de recherche"})")
            c.line("${if (good) "✓" else "✗"} « $m » → ${q?.let { "« $it »" } ?: "—"}")
        }
        c.summary = "$ok/${cases.size} commandes reconnues"
    }

    private fun library(c: Check) {
        val lib = Nox.library
        c.line(lib.stats())
        c.line("Recherche sémantique : ${oui(lib.semantic)} · dimension des vecteurs : ${lib.vectorSize()}")
        lib.kindCounts().forEach { (k, n) -> c.line("  ${k.emoji} ${k.label} : $n source(s)") }
        val srcs = lib.sources()
        c.line("${srcs.size} sources au total")
        srcs.sortedByDescending { it.time }.take(40).forEach { s ->
            c.line("  · [${s.kind.label}] ${s.title.take(70)} · ${s.extracts} extraits · ${s.vectorized} vecteurs · ${s.chars} car.")
        }
        val noVec = srcs.filter { it.vectorized < it.extracts && it.kind != Library.Kind.CHAT }
        if (lib.semantic && noVec.isNotEmpty()) c.warn("${noVec.size} source(s) partiellement sans vecteur")
        val queries = listOf("histoire de France", "recette de cuisine", "autisme", "Pau", "énergie solaire")
        val times = ArrayList<Long>()
        for (q in queries) {
            val clock = NoxLog.chrono()
            val hits = lib.search(q, k = 3)
            val ms = clock.ms(); times += ms
            c.line("Recherche « $q » : ${hits.size} résultat(s) en $ms ms" + hits.joinToString("") {
                "\n     → ${it.chunk.title.take(60)} · score ${"%.3f".format(Locale.ROOT, it.score)} · cos ${"%.3f".format(Locale.ROOT, it.cos)} · bm25 ${"%.2f".format(Locale.ROOT, it.bm25)} · ${it.why}"
            })
        }
        val avg = times.average().toLong()
        if (avg > 800) c.warn("Recherche lente (moyenne $avg ms)")
        if (srcs.isEmpty()) c.warn("Bibliothèque vide")
        c.summary = "${srcs.size} sources · ${if (lib.semantic) "sémantique" else "mots-clés"} · recherche moyenne $avg ms"
    }

    private fun ragRecall(c: Check) {
        val lib = Nox.library
        val pool = lib.sources().filter { it.kind != Library.Kind.CHAT && it.title.length >= 4 }
        if (pool.isEmpty()) { c.status = Status.INFO; c.summary = "sauté (rien à tester)"; return }
        val sample = pool.shuffled(java.util.Random(42)).take(15)
        var top1 = 0; var top3 = 0; var direct = 0
        val times = ArrayList<Long>()
        for (s in sample) {
            val q = s.title.substringBefore(" · ").trim()
            val clock = NoxLog.chrono()
            val hits = lib.search(q, k = 3)
            times += clock.ms()
            val rank = hits.indexOfFirst { it.chunk.source == s.source }
            if (rank == 0) top1++
            if (rank in 0..2) top3++
            if (hits.firstOrNull()?.let { Router.confident(it) } == true) direct++
            c.line("${if (rank in 0..2) "✓" else "✗"} « ${q.take(50)} » → rang ${if (rank < 0) "absent" else (rank + 1).toString()}" +
                (hits.firstOrNull()?.let { " · 1er : ${it.chunk.title.take(40)} (cos ${"%.2f".format(Locale.ROOT, it.cos)}, bm25 ${"%.1f".format(Locale.ROOT, it.bm25)})" } ?: ""))
        }
        val n = sample.size
        c.line("Réponses directes possibles (sans le modèle) : $direct/$n")
        if (top3 * 100 / n < 70) c.warn("Rappel faible : seulement $top3/$n fiches retrouvées par leur titre")
        c.summary = "rang 1 : $top1/$n · top 3 : $top3/$n · ${times.average().toLong()} ms en moyenne"
    }

    private fun ragQuestions(c: Check) {
        val lib = Nox.library
        val titles = lib.sources().map { Text.fold(it.title) }
        val cases = listOf(
            "C'est quoi un trou noir ?" to "trou noir", "Comment fonctionne la photosynthèse ?" to "photosynthese",
            "Qu'est-ce que le Big Bang ?" to "big bang", "Que faire en cas d'hypothermie ?" to "hypothermie",
            "Comment soigner une brûlure ?" to "brulure", "Comment mettre quelqu'un en position latérale de sécurité ?" to "position laterale",
            "Qu'est-ce que la relativité générale ?" to "relativite generale", "Comment arrêter une hémorragie ?" to "hemorragie",
            "Comment rendre l'eau potable ?" to "eau potable", "C'est quoi l'ADN ?" to "desoxyribonucleique",
        ).filter { (_, key) -> titles.any { it.contains(key) } }
        if (cases.isEmpty()) { c.status = Status.INFO; c.summary = "sauté (fiches de test absentes)"; return }
        var found = 0; var injected = 0; var direct = 0
        val goodCos = ArrayList<Double>()
        for ((q, key) in cases) {
            val all = lib.search(q, k = 5)
            val strict = lib.search(q, k = 3, strict = true)
            val rank = all.indexOfFirst { Text.fold(it.chunk.title).contains(key) }
            if (rank in 0..2) found++
            val strictOk = strict.firstOrNull()?.let { Text.fold(it.chunk.title).contains(key) } == true
            if (strictOk) injected++
            val d = Router.isKnowledgeQuestion(q) && strict.firstOrNull()?.let { Router.confident(it) && Text.fold(it.chunk.title).contains(key) } == true
            if (d) direct++
            all.getOrNull(rank)?.let { goodCos += it.cos }
            c.line("${if (rank in 0..2) "✓" else "✗"} « $q » → rang ${if (rank < 0) "absent" else rank + 1} · injecté ${oui(strictOk)} · direct ${oui(d)}" +
                all.take(3).joinToString("") { "\n     · ${it.chunk.title.take(45)} cos ${"%.3f".format(Locale.ROOT, it.cos)} bm25 ${"%.1f".format(Locale.ROOT, it.bm25)}" })
        }
        val n = cases.size
        if (goodCos.isNotEmpty()) c.line("Cosinus des bonnes fiches : min ${"%.3f".format(Locale.ROOT, goodCos.min())} · moyenne ${"%.3f".format(Locale.ROOT, goodCos.average())}")
        c.line("Seuils : injection cos ≥ ${Library.COS_STRONG} · réponse directe cos ≥ ${Router.COS_DIRECT}")
        if (found * 100 / n < 70) c.warn("Les bonnes fiches ne remontent pas assez souvent ($found/$n)")
        if (injected * 100 / n < 50) c.warn("Seuil d'injection trop haut : $injected/$n bonnes fiches injectées")
        c.summary = "trouvées $found/$n · injectées $injected/$n · réponses directes $direct/$n"
    }

    private fun ragNoise(c: Check) {
        val lib = Nox.library
        val queries = listOf("recette de la pâte à crêpes", "résultats du match de rugby", "histoire de France", "la ville de Pau",
            "l'autisme chez l'enfant", "programmer en Kotlin", "le prix de l'essence", "les impôts sur le revenu")
        var leaks = 0
        val noise = ArrayList<Double>()
        for (q in queries) {
            val all = lib.search(q, k = 3)
            val strict = lib.search(q, k = 3, strict = true)
            all.firstOrNull()?.let { noise += it.cos }
            if (strict.isNotEmpty()) leaks++
            c.line("${if (strict.isEmpty()) "✓" else "✗"} « $q » → injecté : ${strict.joinToString { it.chunk.title.take(35) }.ifBlank { "rien" }}" +
                (all.firstOrNull()?.let { " · meilleur cos ${"%.3f".format(Locale.ROOT, it.cos)} (${it.chunk.title.take(35)})" } ?: ""))
        }
        if (noise.isNotEmpty()) c.line("Plancher de bruit : cos max ${"%.3f".format(Locale.ROOT, noise.max())} · moyen ${"%.3f".format(Locale.ROOT, noise.average())}")
        if (leaks > 0) c.warn("$leaks question(s) hors sujet reçoivent quand même des extraits")
        c.summary = "${queries.size - leaks}/${queries.size} sans extrait parasite"
    }

    private fun web(c: Check) {
        val cm = a.getSystemService(ConnectivityManager::class.java)
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (t: SecurityException) { c.warn("État du réseau illisible : ${t.message}"); null }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val kind = when {
            caps == null -> "aucun"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "données mobiles"
            else -> "autre"
        }
        c.line("Réseau : $kind · internet validé ${oui(online)} · débit annoncé ${caps?.linkDownstreamBandwidthKbps ?: 0} kb/s")
        if (caps != null && !online) { c.status = Status.WARN; c.line("⚠ Pas d'internet : test de recherche sauté"); c.summary = "hors ligne"; return }
        val clock = NoxLog.chrono()
        val res = WebSearch.search("Pau Pyrénées-Atlantiques", pages = 1)
        val ms = clock.ms()
        res.take(6).forEach { c.line("  · ${it.title.take(70)} — ${it.domain} · page lue ${it.text.length} car.") }
        if (res.isEmpty()) c.fail("La recherche web ne renvoie rien (DuckDuckGo et Wikipédia)")
        else if (res.none { it.text.isNotBlank() }) c.warn("Résultats trouvés mais aucune page lue")
        if (ms > 12_000) c.warn("Recherche web lente ($ms ms)")
        c.summary = "$kind · ${res.size} résultats en $ms ms"
    }

    private fun tts(c: Check) {
        val latch = CountDownLatch(1)
        var tts: TextToSpeech? = null
        var status = -99
        main.post { tts = TextToSpeech(a.applicationContext) { s -> status = s; latch.countDown() } }
        if (!latch.await(10, TimeUnit.SECONDS)) { c.fail("Le moteur de synthèse ne répond pas"); c.summary = "pas de réponse"; return }
        val t = tts ?: return
        try {
            if (status != TextToSpeech.SUCCESS) { c.fail("Synthèse vocale indisponible (code $status)"); c.summary = "indisponible"; return }
            c.line("Moteur par défaut : ${t.defaultEngine} · moteurs : ${t.engines.joinToString { it.name }}")
            val fr = t.voices.orEmpty().filter { it.locale.language == "fr" }
            val offline = fr.filter { !it.isNetworkConnectionRequired }
            fr.sortedBy { it.name }.forEach { v ->
                c.line("  · ${v.name} · ${v.locale} · qualité ${v.quality} · latence ${v.latency}${if (v.isNetworkConnectionRequired) " · réseau" else " · locale"}")
            }
            c.line("Voix choisie : ${Nox.prefs.voiceName.ifBlank { "(par défaut)" }} · débit ${Nox.prefs.speechRate} · hauteur ${Nox.prefs.pitch}")
            c.line("Français disponible : ${t.isLanguageAvailable(Locale.FRANCE)}")
            if (offline.isEmpty()) c.warn("Aucune voix française hors ligne")
            if (Nox.prefs.voiceName.isNotBlank() && fr.none { it.name == Nox.prefs.voiceName }) c.warn("La voix choisie n'existe plus")
            c.summary = "${fr.size} voix françaises (${offline.size} hors ligne)"
        } finally {
            main.post { try { t.shutdown() } catch (_: Throwable) {} }
        }
    }

    private fun stt(c: Check) {
        val any = SpeechRecognizer.isRecognitionAvailable(a)
        val local = SpeechRecognizer.isOnDeviceRecognitionAvailable(a)
        val mic = a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        c.line("Reconnaissance disponible : ${oui(any)} · sur l'appareil : ${oui(local)} · micro autorisé : ${oui(mic)}")
        c.line("Mode d'écoute : ${Nox.prefs.listenMode} · patience ${Nox.prefs.patienceMs} ms · écoute max ${Nox.prefs.maxListenSec} s · mains libres ${oui(Nox.prefs.handsFree)}")
        c.line("Écoute native par Gemma (audio) : ${oui(Nox.engine.audioReady)}")
        if (!mic) c.fail("Micro non autorisé")
        if (!local) c.warn("Pas de reconnaissance 100 % locale : installer le pack français hors ligne")
        if (!any && !local) c.fail("Aucune reconnaissance vocale")
        c.summary = "locale ${oui(local)} · micro ${oui(mic)}"
    }

    private fun piloting(c: Check) {
        val acc = NoxAccess.instance != null
        val notif = a.getSystemService(android.app.NotificationManager::class.java)
            .isNotificationListenerAccessGranted(android.content.ComponentName(a, NoxNotifs::class.java))
        val assistant = try { a.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) } catch (_: Throwable) { false }
        c.line("Accessibilité (pilotage de l'écran) : ${oui(acc)}")
        c.line("Lecture des notifications (réponses WhatsApp…) : ${oui(notif)}")
        c.line("Nox est l'assistant par défaut (bouton power) : ${oui(assistant)}")
        c.line("Bulle flottante : ${oui(Nox.prefs.bubble)} · possible ${oui(Bubble.possible(a))} · micro en arrière-plan ${oui(NoxService.micReady)}")
        c.line("Confirmation des actions sensibles : ${oui(Nox.prefs.confirmSensitive)} · compétences ${oui(Nox.prefs.skillsEnabled)}")
        c.line("Outils disponibles : ${Nox.skills.all.joinToString { it.name }}")
        val apps = listOf("com.whatsapp" to "WhatsApp", "com.google.android.apps.maps" to "Maps", "com.deezer.android.app" to "Deezer",
            "com.spotify.music" to "Spotify", "com.google.android.youtube" to "YouTube", "com.android.chrome" to "Chrome")
        apps.forEach { (p, n) -> c.line("  $n installé : ${oui(installed(p))}") }
        if (!acc) c.warn("Accessibilité désactivée : Nox ne peut pas piloter les applis")
        if (!notif) c.warn("Accès aux notifications désactivé : pas de lecture ni de réponse aux messages")
        c.summary = "écran ${oui(acc)} · notifications ${oui(notif)}"
    }

    private fun permissions(c: Check) {
        val info: PackageInfo = a.packageManager.getPackageInfo(a.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions.orEmpty()
        var missing = 0
        perms.forEachIndexed { i, p ->
            // la superposition ne s'accorde pas comme les autres : elle se règle dans « Afficher par-dessus les autres applis »
            val granted = if (p == android.Manifest.permission.SYSTEM_ALERT_WINDOW) Settings.canDrawOverlays(a)
                else ((info.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            if (!granted) missing++
            c.line("${if (granted) "✓" else "✗"} ${p.removePrefix("android.permission.")}")
        }
        val alarms = a.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
        c.line("Alarmes exactes (rappels) : ${oui(alarms)}")
        c.line("Superposition : ${oui(Settings.canDrawOverlays(a))}")
        if (missing > 0) c.warn("$missing autorisation(s) non accordée(s)")
        c.summary = "${perms.size - missing}/${perms.size} accordées"
    }

    private fun settings(c: Check) {
        val all = a.getSharedPreferences("nox", Context.MODE_PRIVATE).all.toSortedMap()
        all.forEach { (k, v) -> c.line("$k = ${v.toString().replace("\n", " ⏎ ").take(240)}") }
        c.status = Status.INFO
        c.summary = "${all.size} réglages"
    }

    private fun conversations(c: Check) {
        val msgs = synchronized(Nox.messages) { Nox.messages.toList() }
        val user = msgs.count { it.fromUser }
        c.line("Conversation en cours : ${msgs.size} messages ($user de toi) · mémoire des conversations ${oui(Nox.prefs.rememberChats)}")
        c.line("Longueur moyenne des réponses : ${msgs.filter { !it.fromUser }.map { it.text.length }.average().takeIf { !it.isNaN() }?.toInt() ?: 0} caractères")
        c.line("Réponses avec sources : ${msgs.count { !it.fromUser && it.sources.isNotEmpty() }}")
        c.line("Performances : ${NoxLog.summary()}")
        NoxLog.lastTurn()?.let { c.line("Dernier tour : $it") }
        msgs.takeLast(12).forEach { m ->
            c.line("  ${if (m.fromUser) "Toi" else "Nox"} · ${SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(m.time))} · ${m.text.replace("\n", " ").take(160)}")
        }
        c.status = Status.INFO
        c.summary = "${msgs.size} messages · ${NoxLog.summary().substringBefore(" · ")}"
    }

    private fun exits(c: Check) {
        val am = a.getSystemService(ActivityManager::class.java)
        val list = am.getHistoricalProcessExitReasons(a.packageName, 0, 8)
        val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)
        var bad = 0
        list.forEach { e ->
            val why = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "plantage Java/Kotlin"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif (moteur)"
                android.app.ApplicationExitInfo.REASON_ANR -> "application figée (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "tuée par manque de mémoire"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "fermeture normale"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "fermée par l'utilisateur"
                android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "arrêt forcé"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "tuée par signal ${e.status}"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "ressources excessives"
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "autorisation modifiée"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dépendance arrêtée"
                else -> "raison ${e.reason}"
            }
            if (e.reason in listOf(android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                    android.app.ApplicationExitInfo.REASON_ANR, android.app.ApplicationExitInfo.REASON_LOW_MEMORY)) bad++
            c.line("${fmt.format(Date(e.timestamp))} · $why · PSS ${NoxLog.gb(e.pss * 1024L)} · RSS ${NoxLog.gb(e.rss * 1024L)}${e.description?.let { " · $it" } ?: ""}")
            if (e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                try {
                    e.traceInputStream?.use { s -> c.line("    trace :\n    " + readableTrace(s.readBytes()).joinToString("\n    ")) }
                } catch (_: Throwable) {}
            }
        }
        if (bad > 0) c.warn("$bad fermeture(s) anormale(s) récente(s)")
        if (list.isEmpty()) c.status = Status.INFO
        c.summary = "${list.size} fermetures connues · $bad anormale(s)"
    }

    private fun errors(c: Check) {
        val entries = NoxLog.entries()
        val errs = entries.filter { it.lvl == NoxLog.Lvl.E || it.cat == "CRASH" }
        val warns = entries.filter { it.lvl == NoxLog.Lvl.W }
        errs.takeLast(30).forEach { c.line("E ${it.line()}") }
        warns.takeLast(30).forEach { c.line("W ${it.line()}") }
        if (errs.isNotEmpty()) c.warn("${errs.size} erreur(s) dans le journal")
        c.summary = "${errs.size} erreurs · ${warns.size} avertissements · ${entries.size} lignes de journal"
    }

    // ================================================================== rapport

    private fun buildReport(): String {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
        val sb = StringBuilder()
        sb.append("══════════════ DIAGNOSTIC NOX ══════════════\n")
        sb.append("Nox ${AppInfo.version(a)} · build ${AppInfo.build(a)} · commit ${AppInfo.commit(a)}\n")
        sb.append("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Lancé le ${fmt.format(Date(started))} · durée ${(System.currentTimeMillis() - started) / 1000} s\n\n")
        sb.append("── RÉSUMÉ ──\n")
        synchronized(results) {
            results.forEach { sb.append("${r(it.status)} ${it.name.padEnd(36)} ${it.summary}  [${it.ms} ms]\n") }
            sb.append("\nTotal : ${results.count { it.status == Status.OK }} OK · ${results.count { it.status == Status.WARN }} à surveiller · " +
                "${results.count { it.status == Status.FAIL }} en échec\n\n")
            results.forEach { res ->
                sb.append("── ${res.name.uppercase(Locale.FRANCE)} · ${r(res.status)} ──\n")
                sb.append(res.summary).append('\n')
                res.details.forEach { sb.append("  ").append(it).append('\n') }
                sb.append('\n')
            }
        }
        val prompt = try { Nox.engine.promptPreview() } catch (t: Throwable) { "indisponible : ${t.message}" }
        sb.append("── CE QUE GEMMA REÇOIT (${prompt.length} caractères) ──\n").append(prompt).append("\n\n")
        sb.append("── JOURNAL COMPLET ──\n")
        sb.append(NoxLog.fullText().takeLast(700_000))
        sb.append("\n── FIN DU DIAGNOSTIC ──\n")
        return sb.toString()
    }

    private fun r(s: Status) = when (s) { Status.OK -> "[OK]  "; Status.WARN -> "[ATTN]"; Status.FAIL -> "[ÉCHEC]"; Status.INFO -> "[INFO]" }

    private fun save() {
        savedName = "nox_diagnostic_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())}.txt"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, savedName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nox")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("insertion refusée")
            a.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("écriture refusée")
            a.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            savedUri = uri
            NoxLog.i("DIAG", "Rapport enregistré : Téléchargements/Nox/$savedName (${report.length / 1024} Ko)")
        } catch (t: Throwable) {
            NoxLog.e("DIAG", "Enregistrement du rapport", t)
        }
    }

    private fun share() {
        val subject = "Diagnostic Nox ${AppInfo.version(a)} · ${Build.MODEL}"
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject)
        val uri = savedUri
        if (uri != null) {
            i.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.putExtra(Intent.EXTRA_TEXT, report.substringBefore("── JOURNAL COMPLET ──").take(60_000))
        } else {
            i.putExtra(Intent.EXTRA_TEXT, report.take(400_000))
        }
        a.startActivity(Intent.createChooser(i, "Envoyer le diagnostic"))
    }

    // ================================================================== outils

    companion object { @Volatile private var running = false }

    private fun oui(b: Boolean) = if (b) "oui" else "non"

    /** Tombstone natif (protobuf) ou trace ANR (texte) : on garde les chaînes lisibles utiles. */
    private fun readableTrace(bytes: ByteArray): List<String> {
        val runs = ArrayList<String>()
        val cur = StringBuilder()
        for (b in bytes) {
            val ch = b.toInt() and 0xFF
            if (ch in 32..126) cur.append(ch.toChar()) else { if (cur.length >= 6) runs += cur.toString(); cur.setLength(0) }
        }
        if (cur.length >= 6) runs += cur.toString()
        val useful = Regex("SIG[A-Z]+|SEGV|abort|Abort|\\.so|::|Exception|Error|assert|Check failed|null pointer|signal|Cause|thread|\"main\"|at ")
        return runs.filter { useful.containsMatchIn(it) }.distinct().take(60).map { it.take(220) }
    }

    private fun installed(pkg: String) = try { a.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    private fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { sizeOf(it) } ?: 0L

    private fun cpuFreqs(): String = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
        try { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 } catch (_: Exception) { null }
    }.groupingBy { it }.eachCount().entries.joinToString { "${it.value}×${it.key} MHz" }.ifBlank { "inconnues" }
}
