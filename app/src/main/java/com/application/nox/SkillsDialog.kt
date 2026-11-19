package com.application.nox

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.application.nox.Ui.dp
import com.application.nox.Ui.pillButton
import com.application.nox.Ui.sectionTitle
import com.application.nox.Ui.text

/** Panneau « ⚡ » : les compétences de Nox et ce qu'il a retenu de toi. */
class SkillsDialog(private val a: MainActivity) {

    private lateinit var body: LinearLayout
    private var changed = false

    fun show() {
        body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(22), a.dp(4), a.dp(22), a.dp(12))
        }
        render()
        AlertDialog.Builder(a)
            .setTitle("Compétences & mémoire")
            .setView(ScrollView(a).apply { addView(body) })
            .setPositiveButton("Fermer", null)
            .setOnDismissListener { if (changed) a.applyConversationSettings() }
            .show()
    }

    private fun render() {
        body.removeAllViews()

        val status = when {
            !a.engine.ready -> "Le modèle n'est pas chargé."
            a.engine.skillsActive -> "✓ Branchées sur ${a.engine.modelLabel}. Demande simplement : « mets un minuteur de 10 minutes », « retiens que je suis végétarien », « ajoute du pain aux courses »…"
            a.prefs.skillsEnabled -> "Ce modèle ne sait pas utiliser d'outils. Gemma 4 et Qwen3 le font très bien."
            else -> "Désactivées."
        }
        body.addView(a.text(status, 13f, Ui.MUTED).apply { setPadding(0, a.dp(6), 0, a.dp(4)) })

        @Suppress("DEPRECATION")
        val sw = Switch(a).apply {
            text = "Activer les compétences"
            setTextColor(Ui.TEXT)
            isChecked = a.prefs.skillsEnabled
            thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, v -> a.prefs.skillsEnabled = v; changed = true }
        }
        body.addView(sw)

        body.addView(a.sectionTitle("Ce que Nox sait faire"))
        a.skills.all.forEach { s ->
            body.addView(a.text(s.title, 14.5f, Ui.TEXT, bold = true).apply { setPadding(0, a.dp(6), 0, 0) })
            body.addView(a.text(s.description, 12.5f, Ui.MUTED))
        }

        body.addView(a.sectionTitle("Ce que Nox a retenu de toi"))
        val facts = a.skills.memory.all()
        if (facts.isEmpty()) {
            body.addView(a.text("Rien pour l'instant. Dis-lui « retiens que… ».", 13f, Ui.MUTED))
        } else {
            body.addView(a.text("Touche un souvenir pour l'effacer.", 12f, Ui.MUTED))
            facts.forEachIndexed { i, f ->
                body.addView(a.text("•  $f", 14f, Ui.TEXT).apply {
                    setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9))
                    background = Ui.ripple(Ui.rounded(Ui.SURFACE, a.dp(12).toFloat()))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(6) }
                    setOnClickListener {
                        AlertDialog.Builder(a).setMessage("Oublier « $f » ?")
                            .setPositiveButton("Oublier") { _, _ -> a.skills.memory.removeAt(i); changed = true; render() }
                            .setNegativeButton("Annuler", null).show()
                    }
                })
            }
            body.addView(a.pillButton("Tout oublier", filled = false) {
                AlertDialog.Builder(a).setMessage("Effacer toute la mémoire longue de Nox ?")
                    .setPositiveButton("Effacer") { _, _ ->
                        a.skills.memory.forgetAll(); changed = true; render()
                    }.setNegativeButton("Annuler", null).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(10) } })
        }
        body.addView(a.text("Tout est stocké uniquement sur ce téléphone.", 11.5f, Ui.MUTED).apply { setPadding(0, a.dp(14), 0, 0) })
    }
}
