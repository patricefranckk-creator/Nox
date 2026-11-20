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
    // Palette reprise de Lia
    const val BG = 0xFF0B1120.toInt()
    const val SURFACE = 0xFF161E2E.toInt()
    const val SURFACE_HI = 0xFF1B2D4D.toInt()
    const val STROKE = 0xFF2A3456.toInt()
    const val TEXT = 0xFFEEF2FA.toInt()
    const val MUTED = 0xFF9AA3B5.toInt()
    const val FAINT = 0xFF4D5568.toInt()
    const val USER_TEXT = 0xFFF6C08A.toInt()
    const val USER_TAG = 0xFFFFB36B.toInt()
    const val ACCENT = 0xFF4FA6FF.toInt()          // bleu de Lia
    const val LAVENDER = 0xFFB9B0FF.toInt()        // liens et « Fermer »
    const val CYAN = 0xFF7FD8F0.toInt()
    const val GREEN = 0xFF7ED36B.toInt()
    const val AMBER = 0xFFFFC170.toInt()
    const val DANGER = 0xFFE8806E.toInt()
    const val TIMELINE = 0xFF2A4473.toInt()
    // Popups et menus (gris violacé de Lia)
    const val SHEET = 0xFF2A2933.toInt()
    const val CARD = 0xFF35343E.toInt()
    const val TILE = 0xFF1A2038.toInt()
    const val TILE_STROKE = 0xFF2E3A63.toInt()

    /** Police arrondie et grasse, comme dans Lia. */
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BLACK: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

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
            typeface = if (bold) BOLD else MEDIUM
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

    fun Context.pillButton(label: String, filled: Boolean = true, onClick: (View) -> Unit): TextView =
        NoxDialog.button(this, label, if (filled) NoxDialog.Style.PRIMARY else NoxDialog.Style.OUTLINE) {}.apply {
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
        }

    /** Champ de saisie sombre et arrondi. */
    fun Context.field(hintText: String, lines: Int = 1) = android.widget.EditText(this).apply {
        hint = hintText
        setTextColor(TEXT); setHintTextColor(FAINT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        typeface = MEDIUM
        background = rounded(CARD, dp(16).toFloat(), 0x22FFFFFF, dp(1))
        setPadding(dp(16), dp(12), dp(16), dp(12))
        if (lines > 1) { minLines = lines; gravity = Gravity.TOP } else isSingleLine = true
    }

    /** Interrupteur aux couleurs de Nox. */
    @Suppress("DEPRECATION")
    fun Context.switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = android.widget.Switch(this).apply {
        text = label
        setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = MEDIUM
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(ACCENT, 0xFFB8BCC8.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(0x884FA6FF.toInt(), 0x55FFFFFF))
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    fun Context.sectionTitle(s: String) = text(s.uppercase(), 11.5f, MUTED, bold = true).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(18), 0, dp(6))
    }
}
