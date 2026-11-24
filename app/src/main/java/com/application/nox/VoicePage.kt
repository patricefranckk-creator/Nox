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
    /** Visage holographique 2,5D et avatar 3D, à la place du neurone selon le choix (boutons en bas). */
    val face = FaceView(a).apply { contentDescription = "Nox : toucher pour parler" }
    val avatar = Avatar3DView(a).apply { contentDescription = "Nox : toucher pour parler" }
    private val looks = android.widget.LinearLayout(a).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        background = Ui.rounded(0xFF101A2E.toInt(), a.dp(22).toFloat())
        setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
    }
    private val lookNeuron = lookButton("Neurone")
    private val lookFace = lookButton("Visage")
    private val look3d = lookButton("3D")
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
        page.addView(face, FrameLayout.LayoutParams(size, (size * 1.2f).toInt(), android.view.Gravity.CENTER))
        page.addView(avatar, FrameLayout.LayoutParams(size, (size * 1.2f).toInt(), android.view.Gravity.CENTER))
        looks.addView(lookNeuron); looks.addView(lookFace); looks.addView(look3d)
        page.addView(looks, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = a.dp(36) })
        lookNeuron.setOnClickListener { setLook("neurone") }
        lookFace.setOnClickListener { setLook("visage") }
        look3d.setOnClickListener { setLook("avatar3d") }
        for (v in listOf(face, avatar)) {
            v.setOnClickListener { a.onNeuronTap() }
            v.setOnLongClickListener { a.toggleHandsFree(); true }
        }
        avatar.onFail = {
            a.toast("Avatar 3D indisponible sur cet appareil : visage 2,5D à la place")
            setLook("visage")
        }
        applyLook()
        val top = ((a.resources.displayMetrics.heightPixels - size * 1.2f).toInt() / 2 - a.dp(30)).coerceAtLeast(a.dp(48))
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
        val shown = current()
        shown.scaleX = 0.85f; shown.scaleY = 0.85f
        page.animate().alpha(1f).setDuration(220).start()
        shown.animate().scaleX(1f).scaleY(1f).setDuration(320).start()
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
        FaceBrain.state = state
        neuron.contentDescription = "Nox : $text"
        face.contentDescription = "Nox : $text"
        avatar.contentDescription = "Nox : $text"
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

    fun caption(text: String) {
        neuron.contentDescription = "Nox : $text"; face.contentDescription = "Nox : $text"; avatar.contentDescription = "Nox : $text"
    }

    fun heard(text: String) {}

    fun setLevel(v: Float) { neuron.setLevel(v); FaceBrain.setLevel(v) }

    // ------------------------------------------------------------ neurone ou visage

    private fun lookButton(label: String) = TextView(a).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Ui.BOLD
        minHeight = a.dp(44); minWidth = a.dp(84)
        setPadding(a.dp(16), 0, a.dp(16), 0)
        isClickable = true; isFocusable = true
    }

    private fun setLook(look: String) {
        if (a.prefs.look == look) return
        a.prefs.look = look
        NoxLog.i("VISAGE", "Apparence : " + when (look) { "avatar3d" -> "avatar 3D"; "visage" -> "visage 2,5D"; else -> "neurone" })
        applyLook()
    }

    private fun current(): View = when (a.prefs.look) { "avatar3d" -> avatar; "visage" -> face; else -> neuron }

    private fun applyLook() {
        val look = a.prefs.look
        face.visibility = if (look == "visage") View.VISIBLE else View.GONE
        avatar.visibility = if (look == "avatar3d") View.VISIBLE else View.GONE
        neuron.visibility = if (look == "neurone") View.VISIBLE else View.GONE
        for ((v, on) in listOf(lookNeuron to (look == "neurone"), lookFace to (look == "visage"), look3d to (look == "avatar3d"))) {
            v.background = if (on) Ui.rounded(0xFF4FA6FF.toInt(), a.dp(20).toFloat()) else null
            v.setTextColor(if (on) 0xFF06101E.toInt() else 0xFFA9B4C9.toInt())
            v.contentDescription = (if (on) "Apparence choisie : " else "Choisir l'apparence : ") + v.text
        }
    }
}
