package com.application.nox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.application.nox.Ui.dp
import com.application.nox.Ui.iconButton
import com.application.nox.Ui.text

class MainActivity : Activity(), VoiceIn.Listener, VoiceOut.Listener, AudioCapture.Listener {

    lateinit var prefs: Prefs
    lateinit var store: ModelStore
    private lateinit var chats: ChatStore
    lateinit var engine: LlmEngine
    lateinit var voiceOut: VoiceOut
    lateinit var skills: Skills
    lateinit var library: Library
    private var pendingImage: ByteArray? = null
    private lateinit var attachRow: LinearLayout
    private lateinit var attachLabel: TextView
    private var embedTried = false
    private var voiceIn: VoiceIn? = null
    private var capture: AudioCapture? = null

    /** Une écoute est en cours (reconnaissance Android OU oreille native Gemma). */
    private val listeningNow get() = voiceIn?.active == true || capture?.active == true

    val messages = mutableListOf<ChatMsg>()
    private val main = Handler(Looper.getMainLooper())

    // Vues
    private lateinit var orb: OrbView
    private lateinit var orbHint: TextView
    private lateinit var statusChip: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var actionBtn: TextView
    private lateinit var statsLine: TextView
    private lateinit var orbBox: FrameLayout

    private var streamingView: TextView? = null
    private var streamingMsg: ChatMsg? = null
    private var turnFromVoice = false
    private var loading = false

    // ================================================================ cycle de vie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = ModelStore(this)
        chats = ChatStore(this)
        library = Library(this, store)
        skills = Skills(this, library)
        skills.onUsed = { label -> addChip(label) }
        skills.askPermission = { perm -> requestPermissions(arrayOf(perm), REQ_PERM) }
        engine = LlmEngine(this, prefs, store, skills, library)
        voiceOut = VoiceOut(this, prefs, this)

        window.setDecorFitsSystemWindows(false)   // bord à bord partout, on gère les marges nous-mêmes
        setContentView(buildUi())
        messages.addAll(chats.load())
        if (messages.isEmpty()) addBubble(ChatMsg(false, welcomeText()), save = false) else messages.forEach { addBubble(it, save = false, register = false) }
        scrollToEnd()

