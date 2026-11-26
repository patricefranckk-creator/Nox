package com.application.nox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.pillButton
import com.application.nox.Ui.sectionTitle
import com.application.nox.Ui.text

/** Version, numéro de build, commit : lus à l'exécution (aucune constante à mettre à jour à la main). */
object AppInfo {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    fun build(ctx: Context): Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    } catch (_: Exception) { 0 }

    private fun res(ctx: Context, name: String): String {
        val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return if (id == 0) "?" else ctx.getString(id)
    }

    fun commit(ctx: Context) = res(ctx, "nox_commit")
    fun buildDate(ctx: Context) = res(ctx, "nox_build_date")

    const val ENGINE = "LiteRT-LM 0.18.1 (Google AI Edge)"
}

// ====================================================================== journal

/** Visionneuse du journal : filtres, couleurs, résumé des performances, export et partage. */
class JournalDialog(private val a: MainActivity) {

    private enum class Filter(val label: String) { ALL("Tout"), PERF("Perfs"), ERR("Erreurs"), ENGINE("Moteur"), TOOLS("Outils & RAG") }

    private var filter = Filter.ALL
    private lateinit var view: TextView
    private lateinit var summary: TextView
    private lateinit var scroll: ScrollView
    private val chips = mutableMapOf<Filter, TextView>()

