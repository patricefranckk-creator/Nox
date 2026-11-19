package com.application.nox

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.application.nox.Ui.dp
import com.application.nox.Ui.pillButton
import com.application.nox.Ui.sectionTitle
import com.application.nox.Ui.text
import java.io.File

/** Gestion des cerveaux de Nox : télécharger, importer, choisir, supprimer. */
class ModelsDialog(private val a: MainActivity) {

    private val store = a.store
    private var urlField: EditText? = null
    private var wasActive = true
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private var dialog: AlertDialog? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (dialog?.isShowing != true) return
            // On ne reconstruit la liste que si un téléchargement bouge, et jamais pendant la saisie d'un lien
            val active = store.downloadingFiles().isNotEmpty() || store.customDownloads().isNotEmpty()
            if ((active || wasActive) && urlField?.hasFocus() != true) render()
            wasActive = active
            handler.postDelayed(this, 800)
        }
    }

    fun show() {
        current = this
        body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(22), a.dp(8), a.dp(22), a.dp(8))
        }
        val sv = ScrollView(a).apply { addView(body) }
        dialog = AlertDialog.Builder(a)
            .setTitle("Cerveau de Nox")
            .setView(sv)
            .setPositiveButton("Fermer", null)
            .setOnDismissListener { handler.removeCallbacks(ticker); if (current === this) current = null }
            .show()
        render()
        handler.postDelayed(ticker, 800)
    }

    private fun render() {
        body.removeAllViews()
        val ram = store.totalRamGb()
        body.addView(a.text(
            String.format("Téléphone : %.1f Go de RAM · %s libres", ram, ModelStore.human(store.freeSpaceBytes())),
            12.5f, Ui.MUTED))
        body.addView(a.text("Tout tourne sur ton téléphone. Internet ne sert qu'à ce téléchargement unique.", 12.5f, Ui.MUTED)
            .apply { setPadding(0, a.dp(2), 0, 0) })

        body.addView(a.sectionTitle("Gemma 4 · modèles « E »"))
        store.catalog.filter { it.id.startsWith("gemma") }.forEach { body.addView(card(it)) }
        body.addView(a.sectionTitle("Autres familles"))
        store.catalog.filterNot { it.id.startsWith("gemma") }.forEach { body.addView(card(it)) }

        val imported = store.localModels().filter { f -> store.catalog.none { it.file == f.name } }
        if (imported.isNotEmpty()) {
            body.addView(a.sectionTitle("Modèles importés"))
            imported.forEach { body.addView(fileCard(it)) }
        }

        val custom = store.customDownloads()
        if (custom.isNotEmpty()) {
            body.addView(a.sectionTitle("Téléchargements perso"))
            custom.forEach { (file, id) -> body.addView(customCard(file, id)) }
        }

        body.addView(a.sectionTitle("Depuis un lien Hugging Face"))
        body.addView(a.text("Colle le lien d'un fichier .litertlm (page du fichier ou lien de téléchargement).", 13f, Ui.MUTED))
        urlField = EditText(a).apply {
            setText(pendingUrl)
            hint = "huggingface.co/…/modele.litertlm"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 13.5f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = Ui.rounded(Ui.SURFACE, a.dp(14).toFloat(), Ui.STROKE, a.dp(1))
            setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { pendingUrl = s?.toString() ?: "" }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        body.addView(urlField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        body.addView(row(a.pillButton("Télécharger ce lien") {
            val err = store.downloadUrl(pendingUrl)
            if (err != null) a.toast(err) else {
                pendingUrl = ""
                a.toast("Téléchargement lancé")
            }
            render()
        }).apply { setPadding(0, a.dp(8), 0, 0) })

        body.addView(a.sectionTitle("Depuis le téléphone"))
        body.addView(a.text("Un .litertlm déjà téléchargé (navigateur, Drive…) ?", 13f, Ui.MUTED))
        body.addView(row(a.pillButton("Importer un fichier .litertlm", filled = false) { pickFile() }).apply { setPadding(0, a.dp(8), 0, 0) })
    }

    private fun card(s: ModelStore.Spec): View {
        val box = cardBox(selected = a.prefs.modelPath == store.fileFor(s).absolutePath && store.isInstalled(s))
        box.addView(a.text(s.title, 16f, Ui.TEXT, bold = true))
        val cpuTag = if (s.cpuOnly) " · CPU" else ""
        box.addView(a.text("${ModelStore.human(s.sizeBytes)}$cpuTag · ${s.pitch}", 13f, Ui.MUTED).apply { setPadding(0, a.dp(4), 0, a.dp(8)) })

        val dlId = store.activeDownloadId(s)
        when {
            dlId >= 0 -> {
                val st = store.statusOf(dlId)
                val got = st?.second ?: 0L
                val tot = (st?.third ?: -1L).takeIf { it > 0 } ?: s.sizeBytes
                val pct = (got * 100 / tot).toInt()
                val pb = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = pct
                    progressTintList = android.content.res.ColorStateList.valueOf(Ui.CYAN)
                }
                box.addView(pb, LinearLayout.LayoutParams(-1, a.dp(8)))
                val label = when (st?.first) {
                    DownloadManager.STATUS_PAUSED -> "En pause (réseau ?) · $pct %"
                    DownloadManager.STATUS_PENDING -> "En attente…"
                    else -> "Téléchargement · $pct % · ${ModelStore.human(got)} / ${ModelStore.human(tot)}"
                }
                box.addView(a.text(label, 12.5f, Ui.MUTED).apply { setPadding(0, a.dp(6), 0, a.dp(6)) })
                box.addView(row(a.pillButton("Annuler", filled = false) { store.cancel(s); render() }))
            }
            store.isInstalled(s) -> {
                val f = store.fileFor(s)
                val active = a.prefs.modelPath == f.absolutePath
                box.addView(row(
                    a.pillButton(if (active) "✓ Actif" else "Utiliser", filled = !active) { if (!active) use(f) },
                    a.pillButton("Supprimer", filled = false) { confirmDelete(f) },
                ))
            }
            else -> {
                checkFinishedDownload(s)
                box.addView(row(a.pillButton("Télécharger") {
                    if (store.freeSpaceBytes() < s.sizeBytes + 300_000_000L) a.toast("Espace insuffisant")
                    else { store.download(s); a.toast("Téléchargement lancé (visible dans les notifications)"); render() }
                }))
            }
        }
        return box
    }

    private fun customCard(file: String, id: Long): View {
        val box = cardBox(false)
        box.addView(a.text(file.removeSuffix(".litertlm"), 15f, Ui.TEXT, bold = true))
        val st = store.statusOf(id)
        val got = st?.second ?: 0L
        val tot = st?.third ?: -1L
        val pct = if (tot > 0) (got * 100 / tot).toInt() else 0
        box.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = pct; isIndeterminate = tot <= 0
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.CYAN)
        }, LinearLayout.LayoutParams(-1, a.dp(8)).apply { topMargin = a.dp(8) })
        val label = when (st?.first) {
            DownloadManager.STATUS_FAILED -> "Échec (lien privé, protégé par licence ou invalide)"
            DownloadManager.STATUS_SUCCESSFUL -> "Terminé"
            DownloadManager.STATUS_PAUSED -> "En pause (réseau ?) · $pct %"
            DownloadManager.STATUS_PENDING -> "En attente…"
            else -> if (tot > 0) "$pct % · ${ModelStore.human(got)} / ${ModelStore.human(tot)}" else ModelStore.human(got)
        }
        box.addView(a.text(label, 12.5f, Ui.MUTED).apply { setPadding(0, a.dp(6), 0, a.dp(6)) })
        box.addView(row(a.pillButton("Annuler", filled = false) { store.cancelCustom(file); render() }))
        return box
    }

    private fun checkFinishedDownload(s: ModelStore.Spec) {
        val id = a.getSharedPreferences("nox_dl", Activity.MODE_PRIVATE).getLong(s.id, -1)
        if (id < 0) return
        val st = store.statusOf(id) ?: return
        if (st.first == DownloadManager.STATUS_FAILED) {
            a.getSharedPreferences("nox_dl", Activity.MODE_PRIVATE).edit().remove(s.id).apply()
            a.toast("Le téléchargement a échoué, réessaie (Wi-Fi conseillé)")
        }
    }

    private fun fileCard(f: File): View {
        val active = a.prefs.modelPath == f.absolutePath
        val box = cardBox(active)
        box.addView(a.text(f.nameWithoutExtension, 15f, Ui.TEXT, bold = true))
        box.addView(a.text(ModelStore.human(f.length()), 12.5f, Ui.MUTED).apply { setPadding(0, a.dp(2), 0, a.dp(8)) })
        box.addView(row(
            a.pillButton(if (active) "✓ Actif" else "Utiliser", filled = !active) { if (!active) use(f) },
            a.pillButton("Supprimer", filled = false) { confirmDelete(f) },
        ))
        return box
    }

    private fun use(f: File) {
        a.prefs.modelPath = f.absolutePath
        dialog?.dismiss()
        a.loadModel()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(a)
            .setMessage("Supprimer ${f.name} (${ModelStore.human(f.length())}) ?")
            .setPositiveButton("Supprimer") { _, _ ->
                val wasActive = a.prefs.modelPath == f.absolutePath
                store.delete(f)
                if (wasActive) { a.prefs.modelPath = ""; a.toast("Modèle actif supprimé") }
                render()
            }
            .setNegativeButton("Annuler", null).show()
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        a.startActivityForResult(i, REQ_IMPORT)
    }

    private fun cardBox(selected: Boolean) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
        background = Ui.rounded(if (selected) 0xFF1C1940.toInt() else Ui.SURFACE, a.dp(18).toFloat(),
            if (selected) Ui.ACCENT else Ui.STROKE, a.dp(1))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(10) }
    }

    private fun row(vararg views: View) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = a.dp(10) })
        }
    }

    companion object {
        const val REQ_IMPORT = 4242
        private var current: ModelsDialog? = null
        /** Survit au rafraîchissement de la liste (toutes les 0,8 s) pendant la saisie. */
        private var pendingUrl = ""

        fun onImportResult(a: MainActivity, resultCode: Int, data: Intent?) {
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) return
            val prog = a.text("Copie du modèle… 0 %", 15f, Ui.TEXT).apply { setPadding(a.dp(24), a.dp(20), a.dp(24), a.dp(10)) }
            val d = AlertDialog.Builder(a).setTitle("Import").setView(prog).setCancelable(false).show()
            a.store.import(uri, progress = { p -> prog.text = "Copie du modèle… $p %" }) { file, err ->
                d.dismiss()
                if (file == null) { a.toast(err ?: "Échec"); return@import }
                a.prefs.modelPath = file.absolutePath
                current?.dialog?.dismiss()
                a.loadModel()
            }
        }
    }
}
