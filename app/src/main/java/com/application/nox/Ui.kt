package com.application.nox

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Petits outils pour construire l'interface en code (aucun XML de layout, aucune dépendance). */
object Ui {
    // Palette inspirée de Lia (bleu nuit profond), avec l'accent violet de Nox
    const val BG = 0xFF0A1120.toInt()
    const val SURFACE = 0xFF141C30.toInt()
    const val SURFACE_HI = 0xFF1C2542.toInt()
    const val STROKE = 0xFF243150.toInt()
    const val TEXT = 0xFFEAF1FB.toInt()
    const val MUTED = 0xFF8A97B4.toInt()
    const val USER_TEXT = 0xFFD6E4F5.toInt()
    const val USER_TAG = 0xFFAEC3E0.toInt()
    const val ACCENT = 0xFF8B7CFF.toInt()
    const val CYAN = 0xFF4FE3FF.toInt()
    const val DANGER = 0xFFFF5F7A.toInt()

    fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    fun Context.dp(v: Int): Int = dp(v.toFloat())

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    fun ripple(content: GradientDrawable, rippleColor: Int = 0x33FFFFFF) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    fun Context.text(s: CharSequence = "", size: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
        }

    /** Bouton rond à glyphe (icônes en Unicode : pas de ressources à gérer). */
    fun Context.iconButton(glyph: String, sizeDp: Int = 42, bg: Int = SURFACE, fg: Int = TEXT, onClick: (View) -> Unit) =
        TextView(this).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = ripple(rounded(bg, dp(sizeDp) / 2f))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            setOnClickListener(onClick)
        }

    fun Context.pillButton(label: String, filled: Boolean = true, onClick: (View) -> Unit) =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (filled) Color.WHITE else ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = ripple(
                if (filled) rounded(ACCENT, dp(20).toFloat())
                else rounded(Color.TRANSPARENT, dp(20).toFloat(), ACCENT, dp(1))
            )
            setOnClickListener(onClick)
        }

    fun Context.sectionTitle(s: String) = text(s.uppercase(), 11.5f, MUTED, bold = true).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(18), 0, dp(6))
    }
}
