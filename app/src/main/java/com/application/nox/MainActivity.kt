package com.application.nox

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.application.nox.Ui.dp
import com.application.nox.Ui.text

class MainActivity : Activity(), VoiceIn.Listener, VoiceOut.Listener, AudioCapture.Listener {

    // Le cœur vit dans Nox (il survit à l'écran) ; l'activité n'en est que la vitrine.
    val prefs get() = Nox.prefs
    val store get() = Nox.store
    val engine get() = Nox.engine
    val skills get() = Nox.skills
    val library get() = Nox.library
    val messages get() = Nox.messages
    private val chats get() = Nox.chats

    lateinit var voiceOut: VoiceOut
    lateinit var gauges: GaugePanel
    private lateinit var voicePage: VoicePage
    lateinit var cloudPage: CloudPage
    private lateinit var swipe: android.view.GestureDetector
    private var voiceIn: VoiceIn? = null
    private var capture: AudioCapture? = null
    private var pendingImage: ByteArray? = null
    private var cameraUri: Uri? = null
    private var embedTried = false
    private var listenWhenReady = false

    /** Une écoute est en cours (reconnaissance Android OU oreille native Gemma). */
    private val listeningNow get() = voiceIn?.active == true || capture?.active == true

    private val main = Handler(Looper.getMainLooper())

    // Vues
    private lateinit var root: FrameLayout
    private lateinit var header: LinearLayout
    private lateinit var neuron: NeuronView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var gaugeBtn: FrameLayout
    private lateinit var gaugeIcon: ImageView
    private lateinit var muteIcon: ImageView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var statsLine: TextView
    private lateinit var attachRow: LinearLayout
    private lateinit var attachLabel: TextView
    private lateinit var input: EditText
    private lateinit var actionIcon: ImageView

    private var streamingView: TurnView? = null
    private var turnFromVoice = false
    private var mode = NeuronView.State.OFF

    // ================================================================ cycle de vie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Nox.init(this)
        Nox.ui = this
        voiceOut = VoiceOut(this, prefs, this)
        voiceOut.onMuted = {
            toast("Nox parle, mais le volume des médias est à zéro : monte le son")
            if (::voicePage.isInitialized) voicePage.notice("🔇 Volume des médias à zéro : monte le son pour entendre Nox")
        }

