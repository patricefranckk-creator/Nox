package com.application.nox

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.application.nox.Ui.dp

/**
 * Un tour de parole, épuré : ta question dans une bulle à droite ; la réponse de Nox en texte simple,
 * sous un petit « NOX ». En dessous, au plus deux lignes grises : les sources (bibliothèque ou sites)
 * et ce que Nox a fait (appli pilotée, minuteur…). Ni relances, ni numéros, ni boutons : appui long
 * sur un message pour le copier, le relire ou le modifier.
 */
class TurnView(private val a: Context, val msg: ChatMsg) : LinearLayout(a) {

    interface Actions {
        fun copy(m: ChatMsg)
        fun readAloud(m: ChatMsg)
        fun edit(m: ChatMsg)
        fun quickReply(text: String)
        fun openSources(m: ChatMsg) {}
    }

    val body: TextView
    private val sourcesLine: LinearLayout
    private val sourcesText: TextView
    private val sourcesIcon: ImageView
    private val toolsLine: LinearLayout
    private val toolsText: TextView
    var actions: Actions? = null

    init {
        orientation = VERTICAL
        val user = msg.fromUser
        gravity = if (user) Gravity.END else Gravity.START
        setPadding(a.dp(18), a.dp(9), a.dp(18), a.dp(9))

        if (!user) addView(TextView(a).apply {
            text = "NOX"
            setTextColor(Ui.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Ui.BLACK
            letterSpacing = 0.17f
        })

        body = TextView(a).apply {
            if (user) {
                setTextColor(Ui.USER_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Ui.MEDIUM
                setLineSpacing(0f, 1.3f)
                setPadding(a.dp(15), a.dp(11), a.dp(15), a.dp(11))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Ui.USER_BUBBLE)
                    val r = a.dp(18).toFloat(); val small = a.dp(6).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, small, small, r, r)
                    setStroke(a.dp(1), Ui.USER_BUBBLE_STROKE)
                }
                maxWidth = (a.resources.displayMetrics.widthPixels * 0.78).toInt()
            } else {
                setTextColor(Ui.ANSWER)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                typeface = Ui.MEDIUM
                setLineSpacing(0f, 1.42f)
            }
            setOnLongClickListener { showActions(); true }
        }
        addView(body, LayoutParams(if (user) -2 else -1, -2).apply { topMargin = a.dp(if (user) 0 else 6) })

        sourcesIcon = ImageView(a).apply { setColorFilter(0xFF7E889C.toInt()) }
        sourcesText = line()
        sourcesLine = lineBox(sourcesIcon, sourcesText, chevron = true).apply { setOnClickListener { actions?.openSources(msg) } }
        addView(sourcesLine, LayoutParams(-1, a.dp(40)).apply { topMargin = a.dp(4) })

        toolsText = line()
        toolsLine = lineBox(ImageView(a).apply { setImageResource(drawable("ic_phone_small")); setColorFilter(0xFF7E889C.toInt()) }, toolsText, chevron = false)
        addView(toolsLine, LayoutParams(-1, a.dp(32)).apply { topMargin = a.dp(2) })

        render()
        refreshFooter()
        msg.sources.takeIf { it.isNotEmpty() }?.let { setSources(it) }
        refreshTools()
    }

    private fun drawable(name: String): Int = a.resources.getIdentifier(name, "drawable", a.packageName)

    private fun line() = TextView(a).apply {
        setTextColor(0xFF7E889C.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Ui.MEDIUM
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun lineBox(icon: ImageView, label: TextView, chevron: Boolean) = LinearLayout(a).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        addView(icon, LayoutParams(a.dp(15), a.dp(15)).apply { marginEnd = a.dp(8) })
        addView(label, LayoutParams(0, -2, 1f))
        if (chevron) addView(TextView(a).apply {
            text = "›"; setTextColor(0xFF7E889C.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(a.dp(6), 0, a.dp(4), a.dp(2))
        })
    }

    fun render() {
        val t = msg.text
        body.text = when {
            t.isEmpty() && !msg.fromUser -> "…"
            msg.fromUser -> t
            else -> Markdown.render(t)
        }
    }

    /** Plus d'icônes sous les messages : tout passe par l'appui long. */
    fun refreshFooter() {}

    private fun showActions() {
        if (msg.text.isBlank()) return
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels += "Copier"; acts += { actions?.copy(msg) }
        if (!msg.fromUser) { labels += "Relire à voix haute"; acts += { actions?.readAloud(msg) } }
        if (msg.fromUser) { labels += "Modifier et renvoyer"; acts += { actions?.edit(msg) } }
        if (msg.sources.isNotEmpty()) { labels += "Voir les sources"; acts += { actions?.openSources(msg) } }
        NoxDialog.Builder(a).setItems(labels.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    /**
     * Sources de la réponse : titres de la bibliothèque, ou « titre » + séparateur + lien pour le web.
     * Une seule ligne grise : livre pour la bibliothèque, globe pour internet.
     */
    fun setSources(list: List<String>) {
        msg.sources = list
        if (list.isEmpty()) { sourcesLine.visibility = GONE; return }
        val web = list.any { it.contains(SEP) }
        sourcesIcon.setImageResource(drawable(if (web) "ic_globe_small" else "ic_book_small"))
        sourcesText.text = if (web) list.mapNotNull { sourceUrl(it)?.let { u -> domain(u) } }.distinct().take(3).joinToString(" · ")
            else list.map { sourceTitle(it).substringBefore(" (").substringBefore(" · ") }.distinct().take(3).joinToString(" · ")
        sourcesLine.visibility = VISIBLE
    }

    /** Une action faite pendant la réponse : regroupées sur une seule ligne grise. */
    fun addTool(label: String) {
        if (label !in msg.tools) msg.tools += label
        refreshTools()
    }

    private fun refreshTools() {
        if (msg.tools.isEmpty()) { toolsLine.visibility = GONE; return }
        toolsText.text = msg.tools.map { it.replace(Regex("^[^\\p{L}\\p{N}«]+"), "").trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")
        toolsLine.visibility = VISIBLE
    }

    /** Relances retirées du chat épuré. */
    fun setSuggestions(list: List<String>) { msg.suggestions = emptyList() }

    fun clearSuggestions() {}

    companion object {
        /** Sépare le titre et le lien d'une source web dans [ChatMsg.sources]. */
        const val SEP = "\u001F"
        fun sourceTitle(s: String) = s.substringBefore(SEP)
        fun sourceUrl(s: String): String? = s.substringAfter(SEP, "").ifBlank { null }
        fun domain(url: String): String = try { java.net.URL(url).host.removePrefix("www.") } catch (_: Exception) { url }
    }
}

/** Disposition en lignes qui passent à la ligne (relances, favoris). */
class FlowLayout(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
