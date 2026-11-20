package com.application.nox

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.text

/**
 * Un tour de parole, dans l'esprit du fil de Lia : pas de bulles, une étiquette « TOI » / « NOX »,
 * la réponse de Nox en police à empattements, et autour d'elle ce qui l'a nourrie
 * (extraits de la bibliothèque, actions faites) puis des relances à toucher.
 */
class TurnView(private val a: MainActivity, val msg: ChatMsg) : LinearLayout(a) {

    interface Actions {
        fun copy(m: ChatMsg)
        fun readAloud(m: ChatMsg)
        fun quickReply(text: String)
    }

    val body: TextView
    private val label: TextView
    private val contextBox: LinearLayout
    private val toolsBox: LinearLayout
    private val footer: TextView
    private val suggestRow: LinearLayout
    private val suggestScroll: HorizontalScrollView
    var actions: Actions? = null

    init {
        orientation = VERTICAL
        val user = msg.fromUser
        gravity = if (user) Gravity.END else Gravity.START
        setPadding(if (user) a.dp(36) else a.dp(4), a.dp(14), a.dp(4), a.dp(6))

        label = a.text(if (user) "TOI" else "NOX", 10.5f, if (user) Ui.USER_TAG else Ui.ACCENT, bold = true).apply {
            letterSpacing = 0.18f
        }
        addView(label)

        contextBox = LinearLayout(a).apply {
            orientation = VERTICAL
            visibility = GONE
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
            background = Ui.rounded(0xFF111A30.toInt(), a.dp(12).toFloat(), 0xFF22305A.toInt(), a.dp(1))
        }
        addView(contextBox, LayoutParams(-1, -2).apply { topMargin = a.dp(6); marginStart = a.dp(14) })

        toolsBox = LinearLayout(a).apply { orientation = VERTICAL; visibility = GONE }
        addView(toolsBox, LayoutParams(-2, -2).apply { topMargin = a.dp(4); marginStart = a.dp(14) })

        body = TextView(a).apply {
            setTextColor(if (user) Ui.USER_TEXT else Ui.TEXT)
            if (user) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.END
                setLineSpacing(0f, 1.3f)
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.45f)
            }
            setTextIsSelectable(false)
        }
        addView(body, LayoutParams(if (user) -2 else -1, -2).apply { topMargin = a.dp(6); if (!user) marginStart = a.dp(14) })

        footer = a.text("", 11f, Ui.MUTED).apply { setPadding(0, a.dp(6), 0, 0) }
        addView(footer, LayoutParams(-2, -2).apply { if (!user) marginStart = a.dp(14) })

        suggestRow = LinearLayout(a).apply { orientation = HORIZONTAL }
        suggestScroll = HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            visibility = GONE
            addView(suggestRow)
        }
        addView(suggestScroll, LayoutParams(-1, -2).apply { topMargin = a.dp(8); marginStart = a.dp(14) })

        render()
        refreshFooter()
        msg.sources.takeIf { it.isNotEmpty() }?.let { setSources(it) }
        msg.tools.forEach { addTool(it) }
        if (msg.suggestions.isNotEmpty()) setSuggestions(msg.suggestions)
    }

    fun render() {
        val t = msg.text
        body.text = when {
            t.isEmpty() && !msg.fromUser -> "…"
            msg.fromUser -> t
            else -> Markdown.render(t)
        }
    }

    /** « 🎤 dit à voix haute · ⧉ copier · 🔊 relire » */
    fun refreshFooter() {
        if (msg.text.isBlank()) { footer.visibility = GONE; return }
        footer.visibility = VISIBLE
        val parts = ArrayList<String>()
        if (msg.voice) parts += if (msg.fromUser) "🎤 dit à voix haute" else "🔊 lu à voix haute"
        parts += "⧉ copier"
        if (!msg.fromUser) parts += "▶ relire"
        footer.text = parts.joinToString("   ")
        footer.setOnClickListener(null)
        footer.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                val tv = v as TextView
                val off = tv.getOffsetForPosition(e.x, e.y)
                val s = tv.text.toString()
                val iCopy = s.indexOf("⧉"); val iRead = s.indexOf("▶")
                when {
                    iRead >= 0 && off >= iRead -> actions?.readAloud(msg)
                    iCopy >= 0 && off >= iCopy -> actions?.copy(msg)
                }
                v.performClick()
            }
            true
        }
    }

    /** Carte « Dans ta bibliothèque » : ce que Nox a relu avant de répondre. */
    fun setSources(titles: List<String>) {
        msg.sources = titles
        contextBox.removeAllViews()
        contextBox.addView(a.text("📚  DANS TA BIBLIOTHÈQUE", 10f, Ui.CYAN, bold = true).apply { letterSpacing = 0.14f })
        titles.take(4).forEach { t ->
            contextBox.addView(a.text("•  $t", 12.5f, Ui.MUTED).apply { maxLines = 2; setPadding(0, a.dp(3), 0, 0) })
        }
        contextBox.visibility = VISIBLE
    }

    /** Une action faite pendant la réponse (« ⏱ 5 min »). */
    fun addTool(label: String) {
        if (label !in msg.tools) msg.tools += label
        toolsBox.visibility = VISIBLE
        toolsBox.addView(a.text(label, 12f, Ui.CYAN).apply {
            setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4))
            background = Ui.rounded(0xFF0F1E2A.toInt(), a.dp(12).toFloat(), 0xFF1D4A5E.toInt(), a.dp(1))
        }, LayoutParams(-2, -2).apply { topMargin = a.dp(4) })
    }

    /** Relances proposées par Nox : un tap = l'envoyer. */
    fun setSuggestions(list: List<String>) {
        msg.suggestions = list
        suggestRow.removeAllViews()
        if (list.isEmpty()) { suggestScroll.visibility = GONE; return }
        list.forEach { s ->
            suggestRow.addView(a.text(s, 13f, Ui.TEXT).apply {
                setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
                background = Ui.ripple(Ui.rounded(0x00000000, a.dp(18).toFloat(), Ui.ACCENT, a.dp(1)))
                setOnClickListener { actions?.quickReply(s) }
            }, LayoutParams(-2, -2).apply { marginEnd = a.dp(8) })
        }
        suggestScroll.visibility = VISIBLE
    }

    fun clearSuggestions() { suggestScroll.visibility = GONE }
}
