package com.application.nox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.field
import com.application.nox.Ui.pillButton
import com.application.nox.Ui.text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * « Contenu de la bibliothèque » : voir exactement ce que le RAG de Nox contient.
 *  - toutes les sources (fiches Freekipedia, documents, listes, conversations), filtrables et cherchables ;
 *  - le détail d'une source : chacun de ses extraits, tel que Nox le relit, et son état de vectorisation ;
 *  - « Tester une recherche » : les extraits que Nox retrouverait pour une question, avec leur score.
 */
class LibraryExplorer(private val a: MainActivity) {

    companion object {
        private const val PAGE = 60
        private const val MAX_EXTRACTS = 150
    }

    private val main = Handler(Looper.getMainLooper())
    private val lib get() = a.library

    private lateinit var summary: TextView
    private lateinit var chips: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var filter: android.widget.EditText

    private var kind: Library.Kind? = null
    private var shown = PAGE
    private var generation = 0
    private val debounce = Runnable { refresh() }
    private var dialog: NoxDialog? = null

    fun show() {
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        summary = a.text("", 12.5f, Ui.CYAN)
        body.addView(summary)

        filter = a.field("Filtrer par titre ou contenu…").apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    shown = PAGE
                    main.removeCallbacks(debounce)
                    main.postDelayed(debounce, 300)
                }
            })
        }
        body.addView(filter, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })

        chips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(10) })

        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)

        body.addView(NoxDialog.section(a, "Tester une recherche"))
        body.addView(a.text("Les extraits que Nox retrouverait pour cette question, dans l'ordre, avec leur score.", 12.5f, Ui.MUTED))
        val question = a.field("Ex : comment arrêter une hémorragie ?").apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }
        body.addView(question, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        val go = { testSearch(question.text.toString()) }
        question.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { go(); true } else false }
        body.addView(a.pillButton("Chercher") { go() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, a.dp(44)).apply { topMargin = a.dp(8) }
        })
        results = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results)

        dialog = NoxDialog.sheet(a).setTitle("Contenu de la bibliothèque").setView(body)
            .setOnDismissListener { main.removeCallbacks(debounce) }
            .show()
        refresh()
    }

    // ------------------------------------------------------------------ liste des sources

    private fun refresh() {
        val gen = ++generation
        val q = filter.text.toString()
        val k = kind
        list.removeAllViews()
        list.addView(a.text("Lecture…", 13f, Ui.MUTED).apply { setPadding(a.dp(4), a.dp(12), 0, 0) })
        Thread {
            val all = lib.sources(q, k)
            val counts = lib.kindCounts()
            val stats = lib.stats()
            val dim = lib.vectorSize()
            main.post {
                if (gen != generation) return@post
                summary.text = stats + (if (dim > 0) " · vecteurs $dim dim." else "") +
                    "\nRecherche : " + (if (lib.semantic) "hybride (mots-clés + sens)" else "mots-clés seulement")
                renderChips(counts)
                renderList(all, q.isNotBlank())
            }
        }.start()
    }

    private fun renderChips(counts: Map<Library.Kind, Int>) {
        chips.removeAllViews()
        val total = counts.values.sum()
        chips.addView(chip("Tout · $total", kind == null) { kind = null; shown = PAGE; refresh() })
        Library.Kind.values().filter { (counts[it] ?: 0) > 0 }.forEach { k ->
            chips.addView(chip("${k.emoji} ${k.label} · ${counts[k]}", kind == k) { kind = k; shown = PAGE; refresh() })
        }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = a.text(label, 13.5f,
        if (selected) 0xFF08111F.toInt() else Ui.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
        background = Ui.ripple(if (selected) Ui.rounded(Ui.ACCENT, a.dp(18).toFloat())
            else Ui.rounded(Ui.CARD, a.dp(18).toFloat(), 0x22FFFFFF, a.dp(1)))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = a.dp(8) }
        setOnClickListener { onClick() }
    }

    private fun renderList(all: List<Library.SourceInfo>, filtered: Boolean) {
        list.removeAllViews()
        if (all.isEmpty()) {
            list.addView(a.text(if (filtered) "Aucune source ne correspond." else "La bibliothèque est vide. Importe une mise à jour Freekipedia ou ajoute un document.",
                14f, Ui.MUTED).apply { setPadding(a.dp(4), a.dp(14), 0, 0) })
            return
        }
        list.addView(a.text("${all.size} source${if (all.size > 1) "s" else ""} · touche pour voir les extraits", 12f, Ui.MUTED)
            .apply { setPadding(a.dp(4), a.dp(10), 0, 0) })
        all.take(shown).forEachIndexed { i, s ->
            val detail = if (s.vectorized == s.extracts) "✓ ${s.extracts}" else "${s.vectorized}/${s.extracts}"
            list.addView(NoxDialog.row(a, s.kind.emoji, s.title, i % 2 == 0, detail = detail,
                sub = "${s.extracts} extrait${if (s.extracts > 1) "s" else ""} · ${kb(s.chars)} · ${date(s.time)}") { openSource(s) })
        }
        if (all.size > shown) {
            list.addView(a.pillButton("Afficher ${minOf(PAGE, all.size - shown)} de plus", filled = false) {
                shown += PAGE; renderList(all, filtered)
            }.apply { layoutParams = LinearLayout.LayoutParams(-2, a.dp(44)).apply { topMargin = a.dp(10) } })
        }
    }

    // ------------------------------------------------------------------ détail d'une source

    private fun openSource(s: Library.SourceInfo) {
        val extracts = lib.extracts(s.source)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(a.text(buildString {
            append("${s.kind.emoji} ${s.kind.label}\n")
            append("${extracts.size} extrait${if (extracts.size > 1) "s" else ""} · ${kb(s.chars)} · ajouté le ${date(s.time)}\n")
            append(if (s.vectorized == s.extracts) "✓ Tous les extraits sont vectorisés"
                else "${s.vectorized}/${s.extracts} vectorisés" + if (lib.semantic) " (le reste se fait en fond)" else " (EmbeddingGemma non actif : recherche par mots-clés)")
        }, 13f, Ui.MUTED))

        extracts.take(MAX_EXTRACTS).forEachIndexed { i, c ->
            box.addView(a.text("EXTRAIT ${i + 1}/${extracts.size} · ${c.text.length} car. · " +
                if (c.vec != null) "✓ vectorisé" else "en attente de vecteur", 11f, if (c.vec != null) Ui.GREEN else Ui.AMBER, bold = true)
                .apply { letterSpacing = 0.06f; setPadding(a.dp(4), a.dp(14), 0, a.dp(4)) })
            box.addView(a.text(c.text, 14.5f, Ui.TEXT).apply {
                setTextIsSelectable(true)
                setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
                background = Ui.rounded(Ui.CARD, a.dp(14).toFloat())
            })
        }
        if (extracts.size > MAX_EXTRACTS) box.addView(a.text("… ${extracts.size - MAX_EXTRACTS} extraits de plus non affichés.", 12.5f, Ui.MUTED)
            .apply { setPadding(a.dp(4), a.dp(10), 0, 0) })

        val b = NoxDialog.Builder(a).setTitle(s.title).setIcon(s.kind.emoji).setView(box)
            .setNegativeButton("Fermer", null)
            .setNeutralButton("Copier") { _, _ -> copy(s.title, extracts.joinToString("\n\n") { it.text }) }
        if (s.kind != Library.Kind.NOTE) b.setPositiveButton("Retirer") { _, _ -> confirmRemove(s) }
        b.show()
    }

    private fun confirmRemove(s: Library.SourceInfo) {
        NoxDialog.Builder(a).setTitle("Retirer de la bibliothèque ?")
            .setMessage("« ${s.title} » et ses ${s.extracts} extrait${if (s.extracts > 1) "s" else ""} ne seront plus utilisés par Nox." +
                if (s.kind == Library.Kind.FREEKIPEDIA) "\n\nUne prochaine mise à jour Freekipedia qui contient cette fiche la remettra." else "")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Retirer") { _, _ -> lib.removeSource(s.source) { a.toast("« ${s.title} » retiré"); refresh() } }
            .show()
    }

    // ------------------------------------------------------------------ test de recherche

    private fun testSearch(raw: String) {
        val q = raw.trim()
        results.removeAllViews()
        if (q.length < 3) { results.addView(a.text("Écris au moins 3 caractères.", 13f, Ui.MUTED)); return }
        results.addView(a.text("Recherche…", 13f, Ui.MUTED).apply { setPadding(a.dp(4), a.dp(10), 0, 0) })
        Thread {
            val start = System.currentTimeMillis()
            val hits = try { lib.search(q, k = 6) } catch (t: Throwable) { NoxLog.w("RAG", "Test de recherche impossible", t); emptyList() }
            val auto = try { lib.search(q, k = 3, strict = true).map { it.chunk.id }.toSet() } catch (_: Throwable) { emptySet() }
            val ms = System.currentTimeMillis() - start
            main.post { renderResults(q, hits, auto, ms) }
        }.start()
    }

    private fun renderResults(q: String, hits: List<Library.Hit>, auto: Set<Long>, ms: Long) {
        results.removeAllViews()
        results.addView(a.text("${hits.size} extrait${if (hits.size > 1) "s" else ""} pour « $q » en $ms ms" +
            if (lib.semantic) " · mots-clés + sens" else " · mots-clés seulement", 12.5f, Ui.CYAN).apply { setPadding(a.dp(4), a.dp(10), 0, 0) })
        if (hits.isEmpty()) {
            results.addView(a.text("Rien de pertinent : Nox répondrait sans sa bibliothèque.", 13.5f, Ui.MUTED).apply { setPadding(a.dp(4), a.dp(6), 0, 0) })
            return
        }
        hits.forEachIndexed { i, h ->
            val injected = h.chunk.id in auto
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
                background = Ui.ripple(Ui.rounded(Ui.CARD, a.dp(14).toFloat(), if (injected) Ui.GREEN else 0x22FFFFFF, a.dp(1)))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) }
            }
            card.addView(a.text("${i + 1}. ${Library.Kind.of(h.chunk.source).emoji} ${h.chunk.title}", 14.5f, Ui.TEXT, bold = true).apply { maxLines = 2 })
            card.addView(a.text(String.format(Locale.FRANCE, "%s · RRF %.4f", h.why, h.score) +
                if (injected) " · glissé automatiquement avant la réponse" else " · via l'outil « bibliotheque »", 11.5f,
                if (injected) Ui.GREEN else Ui.AMBER, bold = true).apply { setPadding(0, a.dp(3), 0, a.dp(6)) })
            card.addView(a.text(h.chunk.text.replace('\n', ' ').let { if (it.length > 420) it.take(420) + "…" else it }, 13.5f, 0xFFC9CCD6.toInt()))
            card.setOnClickListener {
                lib.sources().firstOrNull { it.source == h.chunk.source }?.let { openSource(it) }
            }
            results.addView(card)
        }
        results.addView(a.text("Contour vert : extrait assez pertinent pour être glissé tout seul dans la conversation. Touche une carte pour voir la source complète.",
            11.5f, Ui.MUTED).apply { setPadding(a.dp(4), a.dp(8), 0, 0) })
    }

    // ------------------------------------------------------------------ outils

    private fun copy(label: String, text: String) {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        a.toast("Copié")
    }

    private fun kb(chars: Int) = if (chars < 1000) "$chars car." else String.format(Locale.FRANCE, "%.1f k car.", chars / 1000.0)

    private fun date(t: Long) = SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(Date(t))
}
