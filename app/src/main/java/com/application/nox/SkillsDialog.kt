package com.application.nox

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

    companion object {
        const val REQ_DOC = 4343
        const val REQ_FK = 4344

        fun pickFreekipedia(a: MainActivity) {
            @Suppress("DEPRECATION")
            a.startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FK)
        }

        /** Copie le fichier .db choisi puis l'intègre à la bibliothèque. */
        fun importFreekipedia(a: MainActivity, uri: android.net.Uri) {
            val prog = NoxDialog.Builder(a).setTitle("Freekipedia").setMessage("Lecture du fichier…").setCancelable(false).show()
            Thread {
                try {
                    val tmp = java.io.File(a.cacheDir, "freekipedia_import.db")
                    a.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } } ?: error("lecture impossible")
                    a.runOnUiThread { prog.setMessage("Intégration des fiches…") }
                    a.library.importFreekipedia(tmp) { r, err ->
                        prog.dismiss()
                        if (r == null) a.toast("Import Freekipedia impossible : $err")
                        else NoxDialog.Builder(a).setTitle("Freekipedia intégré")
                            .setMessage("${r.fiches} fiches (${r.nouvelles} nouvelles, ${r.maj} remplacées)\n${r.passages} passages ajoutés à la bibliothèque\n${r.info}\n\n" +
                                if (a.library.semantic) "La vectorisation par EmbeddingGemma se fait en fond (elle se met en pause quand Nox parle)."
                                else "Recherche par mots-clés active. Installe EmbeddingGemma 2 (◉) pour chercher aussi par le sens.")
                            .setPositiveButton("OK", null).show()
                    }
                } catch (t: Throwable) {
                    a.runOnUiThread { prog.dismiss(); a.toast("Import impossible : ${t.message}") }
                }
            }.start()
        }

        /** Lit un document texte choisi par l'utilisateur et l'ajoute à la bibliothèque. */
        fun importDocument(a: MainActivity, uri: android.net.Uri) {
            Thread {
                try {
                    var name = "document"
                    a.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && i >= 0) name = c.getString(i) ?: name
                    }
                    val raw = a.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("lecture impossible")
                    require(raw.size < 3_000_000) { "fichier trop gros (3 Mo max)" }
                    var text = String(raw, Charsets.UTF_8)
                    if (name.endsWith(".html", true) || name.endsWith(".htm", true) || name.endsWith(".xml", true))
                        text = text.replace(Regex("(?s)<(script|style).*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
                            .replace("&nbsp;", " ").replace("&amp;", "&")
                    val title = name.substringBeforeLast('.')
                    a.library.addDocument(title, text) { n -> a.toast("« $title » ajouté à la bibliothèque ($n extrait${if (n > 1) "s" else ""})") }
                } catch (t: Throwable) {
                    a.runOnUiThread { a.toast("Import impossible : ${t.message}") }
                }
            }.start()
        }
    }

    private lateinit var body: LinearLayout
    private var changed = false

    fun show() {
        body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(22), a.dp(4), a.dp(22), a.dp(12))
        }
        render()
        NoxDialog.sheet(a)
            .setTitle("Compétences & bibliothèque")
            .setView(ScrollView(a).apply { addView(body) })
            .setPositiveButton("Fermer", null)
            .setOnDismissListener { if (changed) a.applyConversationSettings() }
            .show()
    }

    private fun pasteDialog() {
        val title = android.widget.EditText(a).apply { hint = "Titre (ex : Recette crêpes)"; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED) }
        val body2 = android.widget.EditText(a).apply {
            hint = "Colle ou écris le texte ici"; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); minLines = 5; maxLines = 12
            gravity = android.view.Gravity.TOP
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(22), a.dp(8), a.dp(22), 0); addView(title); addView(body2) }
        NoxDialog.Builder(a).setTitle("Ajouter un texte").setView(box)
            .setPositiveButton("Ajouter") { _, _ ->
                val t = body2.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                val name = title.text.toString().trim().ifEmpty { t.take(40).replace('\n', ' ') }
                a.library.addDocument(name, t) { n -> a.toast("« $name » ajouté ($n extrait${if (n > 1) "s" else ""})"); render() }
            }.setNegativeButton("Annuler", null).show()
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

        // ---------------- Bibliothèque (RAG)
        body.addView(a.sectionTitle("Bibliothèque (ce que Nox peut relire)"))
        body.addView(a.text(a.library.stats(), 12.5f, Ui.CYAN))
        body.addView(a.pillButton("🔎  Voir le contenu") { LibraryExplorer(a).show() }
            .apply { layoutParams = LinearLayout.LayoutParams(-2, a.dp(44)).apply { topMargin = a.dp(8) } })
        if (!a.library.semantic) body.addView(a.text("Astuce : installe EmbeddingGemma 2 (◉) pour une recherche par le sens, pas seulement par mots-clés.", 12f, Ui.MUTED)
            .apply { setPadding(0, a.dp(4), 0, 0) })
        val (fkFiches, fkPassages) = a.library.freekipediaStats()
        body.addView(a.text(if (fkFiches > 0) "🌐  Freekipedia : $fkFiches fiches · $fkPassages passages" else "🌐  Freekipedia : aucune fiche pour l'instant", 14f, Ui.TEXT).apply {
            setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9))
            background = Ui.ripple(Ui.rounded(Ui.CARD, a.dp(12).toFloat(), 0x22FFFFFF, a.dp(1)))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) }
            setOnClickListener {
                val items = if (fkFiches > 0) arrayOf("Importer une mise à jour (.db)", "Retirer toutes les fiches Freekipedia") else arrayOf("Importer une mise à jour (.db)")
                NoxDialog.Builder(a).setTitle("Freekipedia").setItems(items) { _, w ->
                    if (w == 0) pickFreekipedia(a) else a.library.removeFreekipedia { render() }
                }.show()
            }
        })
        val docs = a.library.documents()
        docs.forEach { (title, n) ->
            body.addView(a.text("📄  $title  ·  $n extrait${if (n > 1) "s" else ""}", 14f, Ui.TEXT).apply {
                setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9))
                background = Ui.ripple(Ui.rounded(Ui.CARD, a.dp(12).toFloat()))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(6) }
                setOnClickListener {
                    NoxDialog.Builder(a).setMessage("Retirer « $title » de la bibliothèque ?")
                        .setPositiveButton("Retirer") { _, _ -> a.library.removeSource("doc:$title") { render() } }
                        .setNegativeButton("Annuler", null).show()
                }
            })
        }
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(10), 0, 0) }
        btns.addView(a.pillButton("Ajouter un document") {
            @Suppress("DEPRECATION")
            a.startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json", "application/xml")), REQ_DOC)
        })
        btns.addView(a.pillButton("Coller un texte", filled = false) { pasteDialog() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(10) })
        body.addView(btns)
        body.addView(a.text("Formats texte : .txt .md .csv .json .html. Tu peux aussi partager un texte vers Nox depuis n'importe quelle appli.", 11.5f, Ui.MUTED)
            .apply { setPadding(0, a.dp(6), 0, 0) })

        @Suppress("DEPRECATION")
        val swRag = Switch(a).apply {
            text = "Consulter automatiquement la bibliothèque"
            setTextColor(Ui.TEXT); isChecked = a.prefs.ragAuto
            thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, v -> a.prefs.ragAuto = v }
        }
        body.addView(swRag)
        @Suppress("DEPRECATION")
        val swChats = Switch(a).apply {
            text = "Se souvenir des anciennes conversations"
            setTextColor(Ui.TEXT); isChecked = a.prefs.rememberChats
            thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, v -> a.prefs.rememberChats = v }
        }
        body.addView(swChats)
        body.addView(a.pillButton("Effacer les conversations mémorisées", filled = false) {
            NoxDialog.Builder(a).setMessage("Effacer tous les échanges mémorisés de la bibliothèque ?")
                .setPositiveButton("Effacer") { _, _ -> a.library.clearConversations { render() } }
                .setNegativeButton("Annuler", null).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(6) } })

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
                    background = Ui.ripple(Ui.rounded(Ui.CARD, a.dp(12).toFloat()))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(6) }
                    setOnClickListener {
                        NoxDialog.Builder(a).setMessage("Oublier « $f » ?")
                            .setPositiveButton("Oublier") { _, _ -> a.skills.memory.removeAt(i); changed = true; render() }
                            .setNegativeButton("Annuler", null).show()
                    }
                })
            }
            body.addView(a.pillButton("Tout oublier", filled = false) {
                NoxDialog.Builder(a).setMessage("Effacer toute la mémoire longue de Nox ?")
                    .setPositiveButton("Effacer") { _, _ ->
                        a.skills.memory.forgetAll(); changed = true; render()
                    }.setNegativeButton("Annuler", null).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(10) } })
        }
        body.addView(a.text("Tout est stocké uniquement sur ce téléphone.", 11.5f, Ui.MUTED).apply { setPadding(0, a.dp(14), 0, 0) })
    }
}
