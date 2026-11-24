package com.application.nox

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.text
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Banc d'essai qualité : les mêmes questions à chaque version, pour savoir si une version répond
 * mieux ou moins bien que la précédente. Seuls des outils sans effet sont sollicités (heure, calcul).
 */
class Bench(private val a: MainActivity) {

    private data class Case(val kind: String, val q: String, val expect: Regex, val rag: Boolean = false)

    private val cases = listOf(
        Case("culture", "Quelle est la capitale de l'Italie ?", rx("rome")),
        Case("culture", "Qui a écrit Les Misérables ?", rx("hugo")),
        Case("culture", "En quelle année a eu lieu la prise de la Bastille ?", rx("1789")),
        Case("culture", "Quel est le plus grand océan du monde ?", rx("pacifique")),
        Case("culture", "Quelle planète est la plus proche du Soleil ?", rx("mercure")),
        Case("culture", "Quelle est la langue officielle du Brésil ?", rx("portugais")),
        Case("culture", "Quelle est la formule chimique de l'eau ?", rx("h2o|h₂o")),
        Case("logique", "Combien de pattes a une araignée ?", rx("\\b8\\b|huit")),
        Case("logique", "J'ai 3 pommes, j'en mange une. Combien m'en reste-t-il ?", rx("\\b2\\b|deux")),
        Case("logique", "Quel jour vient après mardi ?", rx("mercredi")),
        Case("logique", "Combien y a-t-il de minutes dans 2 heures ?", rx("120|cent vingt")),
        Case("langue", "Traduis « merci beaucoup » en anglais.", rx("thank")),
        Case("langue", "Quel est le contraire de « grand » ?", rx("petit")),
        Case("langue", "Donne un synonyme de « rapide ».", rx("vite|veloce|prompt|leste|vif|express|preste")),
        Case("langue", "Résume en une phrase : le chat dort sur le canapé toute la journée car il fait froid dehors.", rx("chat|canape")),
        Case("outil", "Combien font 37 fois 43 ?", rx("1\\s?591")),
        Case("outil", "Quelle heure est-il ?", rx("\\d{1,2}\\s?(?:h|:)\\s?\\d{0,2}")),
        Case("bibliothèque", "C'est quoi un trou noir ?", rx("gravit|lumiere|masse|espace"), rag = true),
        Case("bibliothèque", "Que faire en cas de brûlure ?", rx("eau|refroidi|froid"), rag = true),
        Case("bibliothèque", "Comment fonctionne la photosynthèse ?", rx("lumiere|chlorophylle|co2|dioxyde|glucose|sucre"), rag = true),
    )

    private fun rx(s: String) = Regex(s)

    @Volatile private var stop = false