        askPermissions()
        handleShare(intent)
        bootModel()
        updateKeepScreen()
    }

    /** Si un téléchargement se termine pendant que Nox dort, on le branche automatiquement. */
    private val autoPlug = object : Runnable {
        override fun run() {
            store.pruneCustom().forEach { toast("Échec du téléchargement de $it (lien privé ou invalide ?)") }
            if (!embedTried && store.embedFile() != null) { embedTried = true; library.enableSemantic() }
            if (!engine.ready && !loading && (prefs.modelPath.isBlank() || !java.io.File(prefs.modelPath).exists())) {
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
        main.removeCallbacks(autoPlug)
        main.postDelayed(autoPlug, 3000)
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
    }

    override fun onDestroy() {
        if (loading) prefs.loadInProgress = false   // fermeture normale, pas un plantage
        main.removeCallbacksAndMessages(null)
        voiceIn?.release()
        capture?.cancel()
        voiceOut.release()
        engine.release()
        library.release()
        super.onDestroy()
    }

    // ================================================================ interface

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Barre haute
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(12), dp(4))
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(text("Nox", 24f, Ui.TEXT, bold = true).apply { letterSpacing = 0.08f })
        statusChip = text("hors-ligne · 100 % local", 12f, Ui.MUTED)
        titleBox.addView(statusChip)
        top.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconButton("✦") { newConversation() }.also { it.contentDescription = "Nouvelle conversation" })
        top.addView(spacer(6))
        top.addView(iconButton("⚡") { SkillsDialog(this).show() }.also { it.contentDescription = "Compétences et mémoire" })
        top.addView(spacer(6))
        top.addView(iconButton("◉") { ModelsDialog(this).show() }.also { it.contentDescription = "Modèles" })
        top.addView(spacer(6))
        top.addView(iconButton("⚙") { SettingsDialog(this).show() }.also { it.contentDescription = "Réglages" })
        col.addView(top)

        // Orbe
        orbBox = FrameLayout(this)
        orb = OrbView(this)
        orbBox.addView(orb, FrameLayout.LayoutParams(-1, -1))
        orbHint = text("", 12.5f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        orbBox.addView(orbHint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(6) })
        orb.setOnClickListener { onOrbTap() }
        orb.setOnLongClickListener { toggleHandsFree(); true }
        col.addView(orbBox, LinearLayout.LayoutParams(-1, dp(210)))

        transcript = text("", 16f, Ui.CYAN).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), dp(6))
            visibility = View.GONE
        }
        col.addView(transcript)

        // Conversation
        scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(8))
        }
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        statsLine = text("", 11f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(2)) }
        col.addView(statsLine)

        // Saisie
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        input = EditText(this).apply {
            hint = "Écris à Nox…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 16f
            background = Ui.rounded(Ui.SURFACE, dp(24).toFloat(), Ui.STROKE, dp(1))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refreshAction()
            })
        }
        bar.addView(iconButton("＋", 42) { pickImage() }.also { it.contentDescription = "Joindre une photo" })
        bar.addView(spacer(8))
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(spacer(8))
        actionBtn = iconButton("🎙", 50, Ui.ACCENT, Color.WHITE) { onAction() }
        bar.addView(actionBtn)
        attachRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(16), 0)
            visibility = View.GONE
        }
        attachLabel = text("📷 photo jointe", 13f, Ui.CYAN)
        attachRow.addView(attachLabel, LinearLayout.LayoutParams(0, -2, 1f))
        attachRow.addView(text("✕ retirer", 13f, Ui.MUTED).apply { setPadding(dp(10), dp(6), dp(4), dp(6)); setOnClickListener { clearAttachment() } })
        col.addView(attachRow)
        col.addView(bar)

        // Bords à bords (Android 15+) : on respecte barres système et clavier
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            val kb = ime.bottom > 0
            orbBox.layoutParams = orbBox.layoutParams.apply { height = dp(if (kb) 92 else 210) }
            if (kb) scrollToEnd()
            insets
        }
        setOrb(OrbView.Mode.OFF, "")
        return root
    }

    private fun spacer(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) }

    private fun addBubble(m: ChatMsg, save: Boolean = true, register: Boolean = true): TextView {
        if (register) messages += m
        val tv = text(Markdown.render(m.text), 15.5f, Ui.TEXT).apply {
            setPadding(dp(16), dp(11), dp(16), dp(11))
            maxWidth = (resources.displayMetrics.widthPixels * 0.84).toInt()
            background = if (m.fromUser) Ui.rounded(Ui.SURFACE_HI, dp(20).toFloat())
            else Ui.rounded(Ui.SURFACE, dp(20).toFloat(), Ui.STROKE, dp(1))
            setOnLongClickListener { bubbleMenu(m); true }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (m.fromUser) Gravity.END else Gravity.START
            topMargin = dp(8)
        }
        list.addView(tv, lp)
        if (save) chats.save(messages)
        return tv
    }

    private fun bubbleMenu(m: ChatMsg) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Copier", "Relire à voix haute")) { _, which ->
                when (which) {
                    0 -> {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nox", m.text))
                        toast("Copié")
                    }
                    1 -> voiceOut.speakAll(m.text)
                }
            }.show()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun setOrb(mode: OrbView.Mode, hint: String) {
        orb.mode = mode
        orbHint.text = hint
    }

    private fun idleHint() = when {
        !engine.ready -> ""
        prefs.handsFree -> "Mains libres actif · touche l'orbe pour parler"
        else -> "Touche l'orbe pour parler · appui long : mains libres"
    }

    private fun refreshAction() {
        val generating = engine.busy || voiceOut.speaking
        actionBtn.text = when {
            generating -> "■"
            input.text.isNotBlank() || pendingImage != null -> "➤"
            else -> "🎙"
        }
    }

    // ================================================================ modèle

    fun bootModel() {
        if (prefs.modelPath.isBlank() || !java.io.File(prefs.modelPath).exists()) {
            // Premier lancement : on prend un modèle déjà présent, sinon on ouvre le catalogue
            val local = store.localModels().firstOrNull()
            if (local != null) prefs.modelPath = local.absolutePath
            else {
                statusChip.text = "aucun cerveau installé"
                setOrb(OrbView.Mode.OFF, "Installe un modèle pour réveiller Nox")
                main.postDelayed({ ModelsDialog(this).show() }, 500)
                return
            }
        }
        loadModel()
    }

    fun loadModel() {
        if (loading) return
        loading = true
        // Garde-fou : si le chargement précédent a fait planter l'appli (pilote GPU), on passe en CPU.
        if (prefs.loadInProgress && prefs.backend == "gpu") {
            prefs.backend = "cpu"
            toast("Le dernier démarrage GPU a échoué : passage automatique en CPU (modifiable dans Réglages)")
        }
        prefs.loadInProgress = true
        voiceOut.stop(); stopListening()
        setOrb(OrbView.Mode.OFF, "Réveil de Nox…")
        statusChip.text = "chargement du modèle…"
        refreshAction()
        engine.load(messages.toList(), onStatus = { s -> orbHint.text = s }) { ok, msg ->
            loading = false
            prefs.loadInProgress = false
            if (ok) {
                statusChip.text = "$msg · hors-ligne"
                setOrb(OrbView.Mode.IDLE, idleHint())
            } else {
                statusChip.text = "modèle non chargé"
                setOrb(OrbView.Mode.OFF, msg)
                toast(msg)
            }
            refreshAction()
        }
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
            else -> startListening()
        }
    }

    private fun onOrbTap() {
        when {
            !engine.ready -> if (!loading) ModelsDialog(this).show()
            engine.busy || voiceOut.speaking -> { interrupt(); startListening() }
            capture?.active == true -> capture?.finish()   // « j'ai fini » : on envoie ce qui a été dit
            voiceIn?.active == true -> { voiceIn?.stop(); setOrb(OrbView.Mode.IDLE, idleHint()); transcript.visibility = View.GONE }
            else -> startListening()
        }
    }

    private fun interrupt() {
        engine.stop()
        voiceOut.stop()
        setOrb(OrbView.Mode.IDLE, idleHint())
        refreshAction()
    }

    private fun send(text: String) {
        val img = pendingImage
        clearAttachment()
        runTurn(if (img != null && text.isBlank()) "Que vois-tu sur cette photo ?" else text, null, 0L, img)
    }

    private fun sendAudio(wav: ByteArray, ms: Long) = runTurn(null, wav, ms)

    /** Un tour de parole complet : bulle utilisateur → réflexion → réponse en flux (+ voix). */
    private fun runTurn(text: String?, wav: ByteArray?, ms: Long, image: ByteArray? = null) {
        if (!engine.ready) { toast(if (loading) "Nox se réveille, un instant…" else "Aucun modèle chargé"); return }
        if (engine.busy) return
        stopListening()
        voiceOut.stop()

        val prior = messages.toList()
        val userMsg = ChatMsg(true, when {
            image != null -> "📷 " + (text?.ifBlank { "Que vois-tu sur cette photo ?" } ?: "")
            text != null -> text
            else -> "🎙 message vocal (${(ms + 500) / 1000} s)"
        })
        val userView = addBubble(userMsg)
        val reply = ChatMsg(false, "")
        val view = addBubble(reply, save = false)
        view.text = "…"
        streamingMsg = reply
        streamingView = view
        scrollToEnd()
        setOrb(OrbView.Mode.THINKING, if (wav != null) "Gemma écoute ta voix…" else "Réflexion…")
        statsLine.text = ""

        val speak = prefs.autoSpeak
        if (speak) voiceOut.beginStream()

        val listener = object : LlmEngine.StreamListener {
            override fun onChunk(text: String) {
                if (orb.mode == OrbView.Mode.THINKING) orbHint.text = "Réflexion…"
                reply.text += text
                view.text = Markdown.render(reply.text)
                if (speak) voiceOut.feed(text)
                if (scroll.getChildAt(0).bottom - (scroll.height + scroll.scrollY) < dp(140)) scrollToEnd()
            }

            override fun onTranscript(text: String) {
                if (text.isBlank()) return
                userMsg.text = text
                userView.text = Markdown.render("🎙 $text")
            }

            override fun onContext(n: Int) = addChip("📚 $n extrait${if (n > 1) "s" else ""} de ta bibliothèque")

            override fun onDone(stats: String) {
                if (reply.text.isBlank()) { reply.text = "(silence)"; view.text = reply.text }
                statsLine.text = stats
                if (prefs.rememberChats && stats != "interrompu") library.addExchange(userMsg.text.removePrefix("🎙 ").removePrefix("📷 "), reply.text)
                finishTurn(speak)
            }

            override fun onError(msg: String) {
                reply.text = if (reply.text.isBlank()) "⚠ $msg" else reply.text + "\n\n⚠ $msg"
                view.text = Markdown.render(reply.text)
                finishTurn(speak)
            }
        }
        if (wav != null) engine.sendAudio(wav, ms, prior, listener) else engine.send(text ?: "", prior, listener, image)
        refreshAction()
    }

    /** Puce discrète « ⏱ minuteur 5 min » quand Nox utilise une compétence. */
    private fun addChip(label: String) {
        val chip = text(label, 12f, Ui.CYAN).apply {
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = Ui.rounded(0xFF0F1E2A.toInt(), dp(14).toFloat(), 0xFF1D4A5E.toInt(), dp(1))
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START; topMargin = dp(6) }
        val anchor = streamingView
        val idx = if (anchor != null) list.indexOfChild(anchor) else -1
        if (idx >= 0) list.addView(chip, idx, lp) else list.addView(chip, lp)
        scrollToEnd()
    }

    private fun finishTurn(spoken: Boolean) {
        streamingMsg = null
        streamingView = null
        chats.save(messages)
        if (spoken) voiceOut.endStream()
        if (!voiceOut.speaking && !listeningNow) setOrb(OrbView.Mode.IDLE, idleHint())
        if (!spoken) maybeRelisten()
        refreshAction()
    }

    private fun maybeRelisten() {
        if (prefs.handsFree && turnFromVoice && !isFinishing) main.postDelayed({ if (!engine.busy && !voiceOut.speaking) startListening() }, 350)
    }

    fun newConversation() {
        interrupt()
        messages.clear()
        chats.clear()
        list.removeAllViews()
        statsLine.text = ""
        addBubble(ChatMsg(false, "Page blanche. De quoi on parle ?"), save = false, register = false)
        engine.resetConversation(emptyList())
    }

    /** Appelé quand la personnalité / la température / le prénom changent. */
    fun applyConversationSettings() {
        if (engine.ready) engine.resetConversation(messages.toList())
        applyLightSettings()
    }

    /** Réglages qui ne touchent pas au modèle (voix, mains libres). */
    fun applyLightSettings() {
        voiceOut.applyVoice()
        if (orb.mode == OrbView.Mode.IDLE) orbHint.text = idleHint()
        updateKeepScreen()
    }

    private fun toggleHandsFree() {
        prefs.handsFree = !prefs.handsFree
        toast(if (prefs.handsFree) "Mains libres activé : Nox réécoute après chaque réponse" else "Mains libres désactivé")
        updateKeepScreen()
        if (orb.mode == OrbView.Mode.IDLE) orbHint.text = idleHint()
        if (prefs.handsFree && engine.ready && !engine.busy) startListening()
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
        if (prefs.listenMode == "native" && engine.audioReady) {
            // Oreille native : le son brut part directement dans Gemma
            if (capture == null) capture = AudioCapture(this)
            transcript.visibility = View.GONE
            setOrb(OrbView.Mode.LISTENING, "Je t'écoute…")
            capture?.start()
            return
        }
        if (voiceIn == null) voiceIn = VoiceIn(this, this)
        transcript.text = ""
        transcript.visibility = View.VISIBLE
        setOrb(OrbView.Mode.LISTENING, "Je t'écoute…")
        voiceIn?.start()
    }

    // ---- oreille native (AudioCapture)

    override fun onCaptureStarted() = setOrb(OrbView.Mode.LISTENING, "Je t'écoute · oreille Gemma (touche quand tu as fini)")
    override fun onCaptureLevel(level: Float) = orb.setLevel(level)
    override fun onCaptured(wav: ByteArray, durationMs: Long) {
        orb.setLevel(0f)
        turnFromVoice = true
        sendAudio(wav, durationMs)
    }
    override fun onCaptureNothing() = onNothing()
    override fun onCaptureError(msg: String) = onVoiceError(msg)

    override fun onListening() {
        setOrb(OrbView.Mode.LISTENING, if (voiceIn?.isOnDevice == true) "Je t'écoute… (reconnaissance locale)" else "Je t'écoute…")
    }

    override fun onLevel(level: Float) = orb.setLevel(level)

    override fun onPartial(text: String) {
        transcript.text = text
    }

    override fun onFinal(text: String) {
        transcript.visibility = View.GONE
        orb.setLevel(0f)
        turnFromVoice = true
        send(text)
    }

    override fun onNothing() {
        transcript.visibility = View.GONE
        orb.setLevel(0f)
        if (!engine.busy) setOrb(OrbView.Mode.IDLE, idleHint())
    }

    override fun onVoiceError(msg: String) {
        onNothing()
        toast(msg)
    }

    // ================================================================ voix — sortie

    override fun onSpeakingChanged(speaking: Boolean) {
        if (speaking) setOrb(OrbView.Mode.SPEAKING, "Nox parle · touche pour l'interrompre")
        else if (!engine.busy && !listeningNow) setOrb(OrbView.Mode.IDLE, idleHint())
        refreshAction()
    }

    override fun onAllSpoken() {
        refreshAction()
        maybeRelisten()
    }

    // ================================================================ divers

    private fun askPermissions() {
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
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ModelsDialog.REQ_IMPORT) ModelsDialog.onImportResult(this, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) data?.data?.let { loadImage(it) }
        if (requestCode == SkillsDialog.REQ_DOC && resultCode == RESULT_OK) data?.data?.let { SkillsDialog.importDocument(this, it) }
    }

    // ---- vue 📷

    private fun pickImage() {
        if (!engine.visionReady) {
            toast(if (engine.ready) "La vue n'est pas active : il faut Gemma série E et l'option « Vue » dans les réglages" else "Le modèle n'est pas chargé")
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_IMAGE)
    }

    /** Réduit la photo à 768 px max : assez pour Gemma, rapide à encoder. */
    private fun loadImage(uri: android.net.Uri) {
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
    }

    // ---- partage vers Nox (texte depuis une autre appli)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val txt = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (txt.isEmpty()) return
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() } ?: txt.take(40).replace('\n', ' ')
        android.app.AlertDialog.Builder(this)
            .setTitle("Texte reçu (${txt.length} caractères)")
            .setItems(arrayOf("Ajouter à la bibliothèque de Nox", "En parler avec Nox")) { _, w ->
                if (w == 0) library.addDocument(title, txt) { n -> toast("Ajouté à la bibliothèque ($n extrait${if (n > 1) "s" else ""})") }
                else { input.setText(txt.take(4000)); input.setSelection(input.text.length) }
            }.show()
        intent.action = null
    }

    private fun welcomeText() = buildString {
        append("Salut ! Je suis **Nox**. Je vis entièrement dans ce téléphone : ")
        append("pas de cloud, pas de compte, rien ne sort d'ici.\n\n")
        append("Touche l'orbe et parle-moi, ou écris en bas. ")
        append("Un appui long sur l'orbe active le mode mains libres : on discute à voix haute, sans toucher l'écran.")
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_MIC = 11
        private const val REQ_ALL = 12
        private const val REQ_PERM = 13
        private const val REQ_IMAGE = 14
    }
}
