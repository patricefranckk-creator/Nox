package com.application.nox

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import com.application.nox.Ui.dp

/**
 * Le centre des autorisations : tout ce dont Nox a besoin, avec l'état de chaque accès
 * et le bon écran Android en un toucher. Présenté au premier lancement, rouvrable depuis le menu.
 */
class PermissionsDialog(private val a: MainActivity) {

    private class Item(val emoji: String, val title: String, val why: String, val granted: () -> Boolean, val grant: () -> Unit)

    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var dialog: NoxDialog? = null

    private val runtime = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
    }

    private val items = listOf(
        Item("🎙", "Micro", "pour t'écouter", { has(Manifest.permission.RECORD_AUDIO) }) { ask(Manifest.permission.RECORD_AUDIO) },
        Item("🔔", "Notifications", "rappels et Nox en veille",
            { Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS) }) { ask(Manifest.permission.POST_NOTIFICATIONS) },
        Item("👥", "Contacts", "appeler, écrire à quelqu'un", { has(Manifest.permission.READ_CONTACTS) }) { ask(Manifest.permission.READ_CONTACTS) },
        Item("📅", "Agenda", "lire tes rendez-vous",
            { has(Manifest.permission.READ_CALENDAR) }) { ask(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) },
        Item("🖐", "Pilotage des applis", "lire l'écran, toucher, écrire", { NoxAccess.isOn(a) }) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            a.toast("Choisis « Nox » puis active-le. Si c'est grisé : Infos de l'appli › ⋮ › Autoriser les paramètres restreints")
        },
        Item("📨", "Lire les notifications", "messages reçus, répondre", { NoxNotifs.isOn(a) }) {
            val i = if (Build.VERSION.SDK_INT >= 30)
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(a, NoxNotifs::class.java).flattenToString())
            else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            open(i)
        },
        Item("⏻", "Bouton power", "appui long = Nox", { isAssistant() }) {
            open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            a.toast("Assistant numérique par défaut › choisis « Nox », puis active l'appui long sur le bouton marche/arrêt")
        },
        Item("🔋", "Sans limite de batterie", "rester prêt en arrière-plan", {
            a.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(a.packageName)
        }) {
            @Suppress("BatteryLife")
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
        },
    )

    fun show() {
        current = this
        render()
        dialog = NoxDialog.sheet(a)
            .setTitle("Autorisations")
            .setView(body)
            .setOnDismissListener { if (current === this) current = null }
            .show()
    }

    fun refresh() = render()

    private fun render() {
        body.removeAllViews()
        val missing = items.count { !it.granted() }
        body.addView(TextView(a).apply {
            text = if (missing == 0) "Tout est prêt : Nox a tous ses accès. Rien ne sort du téléphone."
            else "Nox fonctionne sans, mais chaque accès débloque des compétences. Touche une ligne pour l'activer. Rien ne sort du téléphone."
            setTextColor(0xFFC9CCD6.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Ui.MEDIUM
            setLineSpacing(0f, 1.25f)
            setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(8))
        })
        items.forEachIndexed { i, it ->
            val ok = it.granted()
            body.addView(NoxDialog.row(a, it.emoji, it.title, i % 2 == 0, if (ok) "✓ actif" else "à activer", sub = it.why) {
                if (!ok) it.grant() else a.toast("${it.title} : déjà actif")
            })
        }
        val actions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(16), 0, a.dp(4)) }
        actions.addView(NoxDialog.button(a, "Infos de l'appli", NoxDialog.Style.OUTLINE) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
        }, LinearLayout.LayoutParams(0, a.dp(48), 1f))
        if (runtime.any { !has(it) }) actions.addView(NoxDialog.button(a, "Tout autoriser", NoxDialog.Style.PRIMARY) {
            ask(*runtime.filter { !has(it) }.toTypedArray())
        }, LinearLayout.LayoutParams(0, a.dp(48), 1f).apply { marginStart = a.dp(10) })
        body.addView(actions)
        body.addView(TextView(a).apply {
            text = "Android 13+ : pour une appli installée hors Play Store, « Pilotage » et « Lire les notifications » sont d'abord grisés. " +
                "Ouvre Infos de l'appli › ⋮ › Autoriser les paramètres restreints, puis reviens ici."
            setTextColor(Ui.FAINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); typeface = Ui.BOLD
            setPadding(a.dp(4), a.dp(10), a.dp(4), 0)
        })
    }

    private fun has(p: String) = a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun ask(vararg p: String) = a.requestPermissions(arrayOf(*p), MainActivity.REQ_PERMS_CENTER)

    private fun open(i: Intent) = try { a.startActivity(i) } catch (_: Throwable) {
        a.startActivity(Intent(Settings.ACTION_SETTINGS))
    }

    private fun isAssistant(): Boolean = try {
        a.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } catch (_: Throwable) {
        Settings.Secure.getString(a.contentResolver, "assistant")?.startsWith(a.packageName) == true
    }

    companion object {
        var current: PermissionsDialog? = null
    }
}
