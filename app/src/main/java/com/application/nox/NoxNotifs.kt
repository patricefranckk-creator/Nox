package com.application.nox

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Les oreilles de Nox sur les notifications : les lire, y répondre (messageries),
 * les effacer ou les ouvrir. Tout reste dans le téléphone.
 */
class NoxNotifs : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        NoxLog.i("NOTIF", "Accès aux notifications actif")
    }

    override fun onListenerDisconnected() {
        instance = null
        NoxLog.i("NOTIF", "Accès aux notifications coupé")
    }

    private var listed: List<StatusBarNotification> = emptyList()

    private fun current(): List<StatusBarNotification> =
        (activeNotifications ?: emptyArray()).filter { sbn ->
            val n = sbn.notification
            sbn.packageName != packageName && n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                (title(sbn).isNotBlank() || body(sbn).isNotBlank())
        }.sortedByDescending { it.postTime }

    fun read(app: String?): String {
        var list = current()
        if (!app.isNullOrBlank()) {
            val q = Text.fold(app)
            list = list.filter { Text.fold(appLabel(it.packageName)).contains(q) }
        }
        listed = list.take(MAX)
        if (listed.isEmpty()) return if (app.isNullOrBlank()) "Aucune notification." else "Aucune notification de « $app »."
        val hm = SimpleDateFormat("HH'h'mm", Locale.FRANCE)
        return listed.mapIndexed { i, s ->
            val canReply = replyAction(s) != null
            "[${i + 1}] ${appLabel(s.packageName)} · ${hm.format(Date(s.postTime))} · ${title(s)} : ${body(s).take(220)}" +
                if (canReply) " (on peut répondre)" else ""
        }.joinToString("\n")
    }

    fun reply(index: Int, text: String): String {
        val s = pick(index) ?: return "Numéro de notification inconnu : relis-les d'abord."
        val action = replyAction(s) ?: return "Cette notification ne permet pas de répondre."
        val inputs = action.remoteInputs ?: return "Réponse impossible."
        val intent = Intent()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        action.actionIntent.send(this, 0, intent)
        return "Réponse envoyée à ${title(s)} (${appLabel(s.packageName)})."
    }

    fun open(index: Int): String {
        val s = pick(index) ?: return "Numéro de notification inconnu : relis-les d'abord."
        val pi = s.notification.contentIntent ?: return "Cette notification ne s'ouvre pas."
        if (Build.VERSION.SDK_INT >= 34) {
            val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(this, 0, null, null, null, null, opts.toBundle())
        } else pi.send()
        return "Ouverte : ${title(s)}."
    }

    fun dismiss(index: Int?): String {
        if (index == null) { cancelAllNotifications(); return "Notifications effacées." }
        val s = pick(index) ?: return "Numéro de notification inconnu : relis-les d'abord."
        cancelNotification(s.key)
        return "Notification effacée."
    }

    private fun pick(i: Int): StatusBarNotification? {
        if (listed.isEmpty()) read(null)
        return listed.getOrNull(i - 1)
    }

    private fun replyAction(s: StatusBarNotification): Notification.Action? =
        s.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun title(s: StatusBarNotification) = s.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    private fun body(s: StatusBarNotification): String {
        val e = s.notification.extras
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) return lines.takeLast(4).joinToString(" / ")
        return (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().replace('\n', ' ')
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    companion object {
        @Volatile var instance: NoxNotifs? = null; private set
        private const val MAX = 15

        fun isOn(ctx: Context): Boolean {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            return nm.isNotificationListenerAccessGranted(ComponentName(ctx, NoxNotifs::class.java))
        }
    }
}
