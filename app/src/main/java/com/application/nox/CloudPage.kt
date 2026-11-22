package com.application.nox

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.pillButton
import com.application.nox.Ui.text
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * « Nox en ligne » : un deuxième chat, à droite du chat local (glisser vers la gauche pour l'ouvrir,
 * vers la droite ou retour pour revenir). Chaque question part vers la bonne API choisie automatiquement
 * (Groq, Gemini Flash, OpenRouter, NVIDIA, GNews, Unsplash) ; le service utilisé est indiqué sous la réponse.
 */
class CloudPage(private val a: MainActivity, parent: FrameLayout) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val keys = CloudKeys(a)
    private val history = ArrayList<Cloud.Msg>()
    private var busy = false
    /** Bouton d'envoi : 🎙 champ vide · ➤ texte à envoyer · ■ arrêter pendant une demande. */
    private val action = TextView(a).apply { textSize = 20f; setTextColor(Ui.TEXT) }
    private var ticker: Runnable? = null
    var isOpen = false; private set

    private val page = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.BG)
        isClickable = true
        visibility = View.GONE
    }
    private val status = a.text("", 12.5f, Ui.MUTED)
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(16)) }
    private val scroll = ScrollView(a).apply { isFillViewport = true; addView(list) }
    private val input = EditText(a).apply {
        hint = "Demande en ligne…"
        setTextColor(Ui.TEXT); setHintTextColor(Ui.FAINT)
        textSize = 16f
        background = Ui.rounded(Ui.SURFACE, a.dp(26).toFloat(), Ui.STROKE, a.dp(1))
        setPadding(a.dp(18), a.dp(12), a.dp(18), a.dp(12))
        maxLines = 5
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
    }
    private val voice: VoiceIn by lazy {
        VoiceIn(a, object : VoiceIn.Listener {
            override fun onListening() { input.hint = "Je t'écoute…" }
            override fun onLevel(level: Float) {}
            override fun onPartial(text: String) { input.setText(text); input.setSelection(text.length) }
            override fun onFinal(text: String) { input.hint = "Demande en ligne…"; input.setText(text); submit() }
            override fun onNothing() { input.hint = "Demande en ligne…" }
            override fun onVoiceError(msg: String) { input.hint = "Demande en ligne…"; a.toast(msg) }
        })
    }

    init {
        // En-tête
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(10), a.dp(14), a.dp(12), a.dp(10))
        }
        head.addView(a.text("‹", 30f, Ui.LAVENDER).apply {
            setPadding(a.dp(10), 0, a.dp(14), a.dp(4))
            contentDescription = "Revenir au chat local"
            setOnClickListener { close() }
        })
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(a.text("Nox en ligne", 22f, Ui.TEXT, bold = true))
        titles.addView(status)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(chip("Nouveau") { history.clear(); list.removeAllViews(); intro() })
        head.addView(chip("API") { CloudApisDialog(a, keys) { refreshStatus() }.show() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
        page.addView(head)
        page.addView(View(a).apply { setBackgroundColor(Ui.STROKE) }, LinearLayout.LayoutParams(-1, a.dp(1)))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Barre de saisie
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(14))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        action.apply {
            gravity = Gravity.CENTER
            background = Ui.ripple(Ui.rounded(Ui.ACCENT, a.dp(26).toFloat()))
            setOnClickListener {
                val t = input.text.toString().trim()
                when {
                    busy -> stop()
                    t.isNotEmpty() -> submit()
                    voice.active -> voice.stop()
                    else -> { voice.patienceMs = a.prefs.patienceMs; voice.start() }
                }
            }
        }
        bar.addView(action, LinearLayout.LayoutParams(a.dp(52), a.dp(52)).apply { marginStart = a.dp(10) })
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshAction()
        })
        refreshAction()
        page.addView(bar)

        parent.addView(page, FrameLayout.LayoutParams(-1, -1))
        intro()
    }

    private fun refreshAction() {
        val hasText = input.text.toString().isNotBlank()
        action.text = when { busy -> "■"; hasText -> "➤"; else -> "🎙" }
        action.contentDescription = when { busy -> "Arrêter"; hasText -> "Envoyer"; else -> "Parler" }
        action.background = Ui.ripple(Ui.rounded(if (busy) Ui.DANGER else Ui.ACCENT, a.dp(26).toFloat()))
    }

    private fun stop() {
        Thread { Cloud.cancel() }.start()
    }

    private fun chip(label: String, onClick: () -> Unit) = a.text(label, 13f, Ui.TEXT, bold = true).apply {
        background = Ui.ripple(Ui.rounded(Ui.SOFT, a.dp(16).toFloat(), Ui.STROKE, a.dp(1)))
        setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
        setOnClickListener { onClick() }
    }

    private fun intro() {
        val ready = Cloud.Api.values().filter { keys.has(it.id) }
        list.addView(a.text(if (ready.isEmpty())
            "Ici, tes questions partent vers tes API en ligne. Touche « API » pour ajouter tes clés (Groq, Gemini, OpenRouter, NVIDIA, GNews, Unsplash)."
        else "Pose ta question : Nox choisit tout seul le bon service. Actualités → GNews · « montre-moi une photo de… » → Unsplash · « crée une image de… » → NVIDIA · le reste → Groq, ou Gemini pour les demandes longues.",
            14f, Ui.FAINT).apply { setPadding(0, a.dp(12), 0, a.dp(8)) })
        refreshStatus()
    }

    private fun refreshStatus() {
        val ready = Cloud.Api.values().filter { keys.has(it.id) }.joinToString(" · ") { it.title }
        status.text = if (ready.isEmpty()) "aucune API configurée" else ready
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        refreshStatus()
        page.visibility = View.VISIBLE
        page.translationX = page.width.toFloat().takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels.toFloat()
        page.animate().translationX(0f).setDuration(220).start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        if (voice.active) voice.stop()
        hideKeyboard()
        page.animate().translationX(page.width.toFloat()).setDuration(200).withEndAction { page.visibility = View.GONE }.start()
    }

    private fun hideKeyboard() {
        a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    // ---------------------------------------------------------------- un échange

    private fun submit() {
        val q = input.text.toString().trim()
        if (q.isEmpty() || busy) return
        input.setText("")
        busy = true
        refreshAction()
        hideKeyboard()
        addUser(q)
        val (box, body, foot) = addAnswer()
        // Progression : étape en cours + secondes écoulées, barre animée
        val bar = android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, a.dp(4)).apply { topMargin = a.dp(6) })
        body.setTextColor(Ui.MUTED)
        var stageText = "Je choisis le bon service"
        val t0 = System.currentTimeMillis()
        Cloud.onStage = { s -> main.post { stageText = s } }
        val tick = object : Runnable {
            override fun run() {
                body.text = "$stageText… ${(System.currentTimeMillis() - t0) / 1000} s"
                main.postDelayed(this, 500)
            }
        }
        ticker?.let { main.removeCallbacks(it) }
        ticker = tick
        tick.run()
        val prior = history.toList()
        io.execute {
            val r = try { Result.success(Cloud.ask(q, prior, keys, a.prefs.userName)) } catch (t: Throwable) { Result.failure(t) }
            main.post {
                main.removeCallbacks(tick)
                box.removeView(bar)
                body.setTextColor(Ui.ANSWER)
                busy = false
                refreshAction()
                r.onSuccess { ans ->
                    body.text = Markdown.render(ans.text)
                    foot.text = "via ${ans.via} · ${String.format(Locale.FRANCE, "%.1f", ans.ms / 1000.0)} s"
                    if (ans.sources.isNotEmpty()) box.addView(sources(ans.sources))
                    if (ans.photos.isNotEmpty()) box.addView(photos(ans.photos))
                    ans.created.forEach { box.addView(created(it)) }
                    history += Cloud.Msg("user", q)
                    history += Cloud.Msg("assistant", ans.text)
                    while (history.size > 24) history.removeAt(0)
                    if (a.prefs.autoSpeak && ans.photos.isEmpty() && ans.created.isEmpty()) a.voiceOut.speakAll(ans.text)
                }.onFailure { t ->
                    val stopped = t.message == "arrêté"
                    body.text = if (stopped) "Demande arrêtée." else "⚠ " + (t.message ?: "erreur")
                    body.setTextColor(if (stopped) Ui.FAINT else Ui.DANGER)
                    foot.text = if (stopped) "" else "Touche « API » pour vérifier tes clés"
                }
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addUser(q: String) {
        val row = LinearLayout(a).apply { gravity = Gravity.END; setPadding(a.dp(40), a.dp(14), 0, a.dp(4)) }
        row.addView(a.text(q, 15.5f, Ui.USER_TEXT).apply {
            background = Ui.rounded(Ui.USER_BUBBLE, a.dp(18).toFloat(), Ui.USER_BUBBLE_STROKE, a.dp(1))
            setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10))
            setTextIsSelectable(true)
        })
        list.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addAnswer(): Triple<LinearLayout, TextView, TextView> {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(12), 0, a.dp(6)) }
        box.addView(a.text("NOX · EN LIGNE", 11.5f, Ui.CYAN, bold = true).apply { letterSpacing = 0.12f })
        val body = a.text("", 16f, Ui.ANSWER).apply { setPadding(0, a.dp(6), 0, a.dp(4)); setTextIsSelectable(true) }
        val foot = a.text("", 12f, Ui.FAINT)
        box.addView(body); box.addView(foot)
        list.addView(box, LinearLayout.LayoutParams(-1, -2))
        return Triple(box, body, foot)
    }

    private fun sources(items: List<String>) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, a.dp(8), 0, 0)
        items.take(6).forEach { s ->
            val url = TurnView.sourceUrl(s)
            addView(a.text("🌐 " + TurnView.sourceTitle(s).take(90) + (url?.let { " · " + TurnView.domain(it) } ?: ""), 13f, Ui.LAVENDER).apply {
                setPadding(0, a.dp(4), 0, a.dp(4))
                if (url != null) setOnClickListener { openUrl(url) }
            })
        }
    }

    private fun photos(items: List<Cloud.Photo>): View {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(10), 0, a.dp(4)) }
        items.forEach { p ->
            val cell = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val img = ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(Ui.SURFACE, a.dp(14).toFloat())
                clipToOutline = true
                contentDescription = p.description.ifBlank { "Photo" }
                setOnClickListener { openUrl(p.link) }
            }
            cell.addView(img, LinearLayout.LayoutParams(a.dp(210), a.dp(150)))
            cell.addView(a.text("Photo : ${p.author} / Unsplash", 11f, Ui.FAINT).apply {
                setPadding(a.dp(2), a.dp(4), 0, 0)
                setOnClickListener { openUrl(p.link) }
            })
            row.addView(cell, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = a.dp(10) })
            io.execute { load(p.url)?.let { bmp -> main.post { img.setImageBitmap(bmp) } } }
        }
        return HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    /** Image créée par NVIDIA : affichée en grand, toucher = enregistrer dans la galerie (Images/Nox). */
    private fun created(bytes: ByteArray): View {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val w = a.resources.displayMetrics.widthPixels - a.dp(36)
        return ImageView(a).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = Ui.rounded(Ui.SURFACE, a.dp(16).toFloat())
            clipToOutline = true
            contentDescription = "Image créée · touche pour l'enregistrer"
            layoutParams = LinearLayout.LayoutParams(w, -2).apply { topMargin = a.dp(10) }
            setOnClickListener { save(bytes) }
        }
    }

    private fun save(bytes: ByteArray) {
        try {
            val name = "nox_image_${System.currentTimeMillis()}.jpg"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Nox")
            }
            val uri = a.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("refusé")
            a.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            a.toast("Image enregistrée dans Images/Nox")
        } catch (t: Throwable) {
            a.toast("Enregistrement impossible : ${t.message}")
        }
    }

    private fun load(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.inputStream.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 1 }) }.also { c.disconnect() }
    } catch (_: Throwable) { null }

    private fun openUrl(url: String) = try {
        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) { a.toast("Aucun navigateur pour ouvrir le lien") }
}

