package com.application.nox

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.pillButton
import com.application.nox.Ui.text
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Banc d'essai automatique : le harnais (aiguillage, commandes, phrases spéciales, bibliothèque), puis Gemma
 * sur la culture, la logique, la langue, le choix des outils (simulés, rien n'est exécuté), la vue (images
 * dessinées par Nox) et la rédaction (document, exposé, synthèse, courriel, liste). Chaque passage est
 * enregistré dans Téléchargements/Nox/nox_banc_….txt avec les réponses complètes, les mesures et le journal.
 */
class Bench(private val a: MainActivity) {

    private class Check(val label: String, val ok: (String) -> Boolean)

    private class Case(
        val kind: String, val q: String, val checks: List<Check>,
        val rag: Boolean = false, val image: (() -> ByteArray)? = null, val tool: String? = null,
    )

    private class Result(
        val case: Case, val answer: String, val passed: Boolean, val skipped: Boolean, val failed: List<String>,
        val firstMs: Long, val totalMs: Long, val tools: List<String>,
    )

    // ---------------------------------------------------------------- vérifications

    private fun has(label: String, rx: String) = Check(label) { Regex(rx).containsMatchIn(fold(it)) }
    private fun minLen(n: Int) = Check("au moins $n caractères") { it.trim().length >= n }
    private fun maxLen(n: Int) = Check("au plus $n caractères") { it.trim().length <= n }
    private fun bullets(min: Int, max: Int = 99) = Check(if (min == max) "exactement $min points" else "au moins $min éléments de liste") {
        val n = it.lines().count { l -> Regex("^\\s*(?:[-•*▪●]|\\d{1,2}[.)])\\s+\\S").containsMatchIn(l) }
        n in min..max
    }
    private fun paragraphs(min: Int) = Check("au moins $min paragraphes") { it.split(Regex("\\n\\s*\\n|\\n(?=#)")).count { p -> p.trim().length > 40 } >= min }
    private fun sections(min: Int) = Check("au moins $min intertitres") {
        it.lines().count { l -> val t = l.trim(); t.startsWith("#") || (t.startsWith("**") && t.length < 90) || (t.endsWith(":") && t.length in 4..70) } >= min
    }
    private fun complete() = Check("réponse terminée (pas coupée)") { it.trim().let { t -> t.isNotEmpty() && (t.last() in ".!?»)…*" || t.lines().last().trim().let { l -> Regex("^\\s*(?:[-•*]|\\d+[.)])").containsMatchIn(l) || l.endsWith(":") }) } }
    private fun noLeak() = Check("pas de code d'outil affiché") { !Regex("<\\|\"\\|>|^\\s*\\w+\\{\\w+:").containsMatchIn(it) }

    private fun fold(s: String) = Text.fold(s.lowercase().replace('’', '\''))

    // ---------------------------------------------------------------- questions au modèle

    private val sleepText = "Le sommeil joue un rôle essentiel dans la santé. Pendant la nuit, le cerveau trie et consolide les souvenirs de la journée, " +
        "ce qui aide à apprendre. Le corps en profite pour réparer les muscles et renforcer les défenses immunitaires. Un adulte a besoin " +
        "de sept à neuf heures de sommeil, un enfant davantage. Les écrans le soir retardent l'endormissement, car leur lumière bleue " +
        "freine la production de mélatonine. Se coucher à heure régulière, éviter la caféine l'après-midi et garder une chambre fraîche " +
        "et sombre améliorent nettement la qualité des nuits."

