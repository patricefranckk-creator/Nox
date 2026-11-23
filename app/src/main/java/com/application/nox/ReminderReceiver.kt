package com.application.nox

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification

/** Rappels de Nox : notification à l'heure demandée, même appli fermée. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Rappel de Nox")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        nm.notify(intent.getIntExtra(EXTRA_ID, 1), n)
    }

    companion object {
        private const val CHANNEL = "nox_rappels"
        private const val EXTRA_TEXT = "texte"
        private const val EXTRA_ID = "id"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels", NotificationManager.IMPORTANCE_HIGH))
            }
        }

        fun schedule(ctx: Context, atMillis: Long, text: String) {
            ensureChannel(ctx)
            val id = (atMillis / 1000 % Int.MAX_VALUE).toInt()
            val i = Intent(ctx, ReminderReceiver::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_ID, id)
            val pi = PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = ctx.getSystemService(AlarmManager::class.java)
            // Fenêtre d'1 minute : précis sans exiger la permission « alarmes exactes »
            am.setWindow(AlarmManager.RTC_WAKEUP, atMillis, 60_000L, pi)
            val now = System.currentTimeMillis()
            val kept = upcoming().filter { it.first > now }.toMutableList()
            kept += atMillis to text
            Nox.prefs.reminders = org.json.JSONArray(kept.sortedBy { it.first }.map { org.json.JSONObject().put("at", it.first).put("t", it.second) }).toString()
        }

        /** Rappels encore à venir (heure, texte). */
        fun upcoming(): List<Pair<Long, String>> = try {
            val a = org.json.JSONArray(Nox.prefs.reminders)
            (0 until a.length()).map { a.getJSONObject(it) }.map { it.getLong("at") to it.getString("t") }
                .filter { it.first > System.currentTimeMillis() }
        } catch (_: Throwable) { emptyList() }
    }
}
