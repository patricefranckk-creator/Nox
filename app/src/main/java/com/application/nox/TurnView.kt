package com.application.nox

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.application.nox.Ui.dp

/**
 * Un tour de parole sur le fil de Lia : une ligne verticale à gauche, un point bleu pour Nox,
 * « TOI » en orange aligné à droite, « NOX » en bleu ; autour de la réponse ce qui l'a nourrie
 * (extraits de la bibliothèque, actions faites) puis les relances à toucher.
 */
class TurnView(private val a: Context, val msg: ChatMsg) : LinearLayout(a) {

    interface Actions {
        fun copy(m: ChatMsg)
        fun readAloud(m: ChatMsg)
        fun edit(m: ChatMsg)
        fun quickReply(text: String)
    }

    val body: TextView
    private val contextBox: LinearLayout
    private val toolsBox: LinearLayout
    private val footer: LinearLayout
    private val suggestBox: FlowLayout
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    var actions: Actions? = null

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        val user = msg.fromUser
        gravity = if (user) Gravity.END else Gravity.START
        setPadding(a.dp(18), a.dp(13), a.dp(18), a.dp(13))

        addView(TextView(a).apply {
            text = if (user) "TOI" else "NOX"
            setTextColor(if (user) Ui.USER_TAG else Ui.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.BLACK
            letterSpacing = 0.19f
        })

        contextBox = LinearLayout(a).apply {
            orientation = VERTICAL
            visibility = GONE
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            background = Ui.rounded(0xFF131C30.toInt(), a.dp(14).toFloat(), 0xFF22325A.toInt(), a.dp(1))
        }
        addView(contextBox, LayoutParams(-1, -2).apply { topMargin = a.dp(10) })

        toolsBox = LinearLayout(a).apply { orientation = VERTICAL; visibility = GONE }
        addView(toolsBox, LayoutParams(-2, -2).apply { topMargin = a.dp(6) })

        body = TextView(a).apply {
            if (user) {
                setTextColor(Ui.USER_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Ui.BLACK
                gravity = Gravity.END
                setLineSpacing(0f, 1.25f)
            } else {
                setTextColor(Ui.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18.5f)
                typeface = Ui.BOLD
                setLineSpacing(0f, 1.38f)
            }
        }
        addView(body, LayoutParams(if (user) -2 else -1, -2).apply { topMargin = a.dp(if (user) 8 else 10) })

        footer = LinearLayout(a).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addView(footer, LayoutParams(-2, -2).apply { topMargin = a.dp(6) })

        suggestBox = FlowLayout(a, a.dp(8)).apply { visibility = GONE }
        addView(suggestBox, LayoutParams(-1, -2).apply { topMargin = a.dp(12) })

        render()
        refreshFooter()
        msg.sources.takeIf { it.isNotEmpty() }?.let { setSources(it) }
        msg.tools.forEach { addTool(it) }
        if (msg.suggestions.isNotEmpty()) setSuggestions(msg.suggestions)
    }

    /** Le point bleu sur la ligne du fil, devant « NOX ». */
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (!msg.fromUser) c.drawCircle((paddingLeft - a.dp(11)).toFloat(), paddingTop + a.dp(9).toFloat(), a.dp(5).toFloat(), dot)
    }

    fun render() {
        val t = msg.text
        body.text = when {
            t.isEmpty() && !msg.fromUser -> "…"
            msg.fromUser -> t
            else -> Markdown.render(t)
        }
    }

    /** Petites icônes sous le message : copier, relire (Nox) ou modifier (toi). */
    fun refreshFooter() {
        footer.removeAllViews()
        if (msg.text.isBlank()) { footer.visibility = GONE; return }
        footer.visibility = VISIBLE
        if (msg.fromUser) footer.addView(icon("ic_edit", "Modifier") { actions?.edit(msg) })
        footer.addView(icon("ic_copy", "Copier") { actions?.copy(msg) })
        if (!msg.fromUser) footer.addView(icon("ic_volume", "Relire à voix haute") { actions?.readAloud(msg) })
        if (msg.voice) footer.addView(TextView(a).apply {
            text = if (msg.fromUser) "dit à voix haute" else "lu à voix haute"
            setTextColor(Ui.FAINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Ui.BOLD
            setPadding(a.dp(2), 0, 0, 0)
        })
    }

    private fun icon(name: String, desc: String, onClick: () -> Unit) = ImageView(a).apply {
        val id = a.resources.getIdentifier(name, "drawable", a.packageName)
        if (id != 0) setImageResource(id)
        setColorFilter(Ui.FAINT)
        contentDescription = desc
        setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
        layoutParams = LayoutParams(a.dp(26), a.dp(26)).apply { marginEnd = a.dp(10) }
        background = Ui.ripple(Ui.rounded(0, a.dp(13).toFloat()))
        setOnClickListener { onClick() }
    }

    /** Carte « Dans ta bibliothèque » : ce que Nox a relu avant de répondre. */
    fun setSources(titles: List<String>) {
        msg.sources = titles
        contextBox.removeAllViews()
        contextBox.addView(TextView(a).apply {
            text = "DANS TA BIBLIOTHÈQUE"
            setTextColor(0xFF7FBFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); typeface = Ui.BLACK
            letterSpacing = 0.15f
        })
        titles.take(4).forEach { t ->
            contextBox.addView(TextView(a).apply {
                text = t
                setTextColor(Ui.MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Ui.BOLD
                maxLines = 2; setPadding(0, a.dp(3), 0, 0)
            })
        }
        contextBox.visibility = VISIBLE
    }

    /** Une action faite pendant la réponse (« ⏱ 5 min »). */
    fun addTool(label: String) {
        if (label !in msg.tools) msg.tools += label
        toolsBox.visibility = VISIBLE
        toolsBox.addView(TextView(a).apply {
            text = label
            setTextColor(Ui.CYAN); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Ui.BOLD
            setPadding(a.dp(11), a.dp(5), a.dp(11), a.dp(5))
            background = Ui.rounded(0xFF0F2235.toInt(), a.dp(12).toFloat(), 0xFF1D4A6E.toInt(), a.dp(1))
        }, LayoutParams(-2, -2).apply { topMargin = a.dp(4) })
    }

    /** Relances proposées par Nox : un tap = l'envoyer. */
    fun setSuggestions(list: List<String>) {
        msg.suggestions = list
        suggestBox.removeAllViews()
        if (list.isEmpty()) { suggestBox.visibility = GONE; return }
        list.forEach { s ->
            suggestBox.addView(TextView(a).apply {
                text = s
                setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Ui.BOLD
                setPadding(a.dp(15), a.dp(9), a.dp(15), a.dp(9))
                background = Ui.ripple(Ui.rounded(0, a.dp(20).toFloat(), Ui.ACCENT, a.dp(1.5f)))
                setOnClickListener { actions?.quickReply(s) }
            })
        }
        suggestBox.visibility = VISIBLE
    }

    fun clearSuggestions() { suggestBox.visibility = GONE }

    /** Ligne verticale fixe du fil, dessinée derrière la conversation qui défile. */
    class Timeline(private val x: Float, private val w: Float) : Drawable() {
        private val p = Paint().apply { color = Ui.TIMELINE }
        override fun draw(c: Canvas) { c.drawRect(x - w / 2, bounds.top.toFloat(), x + w / 2, bounds.bottom.toFloat(), p) }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
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