/** La page API : clé (chiffrée), modèle, test et compteurs, pour chaque service. */
class CloudApisDialog(private val a: MainActivity, private val keys: CloudKeys, private val onSaved: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val fields = HashMap<Cloud.Api, Pair<EditText, EditText?>>()

    fun show() {
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(4), 0, a.dp(4), a.dp(8)) }
        body.addView(a.text("Les clés restent sur ce téléphone, chiffrées. Laisse « modèle » vide : Nox en choisit un bon tout seul.", 13f, Ui.MUTED)
            .apply { setPadding(0, 0, 0, a.dp(10)) })
        Cloud.Api.values().forEach { body.addView(card(it)) }
        NoxDialog.sheet(a)
            .setTitle("API en ligne")
            .setView(ScrollView(a).apply { addView(body) })
            .setNegativeButton("Fermer", null)
            .setPositiveButton("Enregistrer") { _, _ -> saveAll(); a.toast("API enregistrées"); onSaved() }
            .show()
    }

    private fun saveAll() = fields.forEach { (api, f) -> save(api, f) }

    private fun save(api: Cloud.Api, f: Pair<EditText, EditText?>) {
        val k = f.first.text.toString().trim()
        if (k.isNotEmpty()) keys.setKey(api.id, k)
        f.second?.let { keys.setModel(api.id, it.text.toString()) }
    }

    private fun card(api: Cloud.Api): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, a.dp(16).toFloat(), Ui.STROKE, a.dp(1))
            setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(10) }
        }
        val has = keys.has(api.id)
        val u = keys.usage(api.id)
        val dot = if (has) "●" else "○"
        val dotColor = when { !has -> Ui.FAINT; u.calls == 0 && u.errors > 0 -> Ui.DANGER; u.calls > 0 -> Ui.GREEN; else -> Ui.AMBER }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(a.text(dot, 14f, dotColor).apply { setPadding(0, 0, a.dp(8), 0) })
        top.addView(a.text(api.title, 16f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(a.text(api.site, 11.5f, Ui.LAVENDER).apply { setOnClickListener { open("https://" + api.site) } })
        box.addView(top)
        box.addView(a.text(api.hint, 12.5f, Ui.MUTED).apply { setPadding(0, a.dp(2), 0, a.dp(8)) })

        val key = field(if (has) "Clé enregistrée · coller pour remplacer" else "Colle ta clé ici").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = Typeface.MONOSPACE
        }
        box.addView(key)
        var model: EditText? = null
        if (api.llm || api == Cloud.Api.NVIDIA) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(8), 0, 0) }
            model = field(if (api == Cloud.Api.NVIDIA) "modèle d'image : auto (FLUX.1-schnell)" else "modèle : auto").apply { setText(keys.model(api.id)); textSize = 13.5f }
            row.addView(model, LinearLayout.LayoutParams(0, -2, 1f))
            val m = model
            row.addView(a.pillButton("Modèles", filled = false) { pickModel(api, key, m) },
                LinearLayout.LayoutParams(-2, a.dp(44)).apply { marginStart = a.dp(8) })
            box.addView(row)
        }
        fields[api] = key to model

        val result = a.text(usageLine(api), 12f, Ui.FAINT).apply { setPadding(0, a.dp(8), 0, 0) }
        val actions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(8), 0, 0) }
        actions.addView(a.pillButton("Tester") {
            save(api, key to model)
            result.setTextColor(Ui.MUTED); result.text = "Test en cours…"
            Thread {
                val r = try { "✓ " + Cloud.test(api, keys) } catch (t: Throwable) { keys.record(api.id, 0, t.message); "✗ " + (t.message ?: "échec") }
                main.post {
                    result.setTextColor(if (r.startsWith("✓")) Ui.GREEN else Ui.DANGER)
                    result.text = r + "\n" + usageLine(api)
                    model?.setText(keys.model(api.id))
                    onSaved()
                }
            }.start()
        })
        if (has) actions.addView(a.pillButton("Retirer", filled = false) {
            keys.setKey(api.id, ""); keys.setModel(api.id, ""); keys.resetUsage(api.id)
            key.hint = "Colle ta clé ici"; key.setText(""); model?.setText("")
            result.text = "Clé retirée"; onSaved()
        }, LinearLayout.LayoutParams(-2, a.dp(44)).apply { marginStart = a.dp(8) })
        box.addView(actions)
        box.addView(result)
        return box
    }

    private fun usageLine(api: Cloud.Api): String {
        val u = keys.usage(api.id)
        if (u.calls == 0 && u.errors == 0) return if (keys.has(api.id)) "Pas encore utilisé" else "Pas de clé"
        val avg = String.format(Locale.FRANCE, "%.1f", u.avgMs / 1000.0)
        return "${u.calls} appel(s) · $avg s en moyenne · ${u.errors} erreur(s)" + if (u.lastError.isNotBlank()) "\nDernière erreur : ${u.lastError}" else ""
    }

    private fun pickModel(api: Cloud.Api, key: EditText, target: EditText) {
        save(api, key to null)
        if (api == Cloud.Api.NVIDIA) {
            val items = (listOf("auto (FLUX.1-schnell, rapide)") + Cloud.IMAGE_MODELS).toTypedArray<CharSequence>()
            NoxDialog.sheet(a).setTitle("Modèle d'image NVIDIA")
                .setItems(items) { _, i -> target.setText(if (i == 0) "" else Cloud.IMAGE_MODELS[i - 1]) }
                .setNegativeButton("Annuler", null).show()
            return
        }
        if (!keys.has(api.id)) { a.toast("Ajoute d'abord la clé"); return }
        a.toast("Liste des modèles…")
        Thread {
            val r = try { Result.success(Cloud.models(api, keys.key(api.id))) } catch (t: Throwable) { Result.failure(t) }
            main.post {
                r.onSuccess { models ->
                    if (models.isEmpty()) { a.toast("Aucun modèle listé"); return@onSuccess }
                    val items = (listOf("auto (Nox choisit)") + models).toTypedArray<CharSequence>()
                    NoxDialog.sheet(a).setTitle("Modèle ${api.title}")
                        .setItems(items) { _, i -> target.setText(if (i == 0) "" else models[i - 1]) }
                        .setNegativeButton("Annuler", null).show()
                }.onFailure { a.toast("${api.title} : ${it.message}") }
            }
        }.start()
    }

    private fun field(hint: String) = EditText(a).apply {
        this.hint = hint
        setTextColor(Ui.TEXT); setHintTextColor(Ui.FAINT)
        textSize = 14.5f
        isSingleLine = true
        background = Ui.rounded(0xFF0B1020.toInt(), a.dp(12).toFloat(), 0xFF263252.toInt(), a.dp(1))
        setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
    }

    private fun open(url: String) = try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Throwable) {}
}