    private val cases = listOf(
        Case("culture", "Quelle est la capitale de l'Italie ?", listOf(has("cite Rome", "rome"))),
        Case("culture", "Qui a écrit Les Misérables ?", listOf(has("cite Hugo", "hugo"))),
        Case("culture", "En quelle année a eu lieu la prise de la Bastille ?", listOf(has("1789", "1789"))),
        Case("culture", "Quel est le plus grand océan du monde ?", listOf(has("Pacifique", "pacifique"))),
        Case("culture", "Quelle planète est la plus proche du Soleil ?", listOf(has("Mercure", "mercure"))),
        Case("culture", "Quelle est la langue officielle du Brésil ?", listOf(has("portugais", "portugais"))),
        Case("logique", "Combien de pattes a une araignée ?", listOf(has("8", "\\b8\\b|huit"))),
        Case("logique", "J'ai 3 pommes, j'en mange une. Combien m'en reste-t-il ?", listOf(has("2", "\\b2\\b|deux"))),
        Case("logique", "Quel jour vient après mardi ?", listOf(has("mercredi", "mercredi"))),
        Case("logique", "Combien y a-t-il de minutes dans 2 heures ?", listOf(has("120", "120|cent vingt"))),
        Case("logique", "Paul est plus grand que Marc, Marc est plus grand que Léo. Qui est le plus petit ?", listOf(has("Léo", "\\bleo\\b"))),
        Case("langue", "Traduis « merci beaucoup » en anglais.", listOf(has("thank", "thank"))),
        Case("langue", "Quel est le contraire de « grand » ?", listOf(has("petit", "petit"))),
        Case("langue", "Donne un synonyme de « rapide ».", listOf(has("synonyme plausible", "vite|veloce|prompt|leste|vif|express|preste|expeditif"))),
        Case("langue", "Reformule plus simplement : « Nonobstant les intempéries, la manifestation sera maintenue. »",
            listOf(has("« malgré » ou équivalent", "malgre|meme s|en depit|quand meme"), has("maintien", "maintenu|aura lieu|a lieu|continue|garde|se fera|se tiendra"))),

        Case("outil", "Combien font 37 fois 43 ?", listOf(has("1591", "1\\s?591"), noLeak()), tool = "calculer"),
        Case("outil", "Quelle heure est-il ?", listOf(has("une heure", "\\d{1,2}\\s?(?:h|:|heures?)\\s?\\d{0,2}"), noLeak()), tool = "heure_date"),
        Case("outil", "Envoie un SMS à Paul pour lui dire que j'arrive en retard.", listOf(noLeak()), tool = "contacter"),
        Case("outil", "Ajoute un rendez-vous chez le dentiste demain à 14 h dans mon agenda.", listOf(noLeak()), tool = "agenda"),
        Case("outil", "Comment aller à la gare de Pau en voiture ?", listOf(noLeak()), tool = "itineraire"),
        Case("outil", "Mets une chanson de Joe Dassin.", listOf(noLeak()), tool = "musique"),
        Case("outil", "Note dans ma liste de courses : beurre et farine.", listOf(noLeak()), tool = "notes"),

        Case("image", "De quelle couleur est le rond sur cette image ?", listOf(has("rouge", "rouge")), image = { picCircle() }),
        Case("image", "Quel nombre est écrit sur cette image ?", listOf(has("42", "\\b42\\b")), image = { picText() }),
        Case("image", "Combien de carrés bleus vois-tu sur cette image ?", listOf(has("3", "\\b3\\b|trois")), image = { picSquares() }),

        Case("bibliothèque", "C'est quoi un trou noir ?", listOf(has("notion clé", "gravit|lumiere|masse|espace")), rag = true),
        Case("bibliothèque", "Que faire en cas de brûlure ?", listOf(has("refroidir à l'eau", "eau|refroidi|froid")), rag = true),
        Case("bibliothèque", "Comment fonctionne la photosynthèse ?", listOf(has("notion clé", "lumiere|chlorophylle|co2|dioxyde|glucose|sucre")), rag = true),

        Case("rédaction", "Rédige un court compte rendu de la réunion d'équipe de ce matin : on a parlé du planning de décembre et de la sortie au zoo. Mets un titre et des sections.",
            listOf(minLen(350), sections(2), has("parle du zoo", "zoo"), has("parle de décembre", "decembre"), complete())),
        Case("rédaction", "Écris un exposé pour des collégiens sur le recyclage, avec une introduction, deux parties et une conclusion.",
            listOf(minLen(700), has("introduction", "introduction"), has("conclusion", "conclusion|pour conclure|en conclusion|en resume"), paragraphs(4), complete())),
        Case("rédaction", "Fais une synthèse de ce texte en exactement 3 points :\n$sleepText",
            listOf(bullets(3, 3), has("parle du sommeil", "sommeil|dormir|nuit"), maxLen(sleepText.length), complete())),
        Case("rédaction", "Rédige un mail poli à l'école pour excuser l'absence de mon fils lundi dernier.",
            listOf(has("formule d'appel", "bonjour|madame|monsieur"), has("parle de l'absence", "absen"), has("formule de politesse", "cordialement|salutations|sentiments|merci|bien a vous|respectueusement"), complete())),
        Case("rédaction", "Donne une liste de 5 idées d'activités pour un enfant un jour de pluie.", listOf(bullets(5), complete())),
        Case("rédaction", "Explique à un enfant de 8 ans pourquoi le ciel est bleu, en 3 phrases maximum.",
            listOf(has("parle de la lumière ou du soleil", "lumiere|soleil|couleur"), maxLen(500), complete())),
    )

