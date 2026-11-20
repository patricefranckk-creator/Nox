package com.application.nox

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.application.nox.Ui.dp

/**
 * La page vocale de Lia : le grand neurone au centre (vert à l'écoute, bleu en réflexion,
 * ambré quand Nox parle), une légende discrète, et le bouton rond pour revenir au fil.
 */
class VoicePage(private val a: MainActivity, root: FrameLayout) {

    private val page = FrameLayout(a).apply {
        setBackgroundColor(Ui.BG)
        visibility = View.GONE
        isClickable = true
    }
    val neuron = NeuronView(a, NeuronView.Style.VOICE)
    private val caption = TextView(a).apply {
        setTextColor(0x8CEEF2FA.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Ui.BOLD
        gravity = Gravity.CENTER
        setPadding(a.dp(32), 0, a.dp(32), 0)
    }
    private val heard = TextView(a).apply {
        setTextColor(Ui.USER_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); typeface = Ui.BLACK
        gravity = Gravity.CENTER
        setPadding(a.dp(28), 0, a.dp(28), 0)
        maxLines = 4
    }
    var isOpen = false; private set
    private val backCb by lazy { android.window.OnBackInvokedCallback { a.closeVoicePage() } }

    init {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val size = (a.resources.displayMetrics.widthPixels * 0.97f).toInt()
        col.addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(neuron, LinearLayout.LayoutParams(size, size))
        col.addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(18) })
        col.addView(heard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(14) })
        col.addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        val back = ImageView(a).apply {
            setImageResource(a.resources.getIdentifier("ic_back", "drawable", a.packageName))
            setColorFilter(Ui.TEXT)
            contentDescription = "Retour à la conversation"
            setPadding(a.dp(19), a.dp(19), a.dp(19), a.dp(19))
            background = Ui.ripple(Ui.rounded(0, a.dp(32).toFloat(), 0x38EEF2FA, a.dp(1.5f)))
            setOnClickListener { a.closeVoicePage() }
        }
        col.addView(back, LinearLayout.LayoutParams(a.dp(64), a.dp(64)).apply { bottomMargin = a.dp(40) })
        page.addView(col, FrameLayout.LayoutParams(-1, -1))
        neuron.setOnClickListener { a.onNeuronTap() }
        neuron.setOnLongClickListener { a.toggleHandsFree(); true }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        heard.text = ""
        page.visibility = View.VISIBLE
        page.alpha = 0f
        neuron.scaleX = 0.85f; neuron.scaleY = 0.85f
        page.animate().alpha(1f).setDuration(220).start()
        neuron.animate().scaleX(1f).scaleY(1f).setDuration(320).start()
        if (android.os.Build.VERSION.SDK_INT >= 33)
            a.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCb)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        if (android.os.Build.VERSION.SDK_INT >= 33) a.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCb)
        page.animate().alpha(0f).setDuration(180).withEndAction { page.visibility = View.GONE }.start()
    }

    fun set(state: NeuronView.State, text: String) {
        neuron.state = state
        caption.text = text
    }

    fun caption(text: String) { caption.text = text }

    /** Ce qui a été entendu (reconnaissance Android) ou le début de la réponse. */
    fun heard(text: String) { heard.text = text }

    fun setLevel(v: Float) = neuron.setLevel(v)
}
