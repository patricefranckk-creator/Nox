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
    /** Le portrait animé (marionnette 2D d'après les images importées). */
    val portrait = PortraitView(a).apply { contentDescription = "Nox : toucher pour parler" }
    /** L'hologramme : vidéos par état (pack livré dans l'appli, remplaçable), indépendant du portrait. */
    val holo = HoloView(a).apply { contentDescription = "Nox : toucher pour parler" }
    private val looks = android.widget.LinearLayout(a).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        background = Ui.rounded(0xFF101A2E.toInt(), a.dp(22).toFloat())
        setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
    }
    /** Sous-titres : ce que Nox a compris, puis sa réponse, ou un message s'il n'a rien entendu. */
    private val subtitle = TextView(a).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setLineSpacing(0f, 1.25f)
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.START
        setPadding(a.dp(24), 0, a.dp(24), 0)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val lookNeuron = lookButton("Neurone")
    private val lookFace = lookButton("Visage")
    private val look3d = lookButton("3D")
    private val lookPortrait = lookButton("Portrait")
    private val lookHolo = lookButton("Hologramme")
    var isOpen = false; private set

    /** Le mot d'état, au-dessus du neurone : couleur de l'onde, fondu à chaque changement, respiration douce. */
    private val word = TextView(a).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(14, 26, 1, TypedValue.COMPLEX_UNIT_SP)
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        letterSpacing = 0.18f
        setPadding(a.dp(16), 0, a.dp(16), 0)
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
        page.addView(portrait, FrameLayout.LayoutParams(size, (size * 1.2f).toInt(), android.view.Gravity.CENTER))
        page.addView(holo, FrameLayout.LayoutParams(size, (size * 1.2f).toInt(), android.view.Gravity.CENTER))
        looks.addView(lookNeuron); looks.addView(lookFace); looks.addView(look3d); looks.addView(lookPortrait); looks.addView(lookHolo)
        page.addView(looks, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = a.dp(36); leftMargin = a.dp(12); rightMargin = a.dp(12) })
        page.addView(subtitle, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = a.dp(36 + 52 + 18) })
        // les sous-titres restent toujours au-dessus des onglets, quelle que soit la taille de police
        looks.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val lp = subtitle.layoutParams as FrameLayout.LayoutParams
            val want = (bottom - top) + a.dp(36) + a.dp(14)
            if (lp.bottomMargin != want) { lp.bottomMargin = want; subtitle.layoutParams = lp }
        }
        lookNeuron.setOnClickListener { setLook("neurone") }
        lookFace.setOnClickListener { setLook("visage") }
        look3d.setOnClickListener { setLook("avatar3d") }
        lookPortrait.setOnClickListener {
            if (PortraitAssets.ready(a)) setLook("portrait")
            else {
                a.toast("Choisis le ZIP (ou les PNG) des images de Nox")
                PortraitAssets.pick(a)
            }
        }
        lookPortrait.setOnLongClickListener { PortraitAssets.pick(a); true }
        lookHolo.setOnClickListener {
            if (HoloAssets.ready(a)) { setLook("holo"); holo.activate() }
            else {
                a.toast("Choisis le ZIP des vidéos de l'hologramme")
                HoloAssets.pick(a)
            }
        }
        lookHolo.setOnLongClickListener { HoloAssets.pick(a); true }
        holo.onFail = { msg ->
            a.toast(msg)
            setLook("portrait".takeIf { PortraitAssets.ready(a) } ?: "visage")
        }
        portrait.onFail = { msg ->
            a.toast(msg)
            setLook("visage")
        }
        for (v in listOf(face, avatar, portrait, holo)) {
            v.setOnClickListener { a.onNeuronTap() }
            v.setOnLongClickListener { a.toggleHandsFree(); true }
        }
        avatar.onFail = {
            a.toast("Avatar 3D indisponible sur cet appareil : visage 2,5D à la place")
            setLook("visage")
        }
        applyLook()
        val top = ((a.resources.displayMetrics.heightPixels - size * 1.2f).toInt() / 2 - a.dp(30)).coerceAtLeast(a.dp(48))
        page.addView(word, FrameLayout.LayoutParams(-1, a.dp(40), Gravity.TOP).apply { topMargin = top })
        // le mot d'état se pose juste au-dessus du visage, sans le recouvrir
        page.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val faceTop = current().top
            if (faceTop <= 0) return@addOnLayoutChangeListener
            val lp = word.layoutParams as FrameLayout.LayoutParams
            val want = (faceTop - lp.height - a.dp(4)).coerceAtLeast(a.dp(28))
            if (lp.topMargin != want) word.post { lp.topMargin = want; word.layoutParams = lp }
        }
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
        holo.state = state
        neuron.contentDescription = "Nox : $text"
        face.contentDescription = "Nox : $text"
        avatar.contentDescription = "Nox : $text"
        portrait.contentDescription = "Nox : $text"
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
        neuron.contentDescription = "Nox : $text"; face.contentDescription = "Nox : $text"
        avatar.contentDescription = "Nox : $text"; portrait.contentDescription = "Nox : $text"
    }

    /** Ce que la reconnaissance vocale entend (en direct), puis la phrase comprise. */
    fun heard(text: String, final: Boolean = false) {
        if (text.isBlank()) return
        subtitle.setTextColor(if (final) 0xFFE8F4FF.toInt() else 0xFF9FB7D6.toInt())
        subtitle.text = "« ${text.trim()} »"
    }

    /** La réponse de Nox, au fil de l'eau (fin de la réponse si elle est longue). */
    fun answer(text: String) {
        if (!isOpen) return
        subtitle.setTextColor(0xFFBFE6FF.toInt())
        subtitle.text = text.replace(Regex("[*#`_]"), "").replace(Regex("\\s+"), " ").trim()
    }

    /** Message court : rien entendu, erreur de micro, erreur du modèle. */
    fun notice(text: String) {
        subtitle.setTextColor(0xFFF2B36B.toInt())
        subtitle.text = text
    }

    fun setLevel(v: Float) { neuron.setLevel(v); FaceBrain.setLevel(v) }

    // ------------------------------------------------------------ neurone ou visage

    /** Cinq onglets qui tiennent toujours sur une ligne : largeur partagée, texte réduit si la police système est grande. */
    private fun lookButton(label: String) = TextView(a).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = Ui.BOLD
        maxLines = 1
        minHeight = a.dp(44)
        setPadding(a.dp(4), 0, a.dp(4), 0)
        setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
        layoutParams = android.widget.LinearLayout.LayoutParams(0, a.dp(44), 1f)
        isClickable = true; isFocusable = true
    }

    private fun setLook(look: String) {
        if (a.prefs.look == look) return
        a.prefs.look = look
        NoxLog.i("VISAGE", "Apparence : " + when (look) { "holo" -> "hologramme"; "portrait" -> "portrait animé"; "avatar3d" -> "avatar 3D"; "visage" -> "visage 2,5D"; else -> "neurone" })
        applyLook()
    }

    private fun current(): View = when (a.prefs.look) { "holo" -> holo; "portrait" -> portrait; "avatar3d" -> avatar; "visage" -> face; else -> neuron }

    /** Images du portrait importées : on l'affiche et le rendu repart avec elles. */
    fun portraitReady() {
        a.prefs.look = "portrait"
        applyLook()
        portrait.reload()
    }

    /** Vidéos de l'hologramme importées : on l'affiche avec le nouveau pack. */
    fun holoReady() {
        a.prefs.look = "holo"
        holo.reload()
        applyLook()
    }

    private fun applyLook() {
        val look = a.prefs.look.let {
            when {
                it == "portrait" && !PortraitAssets.ready(a) -> "visage"
                it == "holo" && !HoloAssets.ready(a) -> "visage"
                else -> it
            }
        }
        holo.visibility = if (look == "holo") View.VISIBLE else View.GONE
        portrait.visibility = if (look == "portrait") View.VISIBLE else View.GONE
        face.visibility = if (look == "visage") View.VISIBLE else View.GONE
        avatar.visibility = if (look == "avatar3d") View.VISIBLE else View.GONE
        neuron.visibility = if (look == "neurone") View.VISIBLE else View.GONE
        for ((v, on) in listOf(lookNeuron to (look == "neurone"), lookFace to (look == "visage"), look3d to (look == "avatar3d"), lookPortrait to (look == "portrait"), lookHolo to (look == "holo"))) {
            v.background = if (on) Ui.rounded(0xFF4FA6FF.toInt(), a.dp(20).toFloat()) else null
            v.setTextColor(if (on) 0xFF06101E.toInt() else 0xFFA9B4C9.toInt())
            v.contentDescription = (if (on) "Apparence choisie : " else "Choisir l'apparence : ") + v.text
        }
    }
}