    fun show() {
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(14), a.dp(4), a.dp(14), 0) }

        summary = a.text("", 12.5f, Ui.CYAN).apply { setPadding(a.dp(4), 0, a.dp(4), a.dp(8)) }
        body.addView(summary)

        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        Filter.values().forEach { f ->
            val c = a.text(f.label, 12.5f, Ui.TEXT).apply {
                setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6))
                setOnClickListener { filter = f; render() }
            }
            chips[f] = c
            bar.addView(c, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = a.dp(6) })
        }
        body.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(bar) })

        view = TextView(a).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(Ui.TEXT)
            setTextIsSelectable(true)
            setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
            setHorizontallyScrolling(false)
        }
        scroll = ScrollView(a).apply {
            background = Ui.rounded(0xFF1E1D25.toInt(), a.dp(12).toFloat(), 0x22FFFFFF, a.dp(1))
            addView(view)
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.55).toInt()).apply { topMargin = a.dp(8) })

        val actions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(10), 0, a.dp(4)) }
        actions.addView(a.pillButton("Partager") { share() })
        actions.addView(a.pillButton("Copier", filled = false) {
            a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Journal Nox", header() + NoxLog.fullText()))
            a.toast("Journal copié")
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
        actions.addView(a.pillButton("Effacer", filled = false) { NoxLog.clear(); render() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
        body.addView(a.pillButton("🩺 Diagnostic complet", filled = false) { Diagnostic(a).show() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, a.dp(44)).apply { bottomMargin = a.dp(6) }
        })
        body.addView(actions)

        @Suppress("DEPRECATION")
        val sw = android.widget.Switch(a).apply {
            text = "Mode détaillé (chaque envoi, chaque recherche)"
            setTextColor(Ui.TEXT); textSize = 13f
            isChecked = NoxLog.verbose
            setOnCheckedChangeListener { _, v -> NoxLog.verbose = v; a.prefs.verboseLog = v }
        }
        body.addView(sw)

        val d = NoxDialog.sheet(a)
            .setTitle("Journal de bord")
            .setView(body)
            .setPositiveButton("Fermer", null)
            .setOnDismissListener { NoxLog.listener = null }
            .show()
        NoxLog.listener = { if (d.isShowing) render() }
        render()
    }

    private fun matches(e: NoxLog.Entry) = when (filter) {
        Filter.ALL -> true
        Filter.PERF -> e.lvl == NoxLog.Lvl.P
        Filter.ERR -> e.lvl == NoxLog.Lvl.E || e.lvl == NoxLog.Lvl.W || e.cat == "CRASH"
        Filter.ENGINE -> e.cat == "MOTEUR" || e.cat == "GEN" || e.cat == "SYS"
        Filter.TOOLS -> e.cat == "OUTIL" || e.cat == "RAG"
    }

    private fun render() {
        chips.forEach { (f, c) ->
            c.background = Ui.rounded(if (f == filter) Ui.ACCENT else Ui.CARD, a.dp(14).toFloat())
            c.setTextColor(if (f == filter) 0xFFFFFFFF.toInt() else Ui.TEXT)
        }
        summary.text = "⏱ " + NoxLog.summary()
        val list = NoxLog.entries().filter { matches(it) }.takeLast(600)
        val sb = SpannableStringBuilder()
        for (e in list) {
            val start = sb.length
            sb.append(e.line()).append('\n')
            val color = when (e.lvl) {
                NoxLog.Lvl.E -> 0xFFFF6B81.toInt(); NoxLog.Lvl.W -> 0xFFFFC266.toInt(); NoxLog.Lvl.P -> 0xFF5FE3B0.toInt()
                NoxLog.Lvl.D -> Ui.MUTED; else -> Ui.TEXT
            }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (e.lvl == NoxLog.Lvl.E) sb.setSpan(StyleSpan(Typeface.BOLD), start, start + 22.coerceAtMost(sb.length - start), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (list.isEmpty()) sb.append("Rien pour ce filtre.")
        view.text = sb
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun header() = "Nox ${AppInfo.version(a)} (build ${AppInfo.build(a)}, ${AppInfo.commit(a)}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
        "Résumé : ${NoxLog.summary()}\n\n"

    private fun share() {
        val txt = header() + NoxLog.fullText().takeLast(400_000)
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Journal Nox ${AppInfo.version(a)}")
            .putExtra(Intent.EXTRA_TEXT, txt), "Partager le journal"))
    }
}

// ====================================================================== aide

object HelpDialog {
    fun show(a: MainActivity) {
        val b = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, a.dp(12)) }
        fun sec(t: String) = b.addView(a.sectionTitle(t))
        fun item(title: String, desc: String) {
            b.addView(a.text(title, 14.5f, Ui.TEXT, bold = true).apply { setPadding(0, a.dp(6), 0, 0) })
            b.addView(a.text(desc, 13f, Ui.MUTED))
        }

        sec("Parler à Nox")
        item("Le micro, en bas à droite", "Ouvre la page vocale : le médaillon de Nox s'entoure de vert, Nox t'écoute. Touche Nox quand tu as fini (sinon il détecte ton silence). Pendant qu'il parle ou réfléchit, toucher Nox l'interrompt.")
        item("Bouton power", "Appui long sur le bouton marche/arrêt, de n'importe où (même écran verrouillé) : Nox s'ouvre directement à l'écoute. À activer une fois dans Menu › Autorisations › Bouton power.")
        item("Appui long sur Nox", "Mode mains libres : Nox réécoute après chaque réponse, l'écran reste allumé.")
        item("Écrire", "« ou écris ici… » en bas : la flèche envoie, le carré arrête la réponse.")
        item("Photo et galerie", "L'appareil photo ou une image de la galerie, puis ta question : « qu'est-ce que c'est ? », « lis ce document »…")
        item("Sous chaque message", "Copier, relire à voix haute, ou modifier ce que tu as dit. Les relances bleues s'envoient d'un toucher.")
        item("Couper la voix", "Le bouton haut-parleur en haut à droite : Nox répond par écrit seulement.")

        sec("Piloter le téléphone")
        item("Toutes les applis", "Avec l'accès « Pilotage des applis », Nox lit l'écran et agit : « Ouvre WhatsApp et écris à Marie que j'arrive », « Dans les réglages, active le mode avion ». Il relit l'écran après chaque geste et te demande confirmation avant d'envoyer, payer ou supprimer.")
        item("Notifications", "Avec l'accès « Lire les notifications » : « Qu'est-ce que j'ai reçu ? », « Réponds à Paul : OK pour 18h », « Efface les notifications ».")
        item("Agenda", "« Qu'est-ce que j'ai demain ? » · « Ajoute dentiste jeudi à 15h ».")
        item("En arrière-plan", "Nox reste chargé (notification discrète « Nox est prêt ») : la réponse au bouton power est immédiate. « Quitter » dans la notification ou « Fermer l'app » dans le menu le libère.")

        sec("Exemples de demandes")
        item("Actions", "« Mets un minuteur de 10 minutes » · « Réveille-moi à 6h30 » · « Rappelle-moi dans 20 minutes de sortir le linge » · « Allume la lampe » · « Mets du Stromae » · « Morceau suivant »")
        item("Contacts & trajets", "« Appelle Marie » · « Envoie un SMS à Paul pour dire que j'arrive » · « Emmène-moi à la gare de Pau »")
        item("Listes & mémoire", "« Ajoute du pain et du lait aux courses » · « Qu'est-ce qu'il y a sur ma liste ? » · « Retiens que je suis allergique aux noix » · « Oublie ça »")
        item("Bibliothèque", "Ajoute tes documents (Menu › Mes documents) ou partage un texte vers Nox, puis : « Qu'est-ce que disait le compte rendu sur… ? ». Avec EmbeddingGemma, même ta question parlée cherche directement dans la bibliothèque.")
        item("Connaissances (Freekipedia)", "Dans Freekipedia, crée le fichier puis touche « Envoyer à Nox » (ou Menu › Importer Freekipedia ici). Les vecteurs sont déjà calculés avec le modèle de Nox : rien à recalculer. Nox y cherche tout seul et montre « Dans ta bibliothèque » ce qu'il a relu.")
        item("Contenu de la bibliothèque", "Menu › Contenu de la bibliothèque : toutes les fiches, documents, listes et conversations que Nox peut relire, chaque extrait tel quel, son état de vectorisation, et « Tester une recherche » pour voir ce que Nox retrouverait pour une question.")

        sec("L'écran")
        item("Le visage de Nox en haut à gauche", "Ouvre le menu (favoris, profil, mémoire, documents, modèles, réglages, journal…). Son anneau suit l'état de Nox.")
        item("Le bouton jauge", "RAM, Nox, CPU, GPU et batterie en direct ; « Détail RAM » et « Journal » en bas du panneau.")
        item("Journal de bord", "Performances (temps avant le 1er mot, vitesse, RAG, outils) et erreurs. Bouton Partager pour me l'envoyer.")
        item("Diagnostic complet", "Menu → Diagnostic complet : Nox teste tout (modèle, réponses, bibliothèque, web, voix, pilotage…) et écrit un rapport dans Téléchargements/Nox à m'envoyer.")

        sec("Si ça coince")
        item("Plantage au chargement", "Réglages › Moteur : CPU, contexte 2K, turbo désactivé. Après un plantage GPU, Nox repasse en CPU tout seul.")
        item("Lent avant le 1er mot", "Contexte 2K, couper la vue, et fermer les autres applis. Le journal (filtre Perfs) montre où part le temps.")
        item("Il ne comprend pas ma voix", "Réglages › Voix : essaie l'autre mode (oreille Gemma ↔ reconnaissance du téléphone).")
        item("Pilotage grisé", "Android 13+ : Infos de l'appli › ⋮ › Autoriser les paramètres restreints, puis réactive l'accès.")

        NoxDialog.sheet(a).setTitle("Aide").setView(ScrollView(a).apply { addView(b) }).setPositiveButton("OK", null).show()
    }
}