    fun show() {
        val engine = a.engine
        if (!engine.ready) { a.toast("Aucun modèle chargé"); return }
        if (engine.busy) { a.toast("Nox est en train de répondre, réessaie dans un instant"); return }
        val out: TextView = a.text("", 13.5f, Ui.TEXT)
        val head: TextView = a.text("${cases.size} questions · ${engine.modelLabel} · ${engine.backendLabel}\n" + previous(), 13f, Ui.MUTED)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(out, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(10) })
        }
        val scroll = ScrollView(a).apply { addView(box) }
        val d = NoxDialog.Builder(a).setTitle("Banc d'essai").setIcon("🧪").setView(scroll).setCancelable(false)
            .setPositiveButton("Fermer") { _, _ -> stop = true }.show()
        Thread { run(out, scroll, d) }.start()
    }

    private fun run(out: TextView, scroll: ScrollView, d: NoxDialog) {
        val engine = a.engine
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun line(s: String) = main.post { out.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
        NoxLog.i("BANC", "Banc d'essai lancé : ${cases.size} questions")
        val t0 = System.currentTimeMillis()
        var ok = 0
        var n = 0
        val firsts = ArrayList<Long>()
        val byKind = LinkedHashMap<String, IntArray>()
        for (c in cases) {
            if (stop) break
            n++
            val sb = StringBuilder()
            val latch = CountDownLatch(1)
            val start = System.currentTimeMillis()
            var first = 0L
            val ctx = if (c.rag) a.library.search(c.q, k = 3).takeIf { it.isNotEmpty() }?.let { a.library.contextBlock(it) } else null
            main.post {
                engine.send(c.q, emptyList(), object : LlmEngine.StreamListener {
                    override fun onChunk(text: String) { if (first == 0L) first = System.currentTimeMillis() - start; sb.append(text) }
                    override fun onDone(stats: String) = latch.countDown()
                    override fun onError(msg: String) { sb.append("⚠ ").append(msg); latch.countDown() }
                }, context = ctx)
            }
            val finished = latch.await(120, TimeUnit.SECONDS)
            if (!finished) engine.stop()
            val answer = sb.toString().trim()
            val good = finished && c.expect.containsMatchIn(Text.fold(answer.lowercase()))
            if (good) ok++
            if (first > 0) firsts += first
            byKind.getOrPut(c.kind) { IntArray(2) }.let { it[1]++; if (good) it[0]++ }
            line("${if (good) "✓" else "✗"} ${c.q}\n   → ${answer.replace('\n', ' ').take(140).ifBlank { "(rien)" }}" +
                "  · ${if (first > 0) "${first} ms" else "—"}")
            NoxLog.i("BANC", "${if (good) "✓" else "✗"} ${c.q} → ${answer.replace('\n', ' ').take(100)}")
            while (engine.busy && !stop) Thread.sleep(100)
        }
        val total = (System.currentTimeMillis() - t0) / 1000
        val median = firsts.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
        val kinds = byKind.entries.joinToString(" · ") { "${it.key} ${it.value[0]}/${it.value[1]}" }
        val summary = "Score $ok/$n · 1er mot médian $median ms · $total s au total\n$kinds"
        if (!stop && n == cases.size) save(ok, n, median)
        NoxLog.p("BANC", summary.replace('\n', ' '))
        line("\n$summary\n${compare(ok, n)}")
        main.post { engine.resetConversation(a.history()) }
    }

    // ---------------------------------------------------------------- historique des scores

    private fun history(): JSONArray = try { JSONArray(a.prefs.benchHistory) } catch (_: Throwable) { JSONArray() }

    private fun save(ok: Int, n: Int, median: Long) {
        val h = history()
        h.put(JSONObject().put("build", AppInfo.build(a)).put("model", a.engine.modelLabel).put("backend", a.engine.backendLabel)
            .put("ok", ok).put("n", n).put("ttft", median).put("at", System.currentTimeMillis()))
        val keep = JSONArray()
        for (i in maxOf(0, h.length() - 20) until h.length()) keep.put(h.get(i))
        a.prefs.benchHistory = keep.toString()
    }

    private fun previous(): String {
        val h = history()
        if (h.length() == 0) return "Premier passage : ce score servira de référence pour les prochaines versions."
        val p = h.getJSONObject(h.length() - 1)
        val day = SimpleDateFormat("d MMM HH'h'mm", Locale.FRANCE).format(Date(p.optLong("at")))
        return "Dernier passage : build ${p.optString("build")} · ${p.optString("model")} · ${p.optInt("ok")}/${p.optInt("n")} · 1er mot ${p.optLong("ttft")} ms ($day)"
    }

    private fun compare(ok: Int, n: Int): String {
        val h = history()
        if (h.length() < 2) return ""
        val p = h.getJSONObject(h.length() - 2)
        val diff = ok - p.optInt("ok")
        val ref = "build ${p.optString("build")} (${p.optString("model")})"
        return when {
            p.optInt("n") != n -> ""
            diff > 0 -> "📈 +$diff bonne(s) réponse(s) par rapport au $ref"
            diff < 0 -> "📉 $diff bonne(s) réponse(s) par rapport au $ref"
            else -> "= même score que le $ref"
        }
    }

    companion object {
        /** Dernier score, pour le diagnostic. */
        fun last(prefs: Prefs): String = try {
            val h = JSONArray(prefs.benchHistory)
            if (h.length() == 0) "jamais lancé (Menu › Banc d'essai)" else h.getJSONObject(h.length() - 1).let {
                "${it.optInt("ok")}/${it.optInt("n")} · build ${it.optString("build")} · ${it.optString("model")} · 1er mot ${it.optLong("ttft")} ms"
            }
        } catch (_: Throwable) { "illisible" }
    }
}
