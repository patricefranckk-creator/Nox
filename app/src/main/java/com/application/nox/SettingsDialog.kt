package com.application.nox

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.pillButton
import com.application.nox.Ui.sectionTitle
import com.application.nox.Ui.text
import java.util.Locale

class SettingsDialog(private val a: MainActivity) {

    private val p = a.prefs

    fun show() {
        // Valeurs de départ pour savoir quoi recharger
        val oldBackend = p.backend
        val oldTurbo = p.turbo
        val oldCtx = p.contextTokens
        val oldVision = p.vision
        val oldPersona = p.persona
        val oldTemp = p.temperature
        val oldName = p.userName
        var changedPrompt = false

        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, a.dp(4), 0, a.dp(12))
        }

        // ---------------- Toi & Nox
        body.addView(a.sectionTitle("Toi & Nox"))
        val name = edit(p.userName, "Ton prénom (optionnel)", lines = 1)
        body.addView(name)
        body.addView(label("Personnalité de Nox"))
        val persona = edit(p.persona, "Décris qui est Nox", lines = 6)
        body.addView(persona)
        body.addView(a.pillButton("Personnalité d'origine", filled = false) { persona.setText(Prefs.DEFAULT_PERSONA) }
            .apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8) } })

        val tempLabel = label("")
        body.addView(tempLabel)
        val temp = seek(0, 150, (p.temperature * 100).toInt()) { v ->
            tempLabel.text = "Créativité : ${fmt(v / 100f)} " + when {
                v < 40 -> "· précis"; v < 95 -> "· équilibré"; else -> "· imaginatif"
            }
        }
        body.addView(temp)

        // ---------------- Bibliothèque & web
        body.addView(a.sectionTitle("Bibliothèque & web"))
        val ragFirst = switch("Répondre d'abord depuis la bibliothèque (sans solliciter le modèle)", p.ragFirst)
        body.addView(ragFirst)
        val web = switch("Recherche sur internet (« cherche sur le net… »)", p.webSearch)
        body.addView(web)

        // ---------------- Voix
        body.addView(a.sectionTitle("Voix"))
        body.addView(label("Comment Nox t'écoute"))
        val listen = radios(listOf(
            "android" to "Reconnaissance du téléphone (recommandé) : commandes directes, heure, recherche web et bibliothèque à l'oral, plus rapide, moins de RAM",
            "native" to "Oreille Gemma : le son va directement dans le modèle (comprend le ton), mais sans ces raccourcis",
        ), p.listenMode)
        body.addView(listen)
        body.addView(a.text("L'oreille Gemma ne marche qu'avec Gemma 4 / 3n (E2B, E4B). Sinon Nox bascule tout seul sur la reconnaissance du téléphone.",
            12f, Ui.MUTED).apply { setPadding(0, a.dp(4), 0, a.dp(6)) })
        val oldListen = p.listenMode
        body.addView(label("Patience : silence avant que Nox réponde"))
        val patience = radios(listOf("1200" to "1,2 s · réactif", "2000" to "2 s · normal", "3000" to "3 s · posé", "5000" to "5 s · je réfléchis en parlant"), p.patienceMs.toString())
        body.addView(patience)
        body.addView(label("Durée maximale d'un message vocal"))
        val maxL = radios(listOf("30" to "30 s", "90" to "1 min 30", "180" to "3 min"), p.maxListenSec.toString())
        body.addView(maxL)
        val autoSpeak = switch("Lire les réponses à voix haute", p.autoSpeak)
        body.addView(autoSpeak)
        val handsFree = switch("Mains libres (réécoute automatique)", p.handsFree)
        body.addView(handsFree)

        val voices = a.voiceOut.frenchVoices()
        val names = listOf("Automatique (meilleure voix locale)") + voices.map { v ->
            val q = if (v.quality >= 400) "★ " else ""
            "$q${v.name.replace("fr-fr-x-", "").replace("-local", "")}"
        }
        body.addView(label(if (voices.isEmpty()) "Aucune voix française hors-ligne trouvée (installe-la dans Réglages › Synthèse vocale)" else "Voix"))
        val spinner = Spinner(a).apply {
            adapter = object : ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, names) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Ui.TEXT) }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection((voices.indexOfFirst { it.name == p.voiceName } + 1).coerceAtLeast(0))
        }
        body.addView(spinner)

        val rateLabel = label("")
        body.addView(rateLabel)
        val rate = seek(50, 200, (p.speechRate * 100).toInt()) { v -> rateLabel.text = "Débit : ${fmt(v / 100f)}×" }
        body.addView(rate)
        val pitchLabel = label("")
        body.addView(pitchLabel)
        val pitch = seek(50, 150, (p.pitch * 100).toInt()) { v -> pitchLabel.text = "Hauteur : ${fmt(v / 100f)}" }
        body.addView(pitch)

        fun commitVoice() {
            p.voiceName = if (spinner.selectedItemPosition <= 0) "" else voices[spinner.selectedItemPosition - 1].name
            p.speechRate = rate.progress / 100f
            p.pitch = pitch.progress / 100f
            a.voiceOut.applyVoice()
        }
        body.addView(a.pillButton("▶ Tester la voix", filled = false) {
            commitVoice()
            a.voiceOut.speakAll("Salut${if (name.text.isNotBlank()) " " + name.text.trim() else ""} ! C'est Nox. Voilà ma voix, ici, sans aucun serveur.")
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8) } })

        // ---------------- Arrière-plan & pilotage
        body.addView(a.sectionTitle("Arrière-plan & pilotage"))
        val bg = switch("Garder Nox prêt en arrière-plan (bouton power instantané)", p.background)
        body.addView(bg)
        val confirm = switch("Demander confirmation avant d'envoyer, payer ou supprimer dans une appli", p.confirmSensitive)
        body.addView(confirm)
        body.addView(a.pillButton("Autorisations…", filled = false) { PermissionsDialog(a).show() }
            .apply { layoutParams = LinearLayout.LayoutParams(-2, a.dp(44)).apply { topMargin = a.dp(8) } })

        // ---------------- Moteur
        body.addView(a.sectionTitle("Moteur (performances)"))
        val backend = radios(listOf("gpu" to "GPU Adreno (rapide)", "cpu" to "CPU (secours)"), p.backend)
        body.addView(backend)
        val running = if (a.engine.ready) a.engine.backendLabel.ifBlank { "—" } else "chargement en cours"
        body.addView(a.text("Moteur actuel : $running" + if (p.gpuBad.isEmpty()) "" else " · GPU écartés après plantage : ${p.gpuBad.joinToString()}",
            12f, if (p.backend == "gpu" && running.startsWith("CPU")) Ui.AMBER else Ui.MUTED).apply { setPadding(0, a.dp(2), 0, a.dp(6)) })
        val turbo = switch("Turbo MTP (décodage spéculatif)", p.turbo)
        body.addView(turbo)
        val vision = switch("Vue 📷 : Gemma regarde tes photos (+ ~300 Mo de RAM)", p.vision)
        body.addView(vision)
        body.addView(label("Mémoire de travail (contexte)"))
        val ctx = radios(listOf("2048" to "2K · le plus rapide", "4096" to "4K · conseillé", "8192" to "8K · longues discussions"), p.contextTokens.toString())
        body.addView(ctx)
        body.addView(a.text(
            "Les changements de moteur rechargent le modèle (quelques secondes). Si l'appli se ferme au chargement, passe en CPU ou en contexte 2K.",
            12f, Ui.MUTED).apply { setPadding(0, a.dp(8), 0, 0) })

        var saved = false
        var cancelled = false
        fun save() {
            if (saved || cancelled) return
            saved = true
            p.userName = name.text.toString().trim()
            p.persona = persona.text.toString().trim().ifBlank { Prefs.DEFAULT_PERSONA }
            p.temperature = temp.progress / 100f
            p.ragFirst = ragFirst.isChecked
            p.webSearch = web.isChecked
            p.autoSpeak = autoSpeak.isChecked
            p.handsFree = handsFree.isChecked
            commitVoice()
            p.backend = checkedTag(backend) ?: "gpu"
            if (p.backend == "gpu" && oldBackend != "gpu") p.gpuBad = emptySet()
            p.turbo = turbo.isChecked
            p.vision = vision.isChecked
            p.contextTokens = checkedTag(ctx)?.toIntOrNull() ?: 4096
            p.listenMode = checkedTag(listen) ?: "android"
            p.patienceMs = checkedTag(patience)?.toIntOrNull() ?: 2000
            p.maxListenSec = checkedTag(maxL)?.toIntOrNull() ?: 90
            p.background = bg.isChecked
            p.confirmSensitive = confirm.isChecked

            val reload = p.backend != oldBackend || p.turbo != oldTurbo || p.contextTokens != oldCtx || p.listenMode != oldListen || p.vision != oldVision
            val reconv = changedPrompt || p.persona != oldPersona || p.temperature != oldTemp || p.userName != oldName
            when {
                reload -> { a.applyLightSettings(); a.loadModel() }
                reconv -> a.applyConversationSettings()
                else -> a.applyLightSettings()
            }
        }

        NoxDialog.sheet(a)
            .setTitle("Réglages")
            .setView(ScrollView(a).apply { addView(body) })
            .setNegativeButton("Annuler") { _, _ -> cancelled = true }
            .setPositiveButton("Enregistrer") { _, _ -> save() }
            // ✕, retour ou glissement : on enregistre aussi (seul « Annuler » abandonne)
            .setOnDismissListener { save() }
            .show()
    }

    // ---------------------------------------------------------------- widgets

    private fun fmt(f: Float) = String.format(Locale.FRANCE, "%.2f", f)

    private fun label(s: String) = a.text(s, 13.5f, Ui.TEXT).apply { setPadding(0, a.dp(10), 0, a.dp(2)) }

    private fun edit(value: String, hint: String, lines: Int) = EditText(a).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(Ui.MUTED)
        setTextColor(Ui.TEXT)
        textSize = 14.5f
        gravity = Gravity.TOP or Gravity.START
        if (lines > 1) { minLines = 3; maxLines = lines } else isSingleLine = true
        background = Ui.rounded(Ui.CARD, a.dp(16).toFloat(), 0x22FFFFFF, a.dp(1))
        setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
    }

    private fun seek(min: Int, max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(a).apply {
        this.min = min
        this.max = max
        progress = value.coerceIn(min, max)
        progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
        onChange(progress)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) = onChange(v)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    @Suppress("DEPRECATION")
    private fun switch(label: String, checked: Boolean) = Switch(a).apply {
        text = label
        setTextColor(Ui.TEXT)
        textSize = 14.5f
        isChecked = checked
        setPadding(0, a.dp(8), 0, a.dp(8))
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Ui.ACCENT, 0xFF9A98B0.toInt()))
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x884FA6FF.toInt(), 0x55FFFFFF))
    }

    private fun radios(options: List<Pair<String, String>>, selected: String) = RadioGroup(a).apply {
        orientation = RadioGroup.VERTICAL
        options.forEach { (tag, label) ->
            val rb = RadioButton(a).apply {
                id = View.generateViewId()
                text = label
                this.tag = tag
                setTextColor(Ui.TEXT)
                buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            }
            addView(rb)
            if (tag == selected) check(rb.id)
        }
    }

    private fun checkedTag(g: RadioGroup): String? = g.findViewById<RadioButton>(g.checkedRadioButtonId)?.tag as? String
}
