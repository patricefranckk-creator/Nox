package com.application.nox

import android.widget.LinearLayout
import com.application.nox.Ui.dp
import com.application.nox.Ui.field
import com.application.nox.Ui.text
import org.json.JSONObject
import java.io.File

/**
 * Le menu de Nox, copié sur celui de Lia : panneau qui monte du bas, trois favoris en tuiles
 * (appui long pour les changer), puis tout le reste rangé par thème.
 */
class MenuSheet(private val a: MainActivity) {

    private class Entry(val id: String, val emoji: String, val label: String, val run: () -> Unit)

    private var dialog: NoxDialog? = null

    private val entries: List<Entry> = listOf(
        Entry("vocal", "🎙️", "Vocal") { a.openVoicePage(listen = true) },
        Entry("bibliotheque", "📚", "Bibliothèque") { SkillsDialog(a).show() },
        Entry("listes", "📝", "Listes") { showLists() },
        Entry("nouvelle", "💬", "Nouvelle") { a.newConversation() },
        Entry("jauges", "📊", "Jauges") { a.gauges.open() },
        Entry("freekipedia", "🌐", "Freekipedia") { SkillsDialog.pickFreekipedia(a) },
        Entry("contenu", "🔎", "Contenu RAG") { LibraryExplorer(a).show() },
        Entry("modeles", "🧠", "Modèles") { ModelsDialog(a).show() },
        Entry("reglages", "⚙️", "Réglages") { SettingsDialog(a).show() },
        Entry("journal", "📈", "Journal") { JournalDialog(a).show() },
        Entry("autorisations", "🔐", "Accès") { PermissionsDialog(a).show() },
    )

    fun show() {
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        body.addView(NoxDialog.section(a, "Mes favoris · appui long pour changer"))
        val tiles = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val favs = a.prefs.favorites.mapNotNull { id -> entries.firstOrNull { it.id == id } }.take(3)
        favs.forEachIndexed { slot, e ->
            tiles.addView(NoxDialog.tile(a, e.emoji, e.label) { go(e.run) }.apply {
                setOnLongClickListener { chooseFavorite(slot); true }
            }, LinearLayout.LayoutParams(0, a.dp(100), 1f).apply { if (slot > 0) marginStart = a.dp(10) })
        }
        body.addView(tiles)

        section(body, "Moi",
            Triple("👤", "Mon profil") { profile() },
            Triple("✨", "Personnalité de Nox") { personality() },
            Triple("🧠", "Ma mémoire") { SkillsDialog(a).show() },
            Triple("📄", "Mes documents") { SkillsDialog(a).show() },
        )
        section(body, "Conversation",
            Triple("💬", "Nouvelle conversation") { a.newConversation() },
            Triple("🎙️", "Vocal") { a.openVoicePage(listen = true) },
            Triple("🙌", if (a.prefs.handsFree) "Mains libres · activé" else "Mains libres") { a.toggleHandsFree() },
            Triple("🗣️", "Assistant vocal (bouton Power)") { PermissionsDialog(a).show() },
        )
        section(body, "Connaissances",
            Triple("🔎", "Contenu de la bibliothèque") { LibraryExplorer(a).show() },
            Triple("🌐", "Importer Freekipedia") { SkillsDialog.pickFreekipedia(a) },
            Triple("⚡", "Compétences") { SkillsDialog(a).show() },
        )
        section(body, "Réglages",
            Triple("●", "Modèles") { ModelsDialog(a).show() },
            Triple("⚙️", "Réglages") { SettingsDialog(a).show() },
            Triple("🔐", "Autorisations") { PermissionsDialog(a).show() },
            Triple("📈", "Journal de bord") { JournalDialog(a).show() },
            Triple("ℹ️", "Aide & infos · v${AppInfo.version(a)}") { helpAndInfo() },
        )
        body.addView(NoxDialog.row(a, "⏻", "Fermer l'app", shaded = false, labelColor = Ui.DANGER) {
            dialog?.dismiss()
            NoxDialog.Builder(a).setTitle("Fermer Nox ?").setIcon("⏻")
                .setMessage("Le modèle est déchargé de la mémoire et Nox ne répond plus au bouton power jusqu'au prochain lancement.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Fermer l'app") { _, _ -> a.closeApp() }
                .show()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = a.dp(14) })

        dialog = NoxDialog.sheet(a).setTitle("Menu").setView(body).show()
    }

    private fun section(body: LinearLayout, title: String, vararg rows: Triple<String, String, () -> Unit>) {
        body.addView(NoxDialog.section(a, title))
        rows.forEachIndexed { i, (emoji, label, run) ->
            body.addView(NoxDialog.row(a, emoji, label, i % 2 == 0) { go(run) })
        }
    }

    private fun go(run: () -> Unit) {
        dialog?.dismiss()
        run()
    }

    private fun chooseFavorite(slot: Int) {
        val labels = entries.map { "${it.emoji}  ${it.label}" }.toTypedArray()
        NoxDialog.Builder(a).setTitle("Favori n°${slot + 1}").setItems(labels) { _, w ->
            val favs = a.prefs.favorites.toMutableList()
            while (favs.size < 3) favs += entries[favs.size].id
            favs[slot] = entries[w].id
            a.prefs.favorites = favs
            dialog?.dismiss()
            show()
        }.setNegativeButton("Annuler", null).show()
    }

    private fun profile() {
        val name = a.field("Ton prénom").apply { setText(a.prefs.userName) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(a.text("Nox t'appelle par ton prénom et s'en sert pour te saluer.", 14f, Ui.MUTED))
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
        }
        NoxDialog.Builder(a).setTitle("Mon profil").setIcon("👤").setView(box)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                a.prefs.userName = name.text.toString().trim()
                a.applyConversationSettings()
            }.show()
    }

    private fun personality() {
        val txt = a.field("Comment Nox doit parler et se comporter", lines = 8).apply { setText(a.prefs.persona) }
        NoxDialog.Builder(a).setTitle("Personnalité").setIcon("✨").setView(txt)
            .setNeutralButton("Par défaut") { _, _ -> a.prefs.persona = Prefs.DEFAULT_PERSONA; a.applyConversationSettings() }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                a.prefs.persona = txt.text.toString().trim().ifEmpty { Prefs.DEFAULT_PERSONA }
                a.applyConversationSettings()
            }.show()
    }

    private fun showLists() {
        val f = File(a.filesDir, "notes.json")
        val all = try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (all.length() == 0) box.addView(a.text("Aucune liste pour l'instant. Dis par exemple : « ajoute du pain à la liste de courses ».", 15f, Ui.MUTED))
        all.keys().forEach { k ->
            val arr = all.getJSONArray(k)
            box.addView(NoxDialog.section(a, "$k · ${arr.length()}"))
            box.addView(a.text((0 until arr.length()).joinToString("\n") { "•  " + arr.getString(it) }.ifEmpty { "(vide)" }, 16f, Ui.TEXT))
        }
        NoxDialog.Builder(a).setTitle("Listes").setIcon("📝").setView(box).setPositiveButton("Fermer", null).show()
    }

    private fun helpAndInfo() {
        NoxDialog.Builder(a).setTitle("Aide & infos").setIcon("ℹ️")
            .setItems(arrayOf("❓  Aide", "ⓘ  À propos · v${AppInfo.version(a)}")) { _, w ->
                if (w == 0) HelpDialog.show(a) else AboutDialog.show(a)
            }.show()
    }
}
