package com.application.nox

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.application.nox.Ui.dp

/**
 * Le mode vocal, épuré : le neurone animé au centre, sur fond de nuit, et au-dessus un seul mot
 * qui dit ce que fait Nox (écoute en bleu, réflexion en violet, parole en rose). Toucher le neurone : parler ou
 * l'interrompre ; appui long : mains libres ; geste retour : revenir au fil.
 */
class VoicePage(private val a: MainActivity, root: FrameLayout) {

    private val page = FrameLayout(a).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0xFF0B0820.toInt(), 0xFF020409.toInt())
            gradientRadius = a.resources.displayMetrics.heightPixels * 0.5f
            setGradientCenter(0.5f, 0.46f)
        }
        visibility = View.GONE
        isClickable = true
    }
    val neuron = NeuronView(a, NeuronView.Style.VOICE).apply { contentDescription = "Nox : toucher pour parler" }
    var isOpen = false; private set

    /** Le mot d'état, au-dessus du neurone : couleur de l'onde, fondu à chaque changement, respiration douce. */
    private val word = TextView(a).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        letterSpacing = 0.18f
        setShadowLayer(a.dp(18).toFloat(), 0f, 0f, 0x664FA6FF)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val breath = ObjectAnimator.ofFloat(word, View.ALPHA, 1f, 0.55f).apply {
        duration = 1400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
    }
    private var shown: NeuronView.State? = null
    private val backCb by lazy { android.window.OnBackInvokedCallback { a.closeVoicePage() } }

    init {
        val size = (a.resources.displayMetrics.widthPixels * 0.97f).toInt()
        page.addView(neuron, FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER))
        val top = ((a.resources.displayMetrics.heightPixels - size) / 2 - a.dp(10)).coerceAtLeast(a.dp(56))
        page.addView(word, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = top })
        showWord(NeuronView.State.IDLE)
        neuron.setOnClickListener { a.onNeuronTap() }
        neuron.setOnLongClickListener { a.toggleHandsFree(); true }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
    }

    fun open() {
        if (isOpen) return
        isOpen = true
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
        neuron.contentDescription = "Nox : $text"
        showWord(state)
    }

    private fun showWord(state: NeuronView.State) {
        if (state == shown) return
        shown = state
        val (label, color) = when (state) {
            NeuronView.State.LISTENING -> "j'écoute" to 0xFF60A5FA.toInt()
            NeuronView.State.THINKING -> "je réfléchis" to 0xFFB57BFA.toInt()
            NeuronView.State.SPEAKING -> "je parle" to 0xFFF0ABFC.toInt()
            NeuronView.State.OFF -> "je me réveille" to 0xFF6F7890.toInt()
            NeuronView.State.IDLE -> "touche pour parler" to 0xFF9AA3B5.toInt()
        }
        breath.cancel()
        word.animate().cancel()
        word.animate().alpha(0f).translationY(-a.dp(6).toFloat()).setDuration(140).withEndAction {
            word.text = label
            word.setTextColor(color)
            word.setShadowLayer(a.dp(18).toFloat(), 0f, 0f, (color and 0x00FFFFFF) or 0x66000000)
            word.translationY = a.dp(6).toFloat()
            word.animate().alpha(1f).translationY(0f).setDuration(260).withEndAction {
                if (state == NeuronView.State.LISTENING || state == NeuronView.State.THINKING || state == NeuronView.State.SPEAKING) breath.start()
            }.start()
        }.start()
    }

    fun caption(text: String) { neuron.contentDescription = "Nox : $text" }

    fun heard(text: String) {}

    fun setLevel(v: Float) = neuron.setLevel(v)
}