// ====================================================================== à propos

object AboutDialog {
    fun show(a: MainActivity) {
        val b = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(22), a.dp(8), a.dp(22), a.dp(12)); gravity = Gravity.CENTER_HORIZONTAL }
        b.addView(a.text("Nox", 30f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f })
        b.addView(a.text("Assistant vocal 100 % local", 13.5f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        b.addView(a.text("Version ${AppInfo.version(a)}", 16f, Ui.CYAN, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, a.dp(12), 0, 0) })
        b.addView(a.text("build n° ${AppInfo.build(a)} · ${AppInfo.buildDate(a)} · commit ${AppInfo.commit(a)}", 12f, Ui.MUTED).apply { gravity = Gravity.CENTER })

        val am = a.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

        fun row(k: String, v: String) {
            val l = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(5), 0, a.dp(5)) }
            l.addView(a.text(k, 13f, Ui.MUTED), LinearLayout.LayoutParams(0, -2, 0.42f))
            l.addView(a.text(v, 13f, Ui.TEXT), LinearLayout.LayoutParams(0, -2, 0.58f))
            b.addView(l, LinearLayout.LayoutParams(-1, -2))
        }
        b.addView(a.sectionTitle("En ce moment").apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
        row("Cœur", if (a.engine.ready) "${a.engine.modelLabel} · ${a.engine.backendLabel}" else "non chargé")
        row("Oreille / Vue", "${if (a.engine.audioReady) "oui" else "non"} / ${if (a.engine.visionReady) "oui" else "non"}")
        row("Compétences", if (a.engine.skillsActive) "${a.skills.all.size} actives" else "inactives")
        row("Bibliothèque", if (a.library.semantic) "hybride (mots-clés + sens)" else "mots-clés")
        row("Moteur", AppInfo.ENGINE)

        b.addView(a.sectionTitle("Ton téléphone").apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
        row("Appareil", "${Build.MANUFACTURER} ${Build.MODEL}")
        row("Puce", soc)
        row("Mémoire vive", NoxLog.gb(mi.totalMem))
        row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        b.addView(a.sectionTitle("Confidentialité").apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
        b.addView(a.text("Tout tourne sur ce téléphone : modèle, voix, mémoire, bibliothèque, journal. Internet ne sert qu'à télécharger les modèles (et aux recherches web que tu demandes explicitement).", 13f, Ui.TEXT))

        b.addView(a.sectionTitle("Crédits").apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
        b.addView(a.text("Gemma 4 et EmbeddingGemma 2 © Google DeepMind (Apache 2.0) · LiteRT-LM © Google (Apache 2.0) · Visage 3D : maillage canonique MediaPipe © Google (Apache 2.0) · Construit avec Claude.", 12.5f, Ui.MUTED))

        NoxDialog.Builder(a).setTitle("À propos").setIcon("ⓘ").setView(ScrollView(a).apply { addView(b) }).setPositiveButton("OK", null).show()
    }
}
