package com.application.nox

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Widget d'écran d'accueil : le neurone de Nox, un appui pour lui parler. */
class NoxWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val voice = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_VOICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 8, voice, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_nox)
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(id, v)
        }
    }
}