        window.setDecorFitsSystemWindows(false)   // bord à bord partout, on gère les marges nous-mêmes
        root = buildUi()
        setContentView(root)
        gauges = GaugePanel(this, root) { header.bottom }
        gauges.onChange = { open -> styleGaugeButton(open) }
        voicePage = VoicePage(this, root)
        cloudPage = CloudPage(this, root)
        // Glisser vers la gauche : « Nox en ligne » · vers la droite : retour au chat local
        swipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || voicePage.isOpen) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (kotlin.math.abs(dx) < dp(110) || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 2) return false
                if (dx < 0 && !cloudPage.isOpen) { cloudPage.open(); return true }
                if (dx > 0 && cloudPage.isOpen) { cloudPage.close(); return true }
                return false
            }
        })
        setMode(NeuronView.State.OFF, "")

        if (messages.isEmpty()) addTurn(ChatMsg(false, welcomeText()), save = false, register = false)
        else messages.forEach { addTurn(it, save = false, register = false) }
        scrollToEnd()

        val fromAssist = intent?.getBooleanExtra(EXTRA_VOICE, false) == true
        if (savedInstanceState == null && !fromAssist) showSplash()

        when {
            engine.ready -> onReady()
            Nox.loading -> setMode(NeuronView.State.OFF, "réveil de Nox…")
            else -> bootModel()
        }
        handleIntent(intent)
        updateKeepScreen()
        if (!prefs.permsShown) main.postDelayed({ prefs.permsShown = true; PermissionsDialog(this).show() }, if (fromAssist) 0L else 1600L)
        else askEssentials()
    }

    /** Si un téléchargement se termine pendant que Nox dort, on le branche automatiquement. */
    private val autoPlug = object : Runnable {
        override fun run() {
            store.pruneCustom().forEach { toast("Échec du téléchargement de $it (lien privé ou invalide ?)") }
            if (!embedTried && store.embedFile() != null) { embedTried = true; library.enableSemantic() }
            if (!engine.ready && !Nox.loading && (prefs.modelPath.isBlank() || !java.io.File(prefs.modelPath).exists())) {
                val ready = store.localModels().firstOrNull()
                if (ready != null) {
                    prefs.modelPath = ready.absolutePath
                    toast("Modèle téléchargé, réveil de Nox !")
                    loadModel()
                }
            }
            main.postDelayed(this, 3000)
        }
    }

    override fun onResume() {
        super.onResume()
        Nox.ui = this
        Bubble.hide()
        main.removeCallbacks(autoPlug)
        main.postDelayed(autoPlug, 3000)
        PermissionsDialog.current?.refresh()
    }

    private fun stopListening() {
        voiceIn?.stop()
        capture?.cancel()
    }

    override fun onPause() {
        main.removeCallbacks(autoPlug)
        super.onPause()
        stopListening()
        chats.save(messages)
        // Nox passe derrière une autre appli : la bulle reste à portée de doigt
        if (prefs.bubble && prefs.background && engine.ready && !isFinishing && !isChangingConfigurations) {
            Bubble.show(this)
            Bubble.state(mode)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        voiceIn?.release()
        capture?.cancel()
        voiceOut.release()
        if (Nox.ui === this) Nox.ui = null
        if (isFinishing) Bubble.hide()
        super.onDestroy()
        // Sans arrière-plan, quitter l'écran libère vraiment le modèle
        if (isFinishing && !isChangingConfigurations && !prefs.background) Nox.shutdown()
    }

    /** « Fermer l'app » du menu : tout s'arrête, modèle compris. */
    fun closeApp() {
        Bubble.hide()
        finishAndRemoveTask()
        main.postDelayed({ Nox.shutdown() }, 250)
    }

    // ================================================================ interface

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ---- En-tête : neurone (menu), nom + état, jauges, voix
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        neuron = NeuronView(this, NeuronView.Style.HEADER).apply {
            badge = true
            contentDescription = "Menu"
            setOnClickListener { MenuSheet(this@MainActivity).show() }
            setOnLongClickListener { openVoicePage(listen = true); true }
        }
        header.addView(neuron, LinearLayout.LayoutParams(dp(60), dp(60)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        titles.addView(TextView(this).apply {
            text = "Nox"; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Ui.BLACK
        })
        val status = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this)
        status.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(6) })
        statusText = TextView(this).apply {
            setTextColor(Ui.MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Ui.BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        status.addView(statusText)
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        gaugeBtn = FrameLayout(this).apply {
            contentDescription = "Jauges"
            setOnClickListener { gauges.toggle() }
        }
        gaugeIcon = NoxDialog.icon(this, "ic_gauge", Ui.TEXT, 24)
        gaugeBtn.addView(gaugeIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        gaugeBtn.addView(View(this).apply { background = Ui.rounded(0xFF5FD39A.toInt(), dp(4).toFloat()) },
            FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); marginEnd = dp(6) })
        header.addView(gaugeBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        styleGaugeButton(false)

        muteIcon = NoxDialog.icon(this, "ic_volume", Ui.TEXT, 24)
        val muteBtn = FrameLayout(this).apply {
            background = Ui.ripple(Ui.rounded(Ui.SURFACE, dp(14).toFloat(), Ui.STROKE, dp(1)))
            setOnClickListener { toggleMute() }
            addView(muteIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        header.addView(muteBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
        styleMute()
        col.addView(header)
        col.addView(View(this).apply { setBackgroundColor(0xFF141B2B.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))

        // ---- Le fil : ligne verticale fixe, tours de parole par-dessus
        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(12))
        }
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        statsLine = text("", 11f, Ui.FAINT).apply { gravity = Gravity.CENTER; setPadding(dp(20), 0, dp(20), dp(2)); typeface = Ui.BOLD }
        col.addView(statsLine)

        attachRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(58), dp(4), dp(16), dp(2))
            visibility = View.GONE
        }
        attachLabel = text("photo jointe", 13f, Ui.CYAN, bold = true)
        attachRow.addView(attachLabel, LinearLayout.LayoutParams(0, -2, 1f))
        attachRow.addView(text("retirer", 13f, Ui.LAVENDER, bold = true).apply {
            setPadding(dp(10), dp(6), dp(4), dp(6)); setOnClickListener { clearAttachment() }
        })
        col.addView(attachRow)

        // ---- Bas : photo (appui long : galerie), champ arrondi, gros bouton micro
        col.addView(View(this).apply { setBackgroundColor(0xFF141B2B.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        bar.addView(FrameLayout(this).apply {
            contentDescription = "Prendre une photo (appui long : choisir une image)"
            background = Ui.ripple(Ui.rounded(Ui.SURFACE, dp(14).toFloat(), Ui.STROKE, dp(1)))
            addView(NoxDialog.icon(this@MainActivity, "ic_camera", Ui.MUTED, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { takePhoto() }
            setOnLongClickListener { pickImage(); true }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        input = EditText(this).apply {
            hint = "Demande à Nox…"
            setHintTextColor(0xFF6E7891.toInt())
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Ui.MEDIUM
            background = Ui.rounded(Ui.SURFACE, dp(24).toFloat(), Ui.STROKE, dp(1))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(48)
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refreshAction()
            })
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val actionBtn = ImageView(this).apply {
            setImageResource(resources.getIdentifier("ic_mic", "drawable", packageName))
            setColorFilter(0xFF08111F.toInt())
            contentDescription = "Parler"
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            background = Ui.ripple(Ui.rounded(Ui.ACCENT, dp(26).toFloat()), 0x33000000)
            setOnClickListener { onAction() }
        }
        actionIcon = actionBtn
        bar.addView(actionBtn, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(10) })
        col.addView(bar)

        // Bord à bord (Android 15+) : on respecte barres système et clavier
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            if (ime.bottom > 0) scrollToEnd()
            insets
        }
        return root
    }

    private fun barIcon(name: String, desc: String, size: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(resources.getIdentifier(name, "drawable", packageName))
        setColorFilter(Ui.ACCENT)
        contentDescription = desc
        val pad = (dp(44) - dp(size)) / 2
        setPadding(pad, pad, pad, pad)
        background = Ui.ripple(Ui.rounded(0, dp(22).toFloat()))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun styleGaugeButton(open: Boolean) {
        gaugeBtn.background = Ui.ripple(Ui.rounded(if (open) Ui.SURFACE_HI else Ui.SURFACE, dp(14).toFloat(), Ui.STROKE, dp(1)))
        gaugeIcon.setColorFilter(if (open) Ui.ACCENT else Ui.TEXT)
    }

    private fun styleMute() {
        val on = prefs.autoSpeak
        muteIcon.setImageResource(resources.getIdentifier(if (on) "ic_volume" else "ic_volume_off", "drawable", packageName))
        muteIcon.setColorFilter(if (on) Ui.TEXT else 0xFF6E7891.toInt())
        muteIcon.contentDescription = if (on) "Couper la voix" else "Remettre la voix"
    }

    private fun toggleMute() {
        prefs.autoSpeak = !prefs.autoSpeak
        if (!prefs.autoSpeak) voiceOut.stop()
        styleMute()
        toast(if (prefs.autoSpeak) "Nox lit ses réponses à voix haute" else "Voix coupée : Nox répond par écrit")
    }

    /** Écran de démarrage : le neurone sur fond de nuit, le nom, et la mention du créateur. */
    private fun showSplash() {
        val decor = window.decorView as FrameLayout
        val splash = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0xFF120A2A.toInt(), 0xFF020409.toInt())
                gradientRadius = resources.displayMetrics.heightPixels * 0.55f
                setGradientCenter(0.5f, 0.42f)
            }
            isClickable = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val n = NeuronView(this, NeuronView.Style.SPLASH)
        col.addView(n, LinearLayout.LayoutParams(dp(190), dp(190)))
        col.addView(TextView(this).apply {
            text = "Nox"; setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f); typeface = Ui.BLACK
            letterSpacing = 0.03f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        col.addView(TextView(this).apply {
            text = "réveil du modèle…"; setTextColor(0xFF8E97AB.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Ui.MEDIUM
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val track = FrameLayout(this).apply { background = Ui.rounded(0xFF1A2236.toInt(), dp(2).toFloat()); clipChildren = true }
        val bar = View(this).apply { background = Ui.rounded(0xFFA855F7.toInt(), dp(2).toFloat()) }
        track.addView(bar, FrameLayout.LayoutParams(dp(48), dp(3)))
        col.addView(track, LinearLayout.LayoutParams(dp(120), dp(3)).apply { topMargin = dp(22) })
        bar.translationX = -dp(48).toFloat()
        val loading = android.animation.ObjectAnimator.ofFloat(bar, "translationX", -dp(48).toFloat(), dp(120).toFloat()).apply {
            duration = 1300; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        loading.start()
        splash.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val foot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        foot.addView(TextView(this).apply {
            text = "© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} Patrice KOEHLOFER · Tous droits réservés"
            setTextColor(0xFF8E97AB.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Ui.MEDIUM; gravity = Gravity.CENTER
        })
        foot.addView(TextView(this).apply {
            text = "v${AppInfo.version(this@MainActivity)} · 100 % sur ton téléphone"
            setTextColor(0xFF5A6378.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        splash.addView(foot, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(40) })
        decor.addView(splash, FrameLayout.LayoutParams(-1, -1))
        n.scaleX = 0.6f; n.scaleY = 0.6f; n.alpha = 0f
        n.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(650).start()
        var gone = false
        val hide = {
            if (!gone) {
                gone = true
                splash.animate().alpha(0f).setDuration(320).withEndAction { loading.cancel(); decor.removeView(splash) }.start()
            }
        }
        splash.setOnClickListener { hide() }
        main.postDelayed({ hide() }, SPLASH_MS)
    }

    private val turnActions = object : TurnView.Actions {
        override fun copy(m: ChatMsg) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nox", m.text))
            toast("Copié")
        }
        override fun readAloud(m: ChatMsg) = voiceOut.speakAll(m.text)
        override fun edit(m: ChatMsg) {
            input.setText(m.text.removePrefix("📷 "))
            input.setSelection(input.text.length)
            input.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(input, 0)
        }
        override fun openSources(m: ChatMsg) {
            if (m.sources.isEmpty()) return
            val body = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            m.sources.forEach { s ->
                val url = TurnView.sourceUrl(s)
                body.addView(NoxDialog.row(this@MainActivity, if (url != null) "🌐" else "📚", TurnView.sourceTitle(s), true,
                    sub = url?.let { TurnView.domain(it) } ?: "Ta bibliothèque") {
                    if (url != null) try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { toast("Aucun navigateur") }
                    else LibraryExplorer(this@MainActivity).show()
                })
            }
            NoxDialog.sheet(this@MainActivity).setTitle("Sources").setView(body).show()
        }
        override fun quickReply(text: String) {
            if (engine.busy) return
            turnFromVoice = false
            send(text)
        }
    }

    private fun addTurn(m: ChatMsg, save: Boolean = true, register: Boolean = true): TurnView {
        if (register) messages += m
        val t = TurnView(this, m).apply { actions = turnActions }
        list.addView(t, LinearLayout.LayoutParams(-1, -2))
        if (save) chats.save(messages)
        return t
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    /** Un seul endroit pour l'état : neurone de l'en-tête, page vocale et ligne d'état. */
    private fun setMode(state: NeuronView.State, hint: String) {
        mode = state
        neuron.state = state
        if (::voicePage.isInitialized) voicePage.set(state, hint)
        Bubble.state(state)
        val (dot, label) = when (state) {
            NeuronView.State.OFF -> (if (Nox.loading) Ui.AMBER else Ui.FAINT) to (if (Nox.loading) "réveil…" else "endormi")
            NeuronView.State.IDLE -> 0xFF5FD39A.toInt() to "prêt · ${engine.modelLabel} · ${engine.backendLabel}"
            NeuronView.State.LISTENING -> 0xFF5FD39A.toInt() to "à l'écoute…"
            NeuronView.State.THINKING -> Ui.ACCENT to "réfléchit…"
            NeuronView.State.SPEAKING -> Ui.AMBER to "parle…"
        }
        statusDot.background = Ui.rounded(dot, dp(5).toFloat())
        statusText.text = if (state == NeuronView.State.OFF && hint.isNotBlank()) hint else label
    }

    private fun idleHint() = when {
        !engine.ready -> ""
        interp != null -> "Interprète français ⇄ ${interp?.name} · ${if (interpFromForeign) "j'écoute en ${interp?.name}" else "j'écoute en français"} · dis « stop »"
        prefs.handsFree -> "Mains libres actif · touche le neurone pour parler"
        else -> "Touche le neurone pour parler · appui long : mains libres"
    }

    private fun refreshAction() {
        val generating = engine.busy || routing || voiceOut.speaking
        val (icon, desc) = when {
            generating -> "ic_stop" to "Arrêter"
            input.text.isNotBlank() || pendingImage != null -> "ic_send" to "Envoyer"
            else -> "ic_mic" to "Parler"
        }
        actionIcon.setImageResource(resources.getIdentifier(icon, "drawable", packageName))
        actionIcon.contentDescription = desc
    }

    fun onSources(list: List<String>) {
        streamingView?.setSources(list)
        scrollToEnd()
    }

    fun onToolUsed(label: String) {
        streamingView?.addTool(label) ?: toast(label)
        scrollToEnd()
    }

    // ================================================================ modèle

    fun bootModel() {
        if (prefs.modelPath.isBlank() || !java.io.File(prefs.modelPath).exists()) {
            // Premier lancement : on prend un modèle déjà présent, sinon on ouvre le catalogue
            val local = store.localModels().firstOrNull()
            if (local != null) prefs.modelPath = local.absolutePath
            else {
                setMode(NeuronView.State.OFF, "aucun cerveau installé")
                main.postDelayed({ ModelsDialog(this).show() }, 1800)
                return
            }
        }
        loadModel()
    }

    /** Réglage moteur changé pendant un chargement : on recharge dès qu'il est fini. */
    private var reloadAfterLoad = false

    fun loadModel() {
        if (Nox.loading) {
            reloadAfterLoad = true
            NoxLog.i("MOTEUR", "Changement de moteur demandé pendant le chargement : appliqué dès la fin (${prefs.backend.uppercase()})")
            toast("Nox finit de se charger : le changement sera appliqué juste après")
            return
        }
        Nox.loading = true
        // Garde-fou GPU : la configuration qui a fait planter le pilote est écartée, on tente la suivante, plus légère.
        val pending = prefs.gpuPending
        if (pending.isNotEmpty()) {
            val crash = crashedSince(prefs.loadStartedAt)
            if (crash != null) {
                prefs.gpuBad = prefs.gpuBad + pending
                NoxLog.e("MOTEUR", "Le GPU a planté ($crash) avec $pending : configuration écartée, essai d'une version plus légère · écartées : ${prefs.gpuBad.joinToString()}")
                toast("Le GPU a planté avec $pending : Nox essaie une configuration GPU plus légère")
            } else {
                NoxLog.i("MOTEUR", "Démarrage GPU $pending interrompu sans plantage (fermeture, mise à jour…) : rien d'écarté")
            }
            prefs.gpuPending = ""
        }
        prefs.loadStartedAt = System.currentTimeMillis()
        prefs.loadInProgress = true
        voiceOut.stop(); stopListening()
        setMode(NeuronView.State.OFF, "réveil de Nox…")
        refreshAction()
        engine.load(messages.toList(), onStatus = { s -> Nox.ui?.onLoadStatus(s) }) { ok, msg ->
            Nox.loading = false
            prefs.loadInProgress = false
            if (ok) NoxLog.i("APP", "Nox prêt : $msg") else NoxLog.e("APP", "Chargement raté : $msg")
            if (ok) NoxService.start(Nox.app, "${engine.modelLabel} · ${engine.backendLabel}")
            Nox.ui?.onLoaded(ok, msg)
            if (reloadAfterLoad) { reloadAfterLoad = false; loadModel() }
        }
    }

    /** Le processus est-il mort d'un plantage (natif, mémoire) depuis ce moment ? Renvoie la raison, ou null. */
    private fun crashedSince(t: Long): String? = try {
        val e = getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
        when {
            e == null || e.timestamp < t -> null
            e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif"
            e.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "manque de mémoire"
            e.reason == android.app.ApplicationExitInfo.REASON_CRASH -> "plantage"
            else -> null
        }
    } catch (_: Throwable) { null }

    private fun onLoadStatus(s: String) {
        if (mode == NeuronView.State.OFF) { statusText.text = s; voicePage.caption(s) }
    }

    private fun onLoaded(ok: Boolean, msg: String) {
        if (ok) onReady() else {
            setMode(NeuronView.State.OFF, "modèle non chargé")
            toast(msg)
        }
        refreshAction()
    }

    private fun onReady() {
        setMode(NeuronView.State.IDLE, idleHint())
        if (!NoxService.running) NoxService.start(Nox.app, "${engine.modelLabel} · ${engine.backendLabel}")
        if (listenWhenReady) { listenWhenReady = false; startListening() }
        refreshAction()
    }

    // ================================================================ dialogue

    private fun onAction() {
        when {
            engine.busy || voiceOut.speaking -> interrupt()
            input.text.isNotBlank() || pendingImage != null -> {
                val t = input.text.toString().trim()
                input.setText("")
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
                turnFromVoice = false
                send(t)
            }
            else -> openVoicePage(listen = true)
        }
    }

    /** Page vocale plein écran ; écoute tout de suite si demandé (micro, bouton power). */
    fun openVoicePage(listen: Boolean) {
        gauges.close()
        voicePage.open()
        voicePage.set(mode, when {
            engine.ready -> idleHint()
            Nox.loading -> "Réveil de Nox… j'écoute dès que je suis prêt"
            else -> "Aucun modèle chargé"
        })
        if (!listen) return
        if (engine.ready) { if (!engine.busy && !voiceOut.speaking && !listeningNow) startListening() }
        else listenWhenReady = true
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::swipe.isInitialized && swipe.onTouchEvent(ev)) {
            ev.action = android.view.MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (::voicePage.isInitialized && voicePage.isOpen) closeVoicePage()
        else if (::cloudPage.isInitialized && cloudPage.isOpen) cloudPage.close()
        else super.onBackPressed()
    }

    fun closeVoicePage() {
        if (listeningNow) { stopListening(); if (!engine.busy) setMode(NeuronView.State.IDLE, idleHint()) }
        listenWhenReady = false
        voicePage.close()
        scrollToEnd()
    }

    fun onNeuronTap() {
        when {
            !engine.ready -> if (!Nox.loading) ModelsDialog(this).show()
            engine.busy || voiceOut.speaking -> { interrupt(); startListening() }
            capture?.active == true -> capture?.finish()   // « j'ai fini » : on envoie ce qui a été dit
            voiceIn?.active == true -> { voiceIn?.finish(); setMode(NeuronView.State.THINKING, "Je finis d'écouter…") }
            else -> startListening()
        }
    }

    private fun interrupt() {
        engine.stop()
        voiceOut.stop()
        setMode(NeuronView.State.IDLE, idleHint())
        refreshAction()
    }

    private fun send(text: String) {
        val img = pendingImage
        clearAttachment()
        runTurn(if (img != null && text.isBlank()) "Que vois-tu sur cette photo ?" else text, null, 0L, img)
    }

    private fun sendAudio(wavs: List<ByteArray>, ms: Long) = runTurn(null, wavs, ms)

    /** Un tour de parole complet : tour utilisateur → réflexion → réponse en flux (+ voix). */
    private fun runTurn(text: String?, wav: List<ByteArray>?, ms: Long, image: ByteArray? = null) {
        if (!engine.ready) {
            val m = if (Nox.loading) "Nox se réveille, un instant…" else "Aucun modèle chargé"
            NoxLog.w("TOUR", "Tour ignoré : $m")
            voicePage.notice("⚠ $m"); toast(m); return
        }
        if (engine.busy || routing) {
            NoxLog.w("TOUR", "Tour ignoré : moteur occupé (busy=${engine.busy}, routing=$routing, voix=${wav != null})")
            voicePage.notice("⚠ Je termine encore la réponse précédente · réessaie dans un instant")
            if (!engine.busy) setMode(NeuronView.State.IDLE, idleHint())
            return
        }
        stopListening()
        voiceOut.stop()

        val prior = messages.toList()
        val userMsg = ChatMsg(true, when {
            image != null -> "📷 " + (text?.ifBlank { "Que vois-tu sur cette photo ?" } ?: "")
            text != null -> text
            else -> "🎙 message vocal (${(ms + 500) / 1000} s)"
        }).apply { voice = wav != null || turnFromVoice }
        // les relances de la réponse précédente ne servent plus
        (list.getChildAt(list.childCount - 1) as? TurnView)?.clearSuggestions()
        val userView = addTurn(userMsg)
        val reply = ChatMsg(false, "").apply { voice = prefs.autoSpeak }
        val view = addTurn(reply, save = false)
        streamingView = view
        scrollToEnd()
        setMode(NeuronView.State.THINKING, if (wav != null) "Gemma écoute ta voix…" else "Réflexion…")
        if (text != null) voicePage.heard(text, final = true)
        statsLine.text = ""

        val speak = prefs.autoSpeak
        if (speak) voiceOut.beginStream()

        val listener = object : LlmEngine.StreamListener {
            override fun onChunk(text: String) {
                reply.text += text
                view.render()
                voicePage.answer(reply.text)
                if (speak) voiceOut.feed(text)
                if (scroll.getChildAt(0).bottom - (scroll.height + scroll.scrollY) < dp(140)) scrollToEnd()
            }

            override fun onTranscript(text: String) {
                if (text.isBlank()) return
                userMsg.text = text
                userView.render()
                userView.refreshFooter()
                voicePage.heard(text)
            }

            override fun onContext(titles: List<String>) { view.setSources(titles); scrollToEnd() }

            override fun onSuggestions(list: List<String>) { view.setSuggestions(list); scrollToEnd() }

            override fun onDone(stats: String) {
                if (reply.text.isBlank()) reply.text = "(silence)"
                view.render(); view.refreshFooter()
                statsLine.text = stats
                if (prefs.rememberChats && stats != "interrompu" && !stats.startsWith(LIBRARY_ANSWER)) library.addExchange(userMsg.text.removePrefix("🎙 ").removePrefix("📷 "), reply.text)
                finishTurn(speak)
                if (text != null && image == null) relayIfUnsure(text, reply.text, stats, prior)
            }

            override fun onError(msg: String) {
                voicePage.notice("⚠ $msg")
                reply.text = if (reply.text.isBlank()) "⚠ $msg" else reply.text + "\n\n⚠ $msg"
                view.render(); view.refreshFooter()
                finishTurn(speak)
            }
        }
        when {
            wav != null -> { lastLibrary = null; engine.sendAudio(wav, ms, prior, listener) }
            image != null || text == null -> { lastLibrary = null; engine.send(text ?: "", prior, listener, image) }
            else -> route(text, prior, view, listener, userMsg.voice)
        }
        refreshAction()
    }

    /** Dernière réponse tirée de la bibliothèque (question + passages), pour « plus de détails ». */
    private var lastLibrary: Pair<String, List<Library.Hit>>? = null
    @Volatile private var routing = false
    /** Pages lues par la dernière recherche internet (pour « retiens cette page »). */
    @Volatile private var lastWeb: List<WebSearch.Result> = emptyList()
    /** Mode interprète : langue étrangère active, et sens de la prochaine phrase. */
    @Volatile private var interp: Intents.Lang? = null
    @Volatile private var interpFromForeign = false

    /** Aiguillage d'un message écrit : internet, « plus de détails », bibliothèque d'abord, ou le modèle. */
    /**
     * Image locale : traduction par Gemma (les petits modèles d'image ne lisent que l'anglais), pause de Gemma
     * pour libérer la mémoire, dessin par le moteur de Lia, image dans la galerie et sous la réponse, puis Gemma revient.
     */
    private fun localImage(text: String, subject: String, view: TurnView, l: LlmEngine.StreamListener) {
        val m = LocalImages.chosen()
        if (!LocalImages.ready(m)) {
            main.post {
                routing = false; lastLibrary = null
                l.onChunk("Pour créer des images sans internet, télécharge d'abord un modèle dans Menu › Images locales " +
                    "(le plus rapide : ${LocalImages.MODELS.first().label}, ${LocalImages.MODELS.first().mb} Mo). Avec internet, Nox en ligne le fait aussi par NVIDIA.")
                l.onDone("images locales")
                LocalImages.showDialog(this)
            }
            return
        }
        val request = subject.trim().ifBlank { text.trim() }
        var prompt = request
        if (LocalImages.looksFrench(request) && engine.ready) {
            main.post { view.body.text = "Je traduis ta demande pour le dessin…" }
            val latch = java.util.concurrent.CountDownLatch(1)
            val sb = StringBuilder()
            main.post {
                engine.send(LocalImages.translationInstruction(request), emptyList(), object : LlmEngine.StreamListener {
                    override fun onChunk(text: String) { sb.append(text) }
                    override fun onDone(stats: String) { latch.countDown() }
                    override fun onError(msg: String) { latch.countDown() }
                }, context = "")
            }
            latch.await(45, java.util.concurrent.TimeUnit.SECONDS)
            prompt = LocalImages.cleanTranslation(sb.toString(), request) ?: request
            NoxLog.i("IMAGE", "Demande traduite pour le dessin (${prompt.length} car.)")
        }
        LocalImages.awake(true)
        val paused = java.util.concurrent.CountDownLatch(1)
        main.post {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setMode(NeuronView.State.THINKING, "Je dessine…")
            view.body.text = "Je mets Gemma en pause pour libérer la mémoire…"
            engine.unload { paused.countDown() }
        }
        paused.await(30, java.util.concurrent.TimeUnit.SECONDS)
        val (w, h) = LocalImages.size(text, m.scale)
        val t0 = System.currentTimeMillis()
        var stage = "chargement du modèle"
        val tick = object : Runnable {
            override fun run() {
                view.body.text = "Je dessine (${m.label})… $stage · ${(System.currentTimeMillis() - t0) / 1000} s"
                main.postDelayed(this, 500)
            }
        }
        main.post(tick)
        val r = runCatching {
            val drawn = LocalImages.generate(m, prompt, w, h) { s -> stage = s }
            // Pas assez de pixels en 512 : agrandissement ×4 par Real-ESRGAN (Gemma est encore en pause, la mémoire est libre)
            if (prefs.localImageHd) runCatching { ImageHd.enlarge(drawn, prompt, sourceMax = w * h) { s -> stage = s } }
                .onFailure { NoxLog.w("IMAGE", "Agrandissement HD raté, image gardée en ${w}×$h", it) }.getOrDefault(drawn)
            else drawn
        }
        val hdSize = if (prefs.localImageHd && r.isSuccess) "${w * 4}×${h * 4}" else "$w×$h"
        r.exceptionOrNull()?.let { NoxLog.e("IMAGE", "Dessin local raté (${m.id})", it) }
        val saved = r.getOrNull()?.let { bytes -> runCatching { LocalImages.saveToGallery(bytes) } }
        saved?.exceptionOrNull()?.let { NoxLog.e("IMAGE", "Enregistrement de l'image raté", it) }
        val uri = saved?.getOrNull()
        val sec = (System.currentTimeMillis() - t0) / 1000
        LocalImages.awake(false)
        uri?.let { LocalImages.notifyReady(it, "${m.label} · ${sec / 60} min ${sec % 60} s") }
        main.post {
            main.removeCallbacks(tick)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            routing = false; lastLibrary = null
            if (uri != null) {
                view.setImage(uri.toString())
                // Si l'écran a été recréé pendant le dessin, la nouvelle vue de cette réponse reçoit l'image aussi
                (Nox.ui ?: this).showImage(view.msg)
                l.onChunk("Voici ton image, dessinée sur le téléphone sans internet ($hdSize, ${m.label}). Elle est aussi dans ta galerie, dossier Images/Nox.")
                l.onDone("image locale · ${m.label} · $sec s")
            } else {
                l.onError("Dessin impossible : ${r.exceptionOrNull()?.message ?: saved?.exceptionOrNull()?.message ?: "erreur inconnue"}")
            }
            loadModel()   // Gemma revient
        }
    }

    /** Affiche l'image d'un message dans sa vue actuelle (après une recréation de l'écran). */
    fun showImage(m: ChatMsg) {
        val p = m.imagePath ?: return
        for (i in 0 until list.childCount) (list.getChildAt(i) as? TurnView)?.takeIf { it.msg === m }?.setImage(p)
    }

    /** [spoken] : à l'oral, les fiches ne sont consultées que si on le demande (« regarde dans mes fiches »). */
    private fun route(text: String, prior: List<ChatMsg>, view: TurnView, l: LlmEngine.StreamListener, spoken: Boolean) {
        routing = true
        Thread {
            val toModel: () -> Unit = { main.post { routing = false; lastLibrary = null; engine.send(text, prior, l, hint = Intents.toolHint(text), rag = !spoken); refreshAction() } }
            try {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                interp?.let { lang ->
                    if (Intents.detect(text, hour, interpreting = true)?.kind == Intents.INTERPRETER_END) direct(l, stopInterpreter(), "interprète")
                    else interpret(text, lang, l)
                    return@Thread
                }
                Intents.detect(text, hour)?.let { hit -> special(hit, prior, l); return@Thread }
                // « Crée une image de… » dans le chat local : dessin sur le téléphone, sans internet
                Cloud.imageRequest(text)?.let { subject -> localImage(text, subject, view, l); return@Thread }
                val clock = Router.timeAnswer(text)
                val chain = if (clock == null && prefs.skillsEnabled) Commands.matchAll(text) else null
                if (chain != null) {
                    val t = NoxLog.chrono()
                    val outs = chain.map { c ->
                        try { Nox.skills.all.first { it.name == c.skill }.run(c.args) }
                        catch (e: Throwable) { "${c.label} : impossible (${e.message})" }
                    }
                    NoxLog.p("ROUTE", "${chain.size} commandes directes enchaînées en ${t.ms()} ms : ${chain.joinToString(" → ") { it.skill }}")
                    direct(l, outs.joinToString("\n"), "${chain.size} commandes directes")
                    return@Thread
                }
                val cmd = if (clock == null && prefs.skillsEnabled) Commands.match(text) else null
                val done = cmd?.let { c ->
                    val t = NoxLog.chrono()
                    try { Nox.skills.all.first { it.name == c.skill }.run(c.args).also { NoxLog.p("ROUTE", "Commande directe ${c.skill} ${c.args} en ${t.ms()} ms") } }
                    catch (e: Throwable) { NoxLog.w("ROUTE", "Commande directe ${c.skill} impossible, le modèle prend la main : ${e.message}"); null }
                }
                val web = if (clock == null && done == null && prefs.webSearch) WebSearch.queryOf(text) else null
                when {
                    done != null -> main.post {
                        routing = false
                        lastLibrary = null
                        l.onChunk(done)
                        l.onDone("commande directe")
                    }
                    clock != null -> main.post {
                        routing = false
                        lastLibrary = null
                        NoxLog.i("ROUTE", "Heure/date donnée par l'horloge du téléphone")
                        l.onChunk(clock)
                        l.onDone("horloge du téléphone")
                    }
                    web != null -> {
                        main.post { setMode(NeuronView.State.THINKING, "Recherche sur internet…") }
                        val results = WebSearch.search(web, wiki = WebSearch.wantsWikipedia(text))
                        lastWeb = results.filter { it.text.isNotBlank() }
                        main.post {
                            routing = false
                            lastLibrary = null
                            if (results.isEmpty()) {
                                l.onError("Pas de résultat sur internet pour « $web ». Vérifie la connexion.")
                            } else {
                                view.setSources(results.take(5).map { it.title + TurnView.SEP + it.url })
                                engine.send(text, prior, l, null, WebSearch.contextBlock(web, results, engine.contextBudget(text.length)))
                            }
                            refreshAction()
                        }
                    }
                    lastLibrary != null && Router.isMoreDetails(text) -> {
                        val (q, first) = lastLibrary!!
                        val hits = (first + library.search(q, k = 6)).distinctBy { it.chunk.id }.take(6)
                        main.post {
                            routing = false
                            view.setSources(hits.filter { !it.chunk.source.startsWith("chat:") }.map { it.chunk.title }.distinct())
                            engine.send("Développe en détail : $q", prior, l, null, library.contextBlock(hits, 3200))
                            refreshAction()
                        }
                    }
                    Intents.libraryQuery(text) != null -> {
                        val q = Intents.libraryQuery(text)!!
                        val hits = library.search(q, k = 4).filter { !it.chunk.source.startsWith("chat:") }
                        NoxLog.i("RAG", "Fiches demandées (« $q ») : ${hits.size} trouvée(s)" + hits.joinToString("") { " | ${it.chunk.title.take(30)}" })
                        main.post {
                            routing = false
                            lastLibrary = q to hits
                            if (hits.isEmpty()) {
                                voicePage.notice("Rien dans tes fiches sur « $q » · Gemma répond seul")
                                engine.send(text, prior, l, hint = Intents.toolHint(text), rag = false)
                            } else {
                                view.setSources(hits.map { it.chunk.title }.distinct())
                                engine.send(text, prior, l, null, library.contextBlock(hits), hits.map { it.chunk.title })
                            }
                            refreshAction()
                        }
                    }
                    !spoken && prefs.ragFirst && Router.isKnowledgeQuestion(text) -> {
                        val clock = NoxLog.chrono()
                        val hits = library.search(text, k = 4, strict = true)
                        val answer = Router.directAnswer(hits)
                        if (answer == null) toModel() else main.post {
                            routing = false
                            lastLibrary = text to hits
                            NoxLog.p("RAG", "Réponse directe depuis la bibliothèque en ${clock.ms()} ms : ${hits.first().chunk.title.take(40)}")
                            view.setSources(hits.filter { !it.chunk.source.startsWith("chat:") }.map { it.chunk.title }.distinct())
                            l.onChunk(answer)
                            l.onDone("$LIBRARY_ANSWER · ${clock.ms()} ms")
                        }
                    }
                    else -> toModel()
                }
            } catch (t: Throwable) {
                NoxLog.e("ROUTE", "Aiguillage impossible, passage au modèle", t)
                toModel()
            }
        }.start()
    }

    /** Réponse immédiate, sans le modèle (appelé depuis le fil d'aiguillage). */
    private fun direct(l: LlmEngine.StreamListener, answer: String, label: String) = main.post {
        routing = false
        lastLibrary = null
        l.onChunk(answer)
        l.onDone(label)
    }

    /** Phrases spéciales : point du matin, écran, interprète, notes vocales, pages web à retenir. */
    private fun special(hit: Intents.Hit, prior: List<ChatMsg>, l: LlmEngine.StreamListener) {
        NoxLog.i("ROUTE", "Phrase spéciale : ${hit.kind}")
        when (hit.kind) {
            Intents.BRIEFING -> {
                main.post { setMode(NeuronView.State.THINKING, "Je prépare ton point du matin…") }
                direct(l, Briefing.build(prefs.userName), "point du matin")
            }
            Intents.SCREEN_SUMMARY, Intents.SCREEN_REPLY -> {
                val acc = NoxAccess.instance
                val screen = acc?.readScreen()
                when {
                    acc == null -> direct(l, "Pour lire l'écran, active l'accessibilité de Nox (Menu › Accès), puis redemande-moi.", "écran")
                    screen == null || screen.contains("C'est Nox lui-même") ->
                        direct(l, "Ouvre l'appli à lire, touche la bulle Nox, puis redis « résume mon écran » ou « propose une réponse ».", "écran")
                    else -> {
                        val ask = if (hit.kind == Intents.SCREEN_SUMMARY)
                            "Résume en quelques phrases ce que montre cet écran : qui parle, de quoi, les informations importantes. Ignore les boutons et les menus."
                        else "Propose une réponse courte, naturelle et prête à envoyer au dernier message reçu sur cet écran. Donne seulement la réponse, à la première personne."
                        val content = "Contenu de l'écran du téléphone :\n" + screen.substringBefore("\nÉléments :")
                        NoxLog.i("ECRAN", "Écran lu pour ${hit.kind} : ${content.length} caractères")
                        main.post {
                            routing = false
                            lastLibrary = null
                            engine.send(ask, prior, l, null, content)
                            refreshAction()
                        }
                    }
                }
            }
            Intents.INTERPRETER -> {
                val lang = Intents.lang(hit.arg)
                main.post { startInterpreter(lang) }
                direct(l, "Mode interprète français ⇄ ${lang.name} activé. Parle en français : je traduis et je lis en ${lang.name}. " +
                    "Ensuite j'écoute la réponse en ${lang.name} et je la redis en français. Dis « stop » pour arrêter.", "interprète")
            }
            Intents.INTERPRETER_END -> direct(l, "Le mode interprète n'est pas actif.", "interprète")
            Intents.NOTE -> {
                val clean = Intents.cleanNote(hit.arg)
                if (clean.length < 2) direct(l, "Que veux-tu que je note ? Dis par exemple « note : appeler le garage ».", "note")
                else {
                    val stamp = java.text.SimpleDateFormat("d MMM yyyy HH'h'mm", java.util.Locale.FRANCE).format(java.util.Date())
                    val title = "$NOTE_TITLE du $stamp · " + clean.split(' ').take(6).joinToString(" ").trimEnd('.', ',', ';')
                    library.addDocument(title, clean)
                    direct(l, "C'est noté : « $clean »", "note vocale")
                }
            }
            Intents.NOTES_FIND -> direct(l, findNotes(hit.arg.trim()), "notes vocales")
            Intents.REMEMBER -> {
                val fact = hit.arg.trim().trimEnd('.', '!').replaceFirstChar { it.uppercase() }
                if (fact.length < 3) direct(l, "Que veux-tu que je retienne ?", "mémoire")
                else {
                    Nox.skills.memory.add(fact)
                    engine.refreshPrompt()
                    NoxLog.i("MEMOIRE", "Souvenir ajouté directement : $fact")
                    direct(l, "C'est retenu : « $fact ». Je m'en souviendrai dans toutes nos conversations.", "mémoire")
                }
            }
            Intents.FORGET -> {
                val n = Nox.skills.memory.forget(hit.arg.trim().trimEnd('.', '!'))
                if (n > 0) engine.refreshPrompt()
                direct(l, if (n == 0) "Je n'avais rien retenu là-dessus." else "C'est oublié ($n souvenir${if (n > 1) "s" else ""} effacé${if (n > 1) "s" else ""}).", "mémoire")
            }
            Intents.ABOUT_ME -> {
                val facts = Nox.skills.memory.all()
                val name = prefs.userName.takeIf { it.isNotBlank() }?.let { "Tu t'appelles $it." } ?: ""
                direct(l, if (facts.isEmpty()) "$name Je n'ai encore rien retenu d'autre. Dis-moi par exemple « retiens que je travaille à l'IME ».".trim()
                    else "$name Voici ce que je retiens de toi :\n".trimStart() + facts.joinToString("\n") { "• $it" }, "mémoire")
            }
            Intents.ASK_ONLINE -> askOnline(hit.arg.ifBlank { prior.lastOrNull { it.fromUser }?.text ?: "" }, prior, l, explicit = true)
            Intents.KEEP_PAGE -> {
                val pages = lastWeb.filter { it.text.length > 200 }
                if (pages.isEmpty()) direct(l, "Fais d'abord une recherche sur internet, puis dis « retiens cette page ».", "bibliothèque")
                else {
                    pages.forEach { r -> library.addDocument(r.title.take(90) + " (web)", r.text + "\n\nSource : " + r.url) }
                    lastWeb = emptyList()
                    direct(l, "J'ajoute ${pages.size} page${if (pages.size > 1) "s" else ""} à ta bibliothèque : " +
                        pages.joinToString(" ; ") { it.title.take(60) } + ". Tu pourras m'interroger dessus même sans internet.", "bibliothèque")
                }
            }
            else -> direct(l, "Je n'ai pas compris.", "?")
        }
    }

    // ------------------------------------------------------------ relais en ligne

    private fun online(): Boolean = try {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } catch (_: Throwable) { false }

    private fun cloudReady(keys: CloudKeys) = listOf(Cloud.Api.GROQ, Cloud.Api.GEMINI, Cloud.Api.OPENROUTER).any { keys.has(it.id) }

    private fun cloudHistory(prior: List<ChatMsg>) = prior.filter { it.text.isNotBlank() }.takeLast(6)
        .map { Cloud.Msg(if (it.fromUser) "user" else "assistant", it.text.removePrefix("☁ ")) }

    /** « Demande en ligne : … » : la question part directement vers Groq / Gemini / OpenRouter. */
    private fun askOnline(question: String, prior: List<ChatMsg>, l: LlmEngine.StreamListener, explicit: Boolean) {
        val keys = CloudKeys(this)
        when {
            question.isBlank() -> direct(l, "Quelle question veux-tu poser en ligne ?", "en ligne")
            !cloudReady(keys) -> direct(l, "Aucune IA en ligne n'est configurée : ajoute une clé Groq ou Gemini dans la page « En ligne ».", "en ligne")
            !online() -> direct(l, "Pas de connexion internet pour l'instant.", "en ligne")
            else -> {
                main.post { setMode(NeuronView.State.THINKING, "Je demande en ligne…") }
                val r = try { Cloud.ask(question, cloudHistory(prior), keys, prefs.userName) } catch (t: Throwable) { null }
                if (r == null || r.text.isBlank()) direct(l, "L'IA en ligne ne répond pas pour l'instant.", "en ligne")
                else {
                    NoxLog.p("RELAIS", "${if (explicit) "Demande" else "Relais"} en ligne · ${r.via} · ${r.ms} ms")
                    direct(l, "☁ " + r.text, "en ligne · ${r.via}")
                }
            }
        }
    }

    /** Gemma avoue ne pas savoir : si le relais est permis, la même question part en ligne et la réponse s'ajoute. */
    private fun relayIfUnsure(question: String, answer: String, stats: String, prior: List<ChatMsg>) {
        if (!prefs.cloudRelay || !stats.contains("tok/s") || !Intents.uncertain(answer)) return
        val keys = CloudKeys(this)
        if (!cloudReady(keys) || !online()) return
        NoxLog.i("RELAIS", "Gemma ne sait pas : la question part en ligne")
        setMode(NeuronView.State.THINKING, "Gemma ne sait pas, je demande en ligne…")
        Thread {
            val r = try { Cloud.ask(question, cloudHistory(prior), keys, prefs.userName) } catch (t: Throwable) { NoxLog.w("RELAIS", "Relais en ligne impossible : ${t.message}"); null }
            main.post {
                if (r == null || r.text.isBlank()) { setMode(NeuronView.State.IDLE, idleHint()); return@post }
                NoxLog.p("RELAIS", "Réponse en ligne ajoutée · ${r.via} · ${r.ms} ms")
                val m = ChatMsg(false, "☁ " + r.text).apply { voice = prefs.autoSpeak }
                val v = addTurn(m)
                v.render(); v.refreshFooter()
                scrollToEnd()
                if (prefs.autoSpeak) { stopListening(); voiceOut.speakAll(r.text) } else setMode(NeuronView.State.IDLE, idleHint())
                if (Bubble.shown) Bubble.say(r.text)
            }
        }.start()
    }

    private fun findNotes(topic: String): String {
        val all = library.sources().filter { it.title.startsWith(NOTE_TITLE) }
        if (all.isEmpty()) return "Tu n'as encore aucune note vocale. Dis « note : … » pour en créer une."
        val found = if (topic.isEmpty()) all.take(8) else {
            val byText = library.sources(topic).filter { it.title.startsWith(NOTE_TITLE) }.map { it.source }
            val bySense = library.search(topic, k = 8).map { it.chunk }.filter { it.title.startsWith(NOTE_TITLE) }.map { it.source }
            (byText + bySense).distinct().mapNotNull { src -> all.firstOrNull { it.source == src } }.take(8)
        }
        if (found.isEmpty()) return "Je ne trouve pas de note sur « $topic »."
        return (if (topic.isEmpty()) "Tes dernières notes :" else "Tes notes sur « $topic » :") + "\n" + found.joinToString("\n") { s ->
            "• " + s.title.removePrefix("$NOTE_TITLE du ").substringBefore(" · ") + " : " + library.extracts(s.source).joinToString(" ") { it.text }.take(400)
        }
    }

    // ------------------------------------------------------------ interprète

    private fun startInterpreter(lang: Intents.Lang) {
        interp = lang
        interpFromForeign = false
        VoiceIn.language = VoiceIn.FRENCH
        NoxLog.p("INTERPRETE", "Mode interprète français ⇄ ${lang.name}")
        if (mode == NeuronView.State.IDLE) setMode(NeuronView.State.IDLE, idleHint())
    }

    private fun stopInterpreter(): String {
        val lang = interp
        interp = null
        VoiceIn.language = VoiceIn.FRENCH
        main.post { voiceOut.setLanguage(VoiceIn.FRENCH); if (mode == NeuronView.State.IDLE) setMode(NeuronView.State.IDLE, idleHint()) }
        NoxLog.p("INTERPRETE", "Fin du mode interprète")
        return "Mode interprète ${lang?.name ?: ""} terminé. Je reparle français."
    }

    /** Traduit la phrase entendue (en ligne si une IA est enregistrée, sinon Gemma) puis la lit dans l'autre langue. */
    private fun interpret(text: String, lang: Intents.Lang, l: LlmEngine.StreamListener) {
        val fromForeign = interpFromForeign
        val target = if (fromForeign) "French" else lang.english
        val targetTag = if (fromForeign) VoiceIn.FRENCH else lang.tag
        main.post { setMode(NeuronView.State.THINKING, "Je traduis…") }
        val online = try { Cloud.translate(text, target, CloudKeys(this)) } catch (t: Throwable) { NoxLog.w("INTERPRETE", "Traduction en ligne impossible : ${t.message}"); null }
        val out = online ?: localTranslate(text, target)
        main.post {
            voiceOut.setLanguage(targetTag)
            interpFromForeign = !fromForeign
            VoiceIn.language = if (interpFromForeign) lang.tag else VoiceIn.FRENCH
        }
        if (out.isBlank()) direct(l, "Je n'ai pas réussi à traduire, répète s'il te plaît.", "interprète")
        else direct(l, out, if (online != null) "interprète en ligne → ${if (fromForeign) "français" else lang.name}" else "interprète Gemma → ${if (fromForeign) "français" else lang.name}")
    }

    private fun localTranslate(text: String, target: String): String {
        if (!engine.ready) return ""
        val latch = java.util.concurrent.CountDownLatch(1)
        val sb = StringBuilder()
        main.post {
            engine.send("Translate into $target. Reply with the translation only, nothing else:\n$text", emptyList(), object : LlmEngine.StreamListener {
                override fun onChunk(text: String) { sb.append(text) }
                override fun onDone(stats: String) { latch.countDown() }
                override fun onError(msg: String) { latch.countDown() }
            }, context = "")
        }
        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        return sb.toString().trim().trim('"', '«', '»', ' ')
    }

    /** Bilan de l'import des images du portrait ; le portrait s'affiche s'il est prêt. */
    fun portraitImported(report: String) {
        if (PortraitAssets.ready(this) && ::voicePage.isInitialized) voicePage.portraitReady()
        NoxDialog.Builder(this).setTitle("Portrait de Nox").setIcon("🖼").setMessage(report)
            .setPositiveButton(if (PortraitAssets.ready(this)) "Voir Nox" else "OK") { _, _ -> if (PortraitAssets.ready(this)) openVoicePage(false) }
            .show()
    }

    fun runText(t: String) = send(t)

    /** Copie de la conversation en cours (banc d'essai : on remet le modèle dans l'état d'avant). */
    fun history(): List<ChatMsg> = messages.toList()

    private fun finishTurn(spoken: Boolean) {
        streamingView = null
        if (Bubble.shown) messages.lastOrNull()?.takeIf { !it.fromUser }?.let { Bubble.say(it.text) }
        chats.save(messages)
        if (!engine.busy) engine.lighten(messages.toList())
        if (spoken) voiceOut.endStream()
        if (!voiceOut.speaking && !listeningNow) setMode(NeuronView.State.IDLE, idleHint())
        if (!spoken) maybeRelisten()
        refreshAction()
    }

    private fun maybeRelisten() {
        if ((prefs.handsFree || interp != null) && turnFromVoice && !isFinishing) main.postDelayed({ if (!engine.busy && !voiceOut.speaking) startListening() }, 350)
    }

    fun newConversation() {
        interrupt()
        messages.clear()
        chats.clear()
        list.removeAllViews()
        statsLine.text = ""
        addTurn(ChatMsg(false, greeting() + " Page blanche : de quoi on parle ?"), save = false, register = false)
        engine.resetConversation(emptyList())
    }

    /** Appelé quand la personnalité / la température / le prénom changent. */
    fun applyConversationSettings() {
        if (engine.ready) engine.resetConversation(messages.toList())
        applyLightSettings()
    }

    /** Réglages qui ne touchent pas au modèle (voix, mains libres, arrière-plan). */
    fun applyLightSettings() {
        voiceOut.applyVoice()
        styleMute()
        if (mode == NeuronView.State.IDLE) setMode(NeuronView.State.IDLE, idleHint())
        if (!prefs.background) NoxService.stop(this) else if (engine.ready && !NoxService.running) NoxService.start(Nox.app, "${engine.modelLabel} · ${engine.backendLabel}")
        updateKeepScreen()
    }

    fun toggleHandsFree() {
        prefs.handsFree = !prefs.handsFree
        toast(if (prefs.handsFree) "Mains libres activé : Nox réécoute après chaque réponse" else "Mains libres désactivé")
        updateKeepScreen()
        if (mode == NeuronView.State.IDLE) setMode(NeuronView.State.IDLE, idleHint())
        if (prefs.handsFree && engine.ready && !engine.busy) openVoicePage(listen = true)
    }

    private fun updateKeepScreen() {
        if (prefs.handsFree) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ================================================================ voix — entrée

    private fun startListening() {
        if (!engine.ready) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        voiceOut.stop()
        turnFromVoice = true
        voicePage.heard("")
        if (prefs.listenMode == "native" && engine.audioReady) {
            // Oreille native : le son brut part directement dans Gemma
            if (capture == null) capture = AudioCapture(this)
            setMode(NeuronView.State.LISTENING, "Je t'écoute…")
            capture?.start(prefs.patienceMs, prefs.maxListenSec)
            return
        }
        if (voiceIn == null) voiceIn = VoiceIn(this, this)
        voiceIn?.patienceMs = prefs.patienceMs
        setMode(NeuronView.State.LISTENING, "Je t'écoute…")
        voiceIn?.start()
    }

    // ---- oreille native (AudioCapture)

    override fun onCaptureStarted() = setMode(NeuronView.State.LISTENING, "Je t'écoute… touche le neurone quand tu as fini")
    override fun onCaptureLevel(level: Float) { voicePage.setLevel(level); neuron.setLevel(level) }
    override fun onCaptureElapsed(speechMs: Long) {
        val s = speechMs / 1000
        voicePage.caption(String.format(java.util.Locale.FRANCE, "Je t'écoute… %d:%02d · touche le neurone quand tu as fini", s / 60, s % 60))
    }

    override fun onCaptured(wav: List<ByteArray>, durationMs: Long) {
        voicePage.setLevel(0f)
        turnFromVoice = true
        sendAudio(wav, durationMs)
    }
    override fun onCaptureNothing() = onNothing()
    override fun onCaptureError(msg: String) = onVoiceError(msg)

    override fun onListening() {
        setMode(NeuronView.State.LISTENING, if (voiceIn?.isOnDevice == true) "Je t'écoute… (reconnaissance locale)" else "Je t'écoute…")
    }

    override fun onLevel(level: Float) { voicePage.setLevel(level); neuron.setLevel(level) }

    override fun onPartial(text: String) {
        library.prefetch(text)
        voicePage.heard(text)
        if (!voicePage.isOpen) statsLine.text = text
    }

    override fun onFinal(text: String) {
        NoxLog.i("OREILLE", "Compris : « $text »")
        voicePage.heard(text, final = true)
        voicePage.setLevel(0f)
        turnFromVoice = true
        send(text)
    }

    override fun onNothing() {
        if (listeningNow || voicePage.isOpen) voicePage.notice("Je n'ai rien entendu · touche le visage pour réessayer")
        voicePage.setLevel(0f)
        neuron.setLevel(0f)
        if (!engine.busy) setMode(NeuronView.State.IDLE, idleHint())
    }

    override fun onVoiceError(msg: String) {
        onNothing()
        voicePage.notice("⚠ $msg")
        toast(msg)
    }

    // ================================================================ voix — sortie

    override fun onSpeakingChanged(speaking: Boolean) {
        if (speaking) setMode(NeuronView.State.SPEAKING, "Nox parle · touche le neurone pour l'interrompre")
        else if (!engine.busy && !listeningNow) setMode(NeuronView.State.IDLE, idleHint())
        refreshAction()
    }

    override fun onAllSpoken() {
        refreshAction()
        maybeRelisten()
    }

    // ================================================================ autorisations

    /** Après le premier lancement, on redemande seulement l'essentiel (micro, notifications). */
    private fun askEssentials() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_ALL)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        if (requestCode == REQ_PERM && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toast("Merci ! Redemande-moi, ça va marcher.")
        PermissionsDialog.current?.refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ModelsDialog.REQ_IMPORT) ModelsDialog.onImportResult(this, resultCode, data)
        if (requestCode == PortraitAssets.REQ_IMPORT) PortraitAssets.onResult(this, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) data?.data?.let { loadImage(it) }
        if (requestCode == REQ_CAMERA) {
            val uri = cameraUri
            cameraUri = null
            if (resultCode == RESULT_OK && uri != null) loadImage(uri) else uri?.let { contentResolver.delete(it, null, null) }
        }
        if (requestCode == SkillsDialog.REQ_DOC && resultCode == RESULT_OK) data?.data?.let { SkillsDialog.importDocument(this, it) }
        if (requestCode == SkillsDialog.REQ_FK && resultCode == RESULT_OK) data?.data?.let { SkillsDialog.importFreekipedia(this, it) }
    }

    // ---- vue : photo et galerie

    private fun visionOk(): Boolean {
        if (engine.visionReady) return true
        toast(if (engine.ready) "La vue n'est pas active : il faut Gemma série E et l'option « Vue » dans les réglages" else "Le modèle n'est pas chargé")
        return false
    }

    private fun pickImage() {
        if (!visionOk()) return
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_IMAGE)
    }

    /** Appareil photo du téléphone ; la photo est rangée dans Images/Nox (aucune permission caméra). */
    private fun takePhoto() {
        if (!visionOk()) return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "nox_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nox")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run { toast("Photo impossible"); return }
        cameraUri = uri
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQ_CAMERA)
        } catch (_: Throwable) {
            contentResolver.delete(uri, null, null); cameraUri = null
            toast("Aucune appli photo trouvée")
        }
    }

    /** Réduit la photo à 768 px max : assez pour Gemma, rapide à encoder. */
    private fun loadImage(uri: Uri) {
        Thread {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 768) sample *= 2
                val bmp = contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("image illisible")
                val scale = 768f / maxOf(bmp.width, bmp.height)
                val fin = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                val out = java.io.ByteArrayOutputStream()
                fin.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                main.post {
                    pendingImage = out.toByteArray()
                    attachLabel.text = "📷 photo jointe (${fin.width}×${fin.height}) · pose ta question"
                    attachRow.visibility = View.VISIBLE
                    input.requestFocus()
                    refreshAction()
                }
            } catch (t: Throwable) {
                main.post { toast("Photo illisible : ${t.message}") }
            }
        }.start()
    }

    private fun clearAttachment() {
        pendingImage = null
        attachRow.visibility = View.GONE
        refreshAction()
    }

    // ---- intentions reçues : partage de texte, bouton power, notification « Parler »

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) {
            intent.removeExtra(EXTRA_VOICE)
            main.post { openVoicePage(listen = true) }
            return
        }
        if (intent.action == ACTION_IMPORT_FREEKIPEDIA) {
            val uri = intent.data
            intent.action = null
            if (uri != null) main.post { SkillsDialog.importFreekipedia(this, uri) }
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        val txt = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (txt.isEmpty()) return
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() } ?: txt.take(40).replace('\n', ' ')
        NoxDialog.Builder(this)
            .setTitle("Texte reçu (${txt.length} caractères)").setIcon("📥")
            .setItems(arrayOf("📚  Ajouter à la bibliothèque", "💬  En parler avec Nox")) { _, w ->
                if (w == 0) library.addDocument(title, txt) { n -> toast("Ajouté à la bibliothèque ($n extrait${if (n > 1) "s" else ""})") }
                else { input.setText(txt.take(4000)); input.setSelection(input.text.length) }
            }.show()
        intent.action = null
    }

    /** Salutation selon l'heure, avec le prénom s'il est connu. */
    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = when (h) { in 5..11 -> "Bonjour"; in 12..17 -> "Salut"; in 18..22 -> "Bonsoir"; else -> "Encore debout" }
        val name = prefs.userName.trim()
        return if (name.isEmpty()) "$hello !" else if (h in 23..23 || h < 5) "$hello, $name ?" else "$hello $name !"
    }

    private fun welcomeText() = buildString {
        append(greeting()).append(" Je suis **Nox**. Je vis entièrement dans ce téléphone : ")
        append("pas de cloud, pas de compte, rien ne sort d'ici.\n\n")
        append("Touche le micro et parle-moi, ou écris en bas. Un appui long sur le bouton power me réveille de n'importe où, ")
        append("et je peux piloter tes applis et lire tes notifications si tu me donnes les accès (menu › Autorisations).")
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        /** Marque des réponses tirées directement de la bibliothèque (ligne de stats). */
        const val LIBRARY_ANSWER = "bibliothèque"
        /** Durée de l'écran de démarrage (un toucher le passe). */
        const val SPLASH_MS = 10_000L
        /** Envoyé par Freekipedia (« Envoyer à Nox ») avec le fichier .db en données. */
        const val ACTION_IMPORT_FREEKIPEDIA = "com.application.nox.IMPORTER_FREEKIPEDIA"
        const val EXTRA_VOICE = "com.application.nox.VOICE"
        const val NOTE_TITLE = "Note vocale"
        const val REQ_PERM = 13
        const val REQ_PERMS_CENTER = 15
        private const val REQ_MIC = 11
        private const val REQ_ALL = 12
        private const val REQ_IMAGE = 14
        private const val REQ_CAMERA = 16
    }
}
