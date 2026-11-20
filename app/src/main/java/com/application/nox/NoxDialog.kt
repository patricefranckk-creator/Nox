package com.application.nox

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.application.nox.Ui.dp

/**
 * Les popups de Nox, dans le style de Lia : carte gris violacé arrondie, titre gras,
 * boutons-pilules (principal plein, secondaire discret, destructif en corail), listes en lignes
 * alternées. Même API que AlertDialog.Builder pour le reste du code.
 */
class NoxDialog private constructor(private val ctx: Context, private val sheet: Boolean) {

    private val dialog = Dialog(ctx)
    private val card = LinearLayout(ctx)
    private var msgView: TextView? = null
    private var dismissListener: (() -> Unit)? = null

    val isShowing get() = dialog.isShowing
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }
    fun setMessage(m: CharSequence) { msgView?.text = m }

    class Builder(private val ctx: Context, private val sheet: Boolean = false) {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var view: View? = null
        private var items: Array<out CharSequence>? = null
        private var itemsListener: ((NoxDialog, Int) -> Unit)? = null
        private var pos: Pair<String, ((NoxDialog, Int) -> Unit)?>? = null
        private var neg: Pair<String, ((NoxDialog, Int) -> Unit)?>? = null
        private var neutral: Pair<String, ((NoxDialog, Int) -> Unit)?>? = null
        private var cancelable = true
        private var onDismiss: (() -> Unit)? = null
        private var icon: String? = null

        fun setTitle(t: CharSequence) = apply { title = t }
        fun setIcon(emoji: String) = apply { icon = emoji }
        fun setMessage(m: CharSequence) = apply { message = m }
        fun setView(v: View) = apply { view = v }
        fun setItems(list: Array<out CharSequence>, l: ((NoxDialog, Int) -> Unit)?) = apply { items = list; itemsListener = l }
        fun setPositiveButton(t: String, l: ((NoxDialog, Int) -> Unit)?) = apply { pos = t to l }
        fun setNegativeButton(t: String, l: ((NoxDialog, Int) -> Unit)?) = apply { neg = t to l }
        fun setNeutralButton(t: String, l: ((NoxDialog, Int) -> Unit)?) = apply { neutral = t to l }
        fun setCancelable(c: Boolean) = apply { cancelable = c }
        fun setOnDismissListener(l: (() -> Unit)?) = apply { onDismiss = l }

        fun show(): NoxDialog {
            val d = NoxDialog(ctx, sheet)
            d.build(title, icon, message, view, items, itemsListener, pos, neg, neutral, cancelable)
            d.dismissListener = onDismiss
            d.dialog.setOnDismissListener { d.dismissListener?.invoke() }
            if (ctx is Activity && (ctx.isFinishing || ctx.isDestroyed)) return d
            d.dialog.show()
            return d
        }
    }

    private fun build(
        title: CharSequence?, icon: String?, message: CharSequence?, view: View?,
        items: Array<out CharSequence>?, itemsListener: ((NoxDialog, Int) -> Unit)?,
        pos: Pair<String, ((NoxDialog, Int) -> Unit)?>?, neg: Pair<String, ((NoxDialog, Int) -> Unit)?>?,
        neutral: Pair<String, ((NoxDialog, Int) -> Unit)?>?, cancelable: Boolean,
    ) {
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)

        card.orientation = LinearLayout.VERTICAL
        val r = ctx.dp(if (sheet) 30 else 26).toFloat()
        card.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Ui.SHEET)
            cornerRadii = if (sheet) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else FloatArray(8) { r }
            setStroke(ctx.dp(1), 0x22FFFFFF)
        }
        card.setPadding(ctx.dp(20), ctx.dp(if (sheet) 12 else 22), ctx.dp(20), ctx.dp(18))

        if (sheet) {
            card.addView(View(ctx).apply {
                background = Ui.rounded(0xFF9C9AA8.toInt(), ctx.dp(3).toFloat())
            }, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ctx.dp(14) })
        }

        // En-tête : icône facultative, titre, et « Fermer » pour les panneaux
        if (title != null || sheet) {
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (icon != null) head.addView(TextView(ctx).apply {
                text = icon; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
                background = Ui.rounded(Ui.TILE, ctx.dp(14).toFloat(), Ui.TILE_STROKE, ctx.dp(1))
            }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { marginEnd = ctx.dp(12) })
            head.addView(TextView(ctx).apply {
                text = title ?: ""
                setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sheet) 24f else 20f)
                typeface = Ui.BLACK
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (sheet) head.addView(TextView(ctx).apply {
                text = "Fermer"; setTextColor(Ui.LAVENDER); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Ui.BOLD
                setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(4), ctx.dp(10))
                setOnClickListener { dismiss() }
            })
            card.addView(head)
        }

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (message != null) {
            msgView = TextView(ctx).apply {
                text = message; setTextColor(0xFFC9CCD6.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Ui.MEDIUM; setLineSpacing(0f, 1.3f)
                setPadding(0, ctx.dp(if (title != null) 10 else 0), 0, ctx.dp(4))
            }
            content.addView(msgView)
        }
        items?.forEachIndexed { i, label ->
            content.addView(TextView(ctx).apply {
                text = label; setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Ui.BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
                background = Ui.ripple(Ui.rounded(if (i % 2 == 0) Ui.CARD else Color.TRANSPARENT, ctx.dp(18).toFloat()))
                setOnClickListener { itemsListener?.invoke(this@NoxDialog, i); dismiss() }
            }, LinearLayout.LayoutParams(-1, ctx.dp(56)).apply { topMargin = ctx.dp(if (i == 0) 12 else 4) })
        }
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.setPadding(0, view.paddingTop, 0, view.paddingBottom)
            ((view as? ScrollView)?.getChildAt(0))?.let { it.setPadding(0, it.paddingTop, 0, it.paddingBottom) }
            content.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        }

        // Contenu défilant borné à 72 % de l'écran
        val maxH = (ctx.resources.displayMetrics.heightPixels * (if (sheet) 0.82 else 0.70)).toInt()
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(content) }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        // Boutons : secondaire(s) à gauche, principal à droite
        if (pos != null || neg != null || neutral != null) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            row.setPadding(0, ctx.dp(16), 0, 0)
            neutral?.let { (t, l) -> row.addView(button(t, Style.TEXT) { l?.invoke(this, 0); dismiss() }, LinearLayout.LayoutParams(-2, ctx.dp(46))) }
            if (neutral != null) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            neg?.let { (t, l) -> row.addView(button(t, Style.TEXT) { l?.invoke(this, 1); dismiss() }, LinearLayout.LayoutParams(-2, ctx.dp(46))) }
            pos?.let { (t, l) ->
                val danger = DESTRUCTIVE.any { t.lowercase().startsWith(it) }
                row.addView(button(t, if (danger) Style.DANGER else Style.PRIMARY) { l?.invoke(this, 2); dismiss() },
                    LinearLayout.LayoutParams(-2, ctx.dp(46)).apply { marginStart = ctx.dp(8) })
            }
            card.addView(row)
        }

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = ctx.resources.displayMetrics.widthPixels
            if (sheet) {
                setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setWindowAnimations(android.R.style.Animation_InputMethod)
            } else {
                setLayout((w * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.62f)
        }
    }

    enum class Style { PRIMARY, TEXT, DANGER, OUTLINE }

    private fun button(label: String, style: Style, onClick: () -> Unit) = Companion.button(ctx, label, style, onClick)

    companion object {
        private val DESTRUCTIVE = listOf("supprimer", "effacer", "oublier", "retirer", "fermer l", "quitter", "tout oublier")

        fun sheet(ctx: Context) = Builder(ctx, sheet = true)

        /** Bouton-pilule « pro » réutilisable partout. */
        fun button(ctx: Context, label: String, style: Style, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Ui.BOLD
            setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(10))
            minWidth = ctx.dp(if (style == Style.TEXT) 0 else 96)
            minHeight = ctx.dp(44)
            when (style) {
                Style.PRIMARY -> { setTextColor(0xFF08111F.toInt()); background = Ui.ripple(Ui.rounded(Ui.ACCENT, ctx.dp(23).toFloat()), 0x33000000) }
                Style.DANGER -> { setTextColor(0xFF1A0E0B.toInt()); background = Ui.ripple(Ui.rounded(Ui.DANGER, ctx.dp(23).toFloat()), 0x33000000) }
                Style.OUTLINE -> { setTextColor(Ui.TEXT); background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, ctx.dp(23).toFloat(), 0x55FFFFFF, ctx.dp(1))) }
                Style.TEXT -> { setTextColor(Ui.LAVENDER); background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, ctx.dp(23).toFloat())) }
            }
            setOnClickListener { onClick() }
        }

        /** Ligne de menu façon Lia : emoji, libellé, et un détail facultatif à droite. */
        fun row(ctx: Context, emoji: String, label: String, shaded: Boolean, detail: String? = null, labelColor: Int = Ui.TEXT,
                sub: String? = null, onClick: () -> Unit) =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(18), 0, ctx.dp(16), 0)
                background = Ui.ripple(Ui.rounded(if (shaded) Ui.CARD else Color.TRANSPARENT, ctx.dp(20).toFloat()))
                addView(TextView(ctx).apply { text = emoji; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(ctx.dp(34), -2).apply { marginEnd = ctx.dp(14) })
                val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(TextView(ctx).apply {
                    text = label; setTextColor(labelColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sub == null) 18f else 17f); typeface = Ui.BOLD
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (sub != null) texts.addView(TextView(ctx).apply {
                    text = sub; setTextColor(Ui.MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); typeface = Ui.BOLD
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (detail != null) addView(TextView(ctx).apply {
                    text = detail; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Ui.BOLD
                    setTextColor(if (detail.startsWith("✓")) Ui.GREEN else Ui.AMBER)
                })
                layoutParams = LinearLayout.LayoutParams(-1, ctx.dp(62)).apply { topMargin = ctx.dp(5) }
                setOnClickListener { onClick() }
            }

        fun section(ctx: Context, label: String) = TextView(ctx).apply {
            text = label.uppercase()
            setTextColor(0xFF9C9AA8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); typeface = Ui.BLACK
            letterSpacing = 0.18f
            setPadding(ctx.dp(4), ctx.dp(22), 0, ctx.dp(6))
        }

        /** Tuile « favori » façon Lia. */
        fun tile(ctx: Context, emoji: String, label: String, onClick: () -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.ripple(Ui.rounded(Ui.TILE, ctx.dp(22).toFloat(), Ui.TILE_STROKE, ctx.dp(1)))
            addView(TextView(ctx).apply { text = emoji; setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); gravity = Gravity.CENTER })
            addView(TextView(ctx).apply {
                text = label; setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Ui.BOLD
                gravity = Gravity.CENTER; setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), 0); maxLines = 1
            })
            setOnClickListener { onClick() }
        }

        fun icon(ctx: Context, name: String, tint: Int, sizeDp: Int = 24): ImageView = ImageView(ctx).apply {
            val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
            if (id != 0) setImageResource(id)
            setColorFilter(tint)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        }
    }
}
