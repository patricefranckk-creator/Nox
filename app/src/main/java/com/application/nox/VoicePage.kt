package com.application.nox

import android.view.View
import android.widget.FrameLayout

/**
 * Le mode vocal, épuré : rien que le neurone animé au centre, sur fond de nuit. L'état se lit dans
 * ses ondes (bleu : écoute, violet : réflexion, rose : parole). Toucher le neurone : parler ou
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
    private val backCb by lazy { android.window.OnBackInvokedCallback { a.closeVoicePage() } }

    init {
        val size = (a.resources.displayMetrics.widthPixels * 0.97f).toInt()
        page.addView(neuron, FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER))
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
    }

    fun caption(text: String) { neuron.contentDescription = "Nox : $text" }

    fun heard(text: String) {}

    fun setLevel(v: Float) = neuron.setLevel(v)
}