    // ---------------------------------------------------------------- images de test (dessinées par Nox)

    private fun canvas(draw: (Canvas, Paint) -> Unit): ByteArray {
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        draw(c, Paint(Paint.ANTI_ALIAS_FLAG))
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it); b.recycle() }.toByteArray()
    }

    private fun picCircle() = canvas { c, p -> p.color = Color.rgb(220, 20, 30); c.drawCircle(256f, 256f, 150f, p) }

    private fun picText() = canvas { c, p ->
        p.color = Color.BLACK; p.textSize = 230f; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        c.drawText("42", 256f, 340f, p)
    }

    private fun picSquares() = canvas { c, p ->
        p.color = Color.rgb(30, 80, 220)
        for (i in 0 until 3) c.drawRect(40f + i * 160f, 196f, 150f + i * 160f, 316f, p)
    }

    // ---------------------------------------------------------------- harnais (sans le modèle)

    private fun harness(): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        fun cmd(t: String, skill: String) { val got = Commands.match(t)?.skill; out += "commande « $t » → ${got ?: "modèle"} (attendu $skill)" to (got == skill) }
        cmd("Mets un minuteur de 10 minutes", "horloge")
        cmd("Allume la lampe", "telephone")
        cmd("Mets le volume à 40", "telephone")
        cmd("Ajoute des œufs à la liste de courses", "notes")
        cmd("Rappelle-moi dans 30 minutes de sortir le chien", "rappel")
        cmd("Ouvre YouTube", "ouvrir")
        cmd("Combien font 15 fois 4", "calculer")
        val chain = Commands.matchAll("Mets le volume à 30 et allume la lampe")?.size ?: 0
        out += "enchaînement « volume puis lampe » → $chain commande(s) (attendu 2)" to (chain == 2)
        val none = Commands.match("Ouvre WhatsApp et écris à Paul")
        out += "« Ouvre WhatsApp et écris à Paul » laissé au modèle" to (none == null)
        fun intent(t: String, kind: String) { val got = Intents.detect(t, 9)?.kind; out += "phrase « $t » → ${got ?: "aucune"} (attendu $kind)" to (got == kind) }
        intent("Le point du matin", Intents.BRIEFING)
        intent("Note : appeler le garage", Intents.NOTE)
        intent("Mode interprète espagnol", Intents.INTERPRETER)
        intent("Retiens que j'aime le thé vert", Intents.REMEMBER)
        intent("Demande en ligne : la météo de demain", Intents.ASK_ONLINE)
        intent("Résume mon écran", Intents.SCREEN_SUMMARY)
        out += "horloge « Quelle heure est-il ? » répondue sans modèle" to (Router.timeAnswer("Quelle heure est-il ?") != null)
        out += "recherche web « Cherche sur internet la météo à Pau »" to (WebSearch.queryOf("Cherche sur internet la météo à Pau") != null)
        out += "incertitude « Je ne sais pas » repérée" to Intents.uncertain("Je ne sais pas qui a gagné.")
        val top = a.library.search("C'est quoi un trou noir ?", k = 3).firstOrNull()?.chunk?.title ?: "rien"
        out += "bibliothèque « trou noir » → $top" to fold(top).contains("trou noir")
        return out
    }

    // ---------------------------------------------------------------- déroulé

    @Volatile private var stop = false
    private val main = Handler(Looper.getMainLooper())
    private val report = StringBuilder()
    private var savedUri: Uri? = null
    private var savedName = ""

    fun show() {
        val engine = a.engine
        if (!engine.ready) { a.toast("Aucun modèle chargé"); return }
        if (engine.busy) { a.toast("Nox est en train de répondre, réessaie dans un instant"); return }
        val out: TextView = a.text("", 13.5f, Ui.TEXT)
        val head: TextView = a.text("${cases.size} questions au modèle + test du harnais · ${engine.modelLabel} · ${engine.backendLabel}\n" +
            "Durée : 5 à 10 minutes. Les outils sont simulés : rien n'est envoyé ni modifié.\n" + previous(), 13f, Ui.MUTED)
        val shareBtn = a.pillButton("Partager le rapport") { share() }.apply { visibility = android.view.View.GONE }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(out, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(10) })
            addView(shareBtn, LinearLayout.LayoutParams(-1, a.dp(48)).apply { topMargin = a.dp(12) })
        }
        val scroll = ScrollView(a).apply { addView(box) }
        NoxDialog.Builder(a).setTitle("Banc d'essai").setIcon("🧪").setView(scroll).setCancelable(false)
            .setNegativeButton("Arrêter / fermer") { _, _ -> stop = true }
            .show()
        Thread { run(out, scroll); main.post { shareBtn.visibility = android.view.View.VISIBLE } }.start()
    }

    private fun run(out: TextView, scroll: ScrollView) {
        val engine = a.engine
        val skills = Nox.skills
        fun line(s: String) = main.post { out.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
        val startedAt = System.currentTimeMillis()
        val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date(startedAt))
        NoxLog.p("BANC", "Banc d'essai lancé : harnais + ${cases.size} questions · ${engine.modelLabel} · ${engine.backendLabel}")
        header(engine)

        // 1. Harnais
        line("── Harnais (sans le modèle) ──")
        val h = harness()
        report.append("── HARNAIS (aiguillage sans le modèle) ──\n")
        h.forEach { (label, ok) -> line("${if (ok) "✓" else "✗"} $label"); report.append("${if (ok) "✓" else "✗"} $label\n") }
        val hOk = h.count { it.second }
        report.append("Harnais : $hOk/${h.size}\n\n")
        line("Harnais : $hOk/${h.size}\n")

        // 2. Modèle
        report.append("── QUESTIONS AU MODÈLE ──\n")
        val results = ArrayList<Result>()
        var lastKind = ""
        skills.dryRun = true
        try {
            for ((i, c) in cases.withIndex()) {
                if (stop) break
                if (c.kind != lastKind) { line("── ${c.kind} ──"); lastKind = c.kind }
                if (engine.contextPct() > 55) freshConversation(engine)
                val r = ask(engine, skills, c)
                results += r
                val mark = when { r.skipped -> "–"; r.passed -> "✓"; else -> "✗" }
                line("$mark ${c.q.lineSequence().first().take(90)}\n   → ${r.answer.replace('\n', ' ').take(130).ifBlank { "(rien)" }}" +
                    (if (r.failed.isNotEmpty()) "\n   manque : ${r.failed.joinToString(", ")}" else "") +
                    "  · ${if (r.firstMs > 0) "${r.firstMs} ms" else "—"}")
                reportCase(i + 1, r)
            }
        } finally {
            skills.dryRun = false
        }

        // 3. Bilan
        val counted = results.filter { !it.skipped }
        val ok = counted.count { it.passed }
        val firsts = counted.mapNotNull { it.firstMs.takeIf { f -> f > 0 } }.sorted()
        val median = if (firsts.isEmpty()) 0L else firsts[firsts.size / 2]
        val byKind = LinkedHashMap<String, IntArray>()
        counted.forEach { r -> byKind.getOrPut(r.case.kind) { IntArray(2) }.let { it[1]++; if (r.passed) it[0]++ } }
        val kinds = byKind.entries.joinToString(" · ") { "${it.key} ${it.value[0]}/${it.value[1]}" }
        val total = (System.currentTimeMillis() - startedAt) / 1000
        val skipped = results.count { it.skipped }
        val summary = "Modèle : $ok/${counted.size}${if (skipped > 0) " ($skipped sauté(s))" else ""} · harnais $hOk/${h.size} · 1er mot médian $median ms · ${total / 60} min ${total % 60} s\n$kinds"
        val complete = !stop && results.size == cases.size
        val cmp = if (complete) { save(ok, counted.size, hOk, h.size, median, byKind); compare(ok, counted.size, byKind) } else "Passage interrompu : score non enregistré."
        NoxLog.p("BANC", summary.replace('\n', ' '))
        line("\n$summary\n$cmp")

        report.append("\n── BILAN ──\n$summary\n$cmp\n\n── JOURNAL PENDANT LE BANC ──\n")
        report.append(NoxLog.fullText().lines().dropWhile { it.length < 14 || it.substring(0, 14) < stamp }.joinToString("\n").takeLast(300_000))
        report.append("\n── FIN DU BANC D'ESSAI ──\n")
        saveReport()
        line(if (savedUri != null) "\n📄 Rapport complet : Téléchargements/Nox/$savedName\nTouche « Partager le rapport » pour me l'envoyer." else "\n⚠ Rapport non enregistré (voir le journal).")
        main.post { engine.resetConversation(a.history()) }
    }

    private fun freshConversation(engine: LlmEngine) {
        val latch = CountDownLatch(1)
        main.post { engine.resetConversation(emptyList()) { latch.countDown() } }
        latch.await(90, TimeUnit.SECONDS)
        NoxLog.d("BANC", "Mémoire de travail remise à zéro entre deux questions")
    }

    private fun ask(engine: LlmEngine, skills: Skills, c: Case): Result {
        if (c.image != null && !engine.visionReady) return Result(c, "", false, true, listOf("vue désactivée ou modèle sans vision"), 0, 0, emptyList())
        val image = c.image?.invoke()
        val ctx = if (c.rag) a.library.search(c.q, k = 3).takeIf { it.isNotEmpty() }?.let { a.library.contextBlock(it) } else null
        skills.dryCalls.clear()
        val sb = StringBuilder()
        val latch = CountDownLatch(1)
        val start = System.currentTimeMillis()
        var first = 0L
        main.post {
            engine.send(c.q, emptyList(), object : LlmEngine.StreamListener {
                override fun onChunk(text: String) { if (first == 0L) first = System.currentTimeMillis() - start; sb.append(text) }
                override fun onDone(stats: String) = latch.countDown()
                override fun onError(msg: String) { sb.append("\n⚠ ").append(msg); latch.countDown() }
            }, image = image, context = ctx)
        }
        val limit = start + (if (c.kind == "rédaction") 240_000 else 120_000)
        var finished = false
        while (!finished && !stop && System.currentTimeMillis() < limit) finished = latch.await(500, TimeUnit.MILLISECONDS)
        if (!finished) main.post { engine.stop() }
        val totalMs = System.currentTimeMillis() - start
        while (engine.busy && !stop) Thread.sleep(100)
        val answer = sb.toString().trim()
        val tools = skills.dryCalls.toList()
        val failed = ArrayList<String>()
        if (!finished) failed += "pas de réponse en ${totalMs / 1000} s"
        c.checks.forEach { if (!it.ok(answer)) failed += it.label }
        if (c.tool != null && tools.none { it.startsWith(c.tool + "(") }) failed += "outil « ${c.tool} » non appelé"
        NoxLog.i("BANC", "${if (failed.isEmpty()) "✓" else "✗"} ${c.q.take(60)} · ${failed.joinToString(", ")}")
        return Result(c, answer, failed.isEmpty(), false, failed, first, totalMs, tools)
    }

    // ---------------------------------------------------------------- rapport

    private fun header(engine: LlmEngine) {
        report.append("══════════════ BANC D'ESSAI NOX ══════════════\n")
        report.append("Nox ${AppInfo.version(a)} · build ${AppInfo.build(a)} · commit ${AppInfo.commit(a)}\n")
        report.append("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        report.append("Lancé le ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())}\n")
        report.append("Modèle ${engine.modelLabel} · ${engine.backendLabel} · contexte ${a.prefs.contextTokens} jetons · vue ${if (engine.visionReady) "oui" else "non"} · température ${a.prefs.temperature}\n")
        report.append("${previous()}\n\n")
    }

    private fun reportCase(n: Int, r: Result) {
        val c = r.case
        val status = when { r.skipped -> "SAUTÉ"; r.passed -> "RÉUSSI"; else -> "ÉCHEC" }
        val chars = r.answer.length
        val decodeMs = (r.totalMs - r.firstMs).coerceAtLeast(1)
        val tps = if (r.firstMs > 0) chars / 4.0 / (decodeMs / 1000.0) else 0.0
        report.append("#$n [${c.kind}] $status\n")
        report.append("Q : ${c.q}\n")
        if (c.image != null) report.append("Image : dessinée par Nox (512×512)\n")
        if (c.rag) report.append("Extraits de la bibliothèque glissés dans la question\n")
        report.append("Mesures : 1er mot ${r.firstMs} ms · total ${r.totalMs} ms · $chars caractères · ≈${String.format(Locale.FRANCE, "%.1f", tps)} tok/s\n")
        if (r.tools.isNotEmpty()) report.append("Outils appelés : ${r.tools.joinToString(" ; ")}\n")
        report.append("Vérifications : ${c.checks.joinToString(", ") { it.label }}${c.tool?.let { ", outil $it" } ?: ""}\n")
        if (r.failed.isNotEmpty()) report.append("Manque : ${r.failed.joinToString(", ")}\n")
        report.append("Réponse :\n${r.answer.ifBlank { "(rien)" }.prependIndent("  ")}\n\n")
    }

    private fun saveReport() {
        savedName = "nox_banc_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())}.txt"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, savedName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nox")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insertion refusée")
            a.contentResolver.openOutputStream(uri)?.use { it.write(report.toString().toByteArray(Charsets.UTF_8)) } ?: error("écriture refusée")
            a.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            savedUri = uri
            NoxLog.i("BANC", "Rapport enregistré : Téléchargements/Nox/$savedName (${report.length / 1024} Ko)")
        } catch (t: Throwable) {
            NoxLog.e("BANC", "Enregistrement du rapport du banc", t)
        }
    }

    private fun share() {
        if (report.isEmpty()) { a.toast("Le banc n'a pas encore de rapport"); return }
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Banc d'essai Nox ${AppInfo.version(a)} · ${Build.MODEL}")
        val uri = savedUri
        if (uri != null) i.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_TEXT, report.toString().substringBefore("── JOURNAL PENDANT LE BANC ──").take(60_000))
        else i.putExtra(Intent.EXTRA_TEXT, report.take(400_000).toString())
        a.startActivity(Intent.createChooser(i, "Envoyer le banc d'essai"))
    }

    // ---------------------------------------------------------------- historique des scores

    private fun history(): JSONArray = try { JSONArray(a.prefs.benchHistory) } catch (_: Throwable) { JSONArray() }

    private fun save(ok: Int, n: Int, hOk: Int, hN: Int, median: Long, byKind: Map<String, IntArray>) {
        val kinds = JSONObject().also { j -> byKind.forEach { (k, v) -> j.put(k, "${v[0]}/${v[1]}") } }
        val h = history()
        h.put(JSONObject().put("build", AppInfo.build(a)).put("model", a.engine.modelLabel).put("backend", a.engine.backendLabel)
            .put("ok", ok).put("n", n).put("harness", "$hOk/$hN").put("ttft", median).put("kinds", kinds)
            .put("version", VERSION).put("at", System.currentTimeMillis()))
        val keep = JSONArray()
        for (i in maxOf(0, h.length() - 30) until h.length()) keep.put(h.get(i))
        a.prefs.benchHistory = keep.toString()
    }

    private fun previous(): String {
        val h = history()
        if (h.length() == 0) return "Premier passage : ce score servira de référence pour les prochaines versions."
        val p = h.getJSONObject(h.length() - 1)
        val day = SimpleDateFormat("d MMM HH'h'mm", Locale.FRANCE).format(Date(p.optLong("at")))
        return "Dernier passage : build ${p.optString("build")} · ${p.optString("model")} · ${p.optInt("ok")}/${p.optInt("n")} · 1er mot ${p.optLong("ttft")} ms ($day)"
    }

    private fun compare(ok: Int, n: Int, byKind: Map<String, IntArray>): String {
        val h = history()
        var prev: JSONObject? = null
        for (i in h.length() - 2 downTo 0) {
            val p = h.getJSONObject(i)
            if (p.optInt("version") == VERSION) { prev = p; break }
        }
        val p = prev ?: return "Premier passage de cette version du banc : référence enregistrée."
        val ref = "build ${p.optString("build")} (${p.optString("model")})"
        val diff = ok - p.optInt("ok")
        val kinds = p.optJSONObject("kinds")
        val detail = byKind.entries.mapNotNull { (k, v) ->
            val before = kinds?.optString(k)?.substringBefore('/')?.toIntOrNull() ?: return@mapNotNull null
            val d = v[0] - before
            if (d == 0) null else "$k ${if (d > 0) "+" else ""}$d"
        }.joinToString(" · ")
        val head = when {
            p.optInt("n") != n -> "Comparaison impossible (questions différentes) avec le $ref"
            diff > 0 -> "📈 +$diff bonne(s) réponse(s) par rapport au $ref"
            diff < 0 -> "📉 $diff bonne(s) réponse(s) par rapport au $ref"
            else -> "= même score que le $ref"
        }
        return if (detail.isEmpty()) head else "$head\n   détail : $detail"
    }

    companion object {
        /** Numéro de la série de questions : les scores ne se comparent qu'à série égale. */
        private const val VERSION = 2

        /** Dernier score, pour le diagnostic. */
        fun last(prefs: Prefs): String = try {
            val h = JSONArray(prefs.benchHistory)
            if (h.length() == 0) "jamais lancé (Menu › Banc d'essai)" else h.getJSONObject(h.length() - 1).let {
                "${it.optInt("ok")}/${it.optInt("n")} · harnais ${it.optString("harness", "—")} · build ${it.optString("build")} · ${it.optString("model")} · 1er mot ${it.optLong("ttft")} ms"
            }
        } catch (_: Throwable) { "illisible" }
    }
}
