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
 * Les popups de Nox : carte bleu nuit au fil fin, titre gras, boutons pleine largeur (principal
 * coloré, secondaire sobre, destructif en corail), lignes groupées en cartes avec icône teintée.
 * Même API que AlertDialog.Builder pour le reste du code.
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
            setStroke(ctx.dp(1), Ui.STROKE)
        }
        card.setPadding(ctx.dp(20), ctx.dp(if (sheet) 12 else 22), ctx.dp(20), ctx.dp(18))

        if (sheet) {
            card.addView(View(ctx).apply {
                background = Ui.rounded(0xFF2A3456.toInt(), ctx.dp(2).toFloat())
            }, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ctx.dp(14) })
        }

        // En-tête : icône facultative, titre, et « Fermer » pour les panneaux
        if (title != null || sheet) {
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (icon != null) head.addView(TextView(ctx).apply {
                text = icon; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER
                background = Ui.rounded(Ui.SOFT, ctx.dp(14).toFloat())
            }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { marginEnd = ctx.dp(12) })
            head.addView(TextView(ctx).apply {
                text = title ?: ""
                setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sheet) 24f else 20f)
                typeface = Ui.BLACK
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (sheet) head.addView(TextView(ctx).apply {
                text = "✕"; gravity = Gravity.CENTER; contentDescription = "Fermer"
                setTextColor(0xFFC9D1E3.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Ui.BOLD
                background = Ui.ripple(Ui.rounded(Ui.SOFT, ctx.dp(14).toFloat()))
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
            card.addView(head)
        }

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (message != null) {
            msgView = TextView(ctx).apply {
                text = message; setTextColor(0xFFA7B0C2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Ui.MEDIUM; setLineSpacing(0f, 1.35f)
                setPadding(0, ctx.dp(if (title != null) 10 else 0), 0, ctx.dp(4))
            }
            content.addView(msgView)
        }
        items?.forEachIndexed { i, label ->
            content.addView(TextView(ctx).apply {
                text = label; setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Ui.BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
                background = Ui.ripple(Ui.rounded(Ui.CARD, ctx.dp(16).toFloat()))
                setOnClickListener { itemsListener?.invoke(this@NoxDialog, i); dismiss() }
            }, LinearLayout.LayoutParams(-1, ctx.dp(54)).apply { topMargin = ctx.dp(if (i == 0) 12 else 6) })
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

        // Boutons pleine largeur : empilés (principal en haut) dans une fenêtre, côte à côte dans un panneau
        if (pos != null || neg != null || neutral != null) {
            val stacked = !sheet
            val box = LinearLayout(ctx).apply {
                orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                setPadding(0, ctx.dp(18), 0, 0)
            }
            val entries = ArrayList<Triple<String, Style, () -> Unit>>()
            pos?.let { (t, l) ->
                val danger = DESTRUCTIVE.any { t.lowercase().startsWith(it) }
                entries += Triple(t, if (danger) Style.DANGER else Style.PRIMARY) { l?.invoke(this, 2); dismiss() }
            }
            neg?.let { (t, l) -> entries += Triple(t, Style.SOFT) { l?.invoke(this, 1); dismiss() } }
            neutral?.let { (t, l) -> entries += Triple(t, Style.SOFT) { l?.invoke(this, 0); dismiss() } }
            val ordered = if (stacked) entries else entries.reversed()
            ordered.forEachIndexed { i, (t, st, act) ->
                val lp = if (stacked) LinearLayout.LayoutParams(-1, ctx.dp(50)).apply { if (i > 0) topMargin = ctx.dp(8) }
                    else LinearLayout.LayoutParams(0, ctx.dp(52), 1f).apply { if (i > 0) marginStart = ctx.dp(10) }
                box.addView(button(t, st) { act() }, lp)
            }
            card.addView(box)
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

    enum class Style { PRIMARY, TEXT, DANGER, OUTLINE, SOFT }

    private fun button(label: String, style: Style, onClick: () -> Unit) = Companion.button(ctx, label, style, onClick)

    companion object {
        private val DESTRUCTIVE = listOf("supprimer", "effacer", "oublier", "retirer", "fermer l", "quitter", "tout oublier")

        fun sheet(ctx: Context) = Builder(ctx, sheet = true)

        /** Bouton-pilule « pro » réutilisable partout. */
        fun button(ctx: Context, label: String, style: Style, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Ui.BOLD
            setPadding(ctx.dp(18), ctx.dp(10), ctx.dp(18), ctx.dp(10))
            minWidth = ctx.dp(if (style == Style.TEXT) 0 else 96)
            minHeight = ctx.dp(44)
            val r = ctx.dp(16).toFloat()
            when (style) {
                Style.PRIMARY -> { setTextColor(0xFF08111F.toInt()); background = Ui.ripple(Ui.rounded(Ui.ACCENT, r), 0x33000000) }
                Style.DANGER -> { setTextColor(0xFF1A0C09.toInt()); background = Ui.ripple(Ui.rounded(Ui.DANGER, r), 0x33000000) }
                Style.OUTLINE -> { setTextColor(0xFFDDE3EF.toInt()); background = Ui.ripple(Ui.rounded(Ui.SURFACE, r, 0xFF263252.toInt(), ctx.dp(1))) }
                Style.SOFT -> { setTextColor(0xFFDDE3EF.toInt()); typeface = Ui.MEDIUM; background = Ui.ripple(Ui.rounded(Ui.SOFT, r)) }
                Style.TEXT -> { setTextColor(Ui.LAVENDER); background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, r)) }
            }
            setOnClickListener { onClick() }
        }

        /** Ligne de menu : icône dans une pastille teintée, libellé, détail ou chevron à droite. */
        fun row(ctx: Context, emoji: String, label: String, shaded: Boolean, detail: String? = null, labelColor: Int = Ui.TEXT,
                sub: String? = null, onClick: () -> Unit) =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), 0, ctx.dp(14), 0)
                background = Ui.ripple(Ui.rounded(Ui.CARD, ctx.dp(16).toFloat()))
                addView(TextView(ctx).apply {
                    text = emoji; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER
                    background = Ui.rounded(if (labelColor == Ui.DANGER) 0xFF2A1714.toInt() else 0xFF1A2236.toInt(), ctx.dp(10).toFloat())
                }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { marginEnd = ctx.dp(12) })
                val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(TextView(ctx).apply {
                    text = label; setTextColor(labelColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f); typeface = Ui.MEDIUM
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (sub != null) texts.addView(TextView(ctx).apply {
                    text = sub; setTextColor(Ui.MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); typeface = Ui.MEDIUM
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (detail != null) addView(TextView(ctx).apply {
                    text = detail; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Ui.BOLD
                    setTextColor(if (detail.startsWith("✓")) Ui.GREEN else Ui.AMBER)
                    setPadding(ctx.dp(8), 0, 0, 0)
                }) else if (labelColor != Ui.DANGER) addView(TextView(ctx).apply {
                    text = "›"; setTextColor(Ui.FAINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setPadding(ctx.dp(8), 0, 0, ctx.dp(2))
                })
                layoutParams = LinearLayout.LayoutParams(-1, ctx.dp(if (sub == null) 56 else 62)).apply { topMargin = ctx.dp(6) }
                setOnClickListener { onClick() }
            }

        fun section(ctx: Context, label: String) = TextView(ctx).apply {
            text = label.uppercase()
            setTextColor(Ui.CYAN); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); typeface = Ui.BLACK
            letterSpacing = 0.14f
            setPadding(ctx.dp(6), ctx.dp(20), 0, ctx.dp(4))
        }

        /** Tuile « favori » façon Lia. */
        fun tile(ctx: Context, emoji: String, label: String, onClick: () -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.ripple(Ui.rounded(Ui.CARD, ctx.dp(18).toFloat(), Ui.STROKE, ctx.dp(1)))
            addView(TextView(ctx).apply { text = emoji; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); gravity = Gravity.CENTER })
            addView(TextView(ctx).apply {
                text = label; setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Ui.BOLD
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
