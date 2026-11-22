package com.application.nox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder

/**
 * Garde Nox éveillé en arrière-plan : le modèle reste en mémoire, prêt à répondre dès
 * l'appui long sur le bouton power. Notification discrète avec « Parler » et « Quitter ».
 */
class NoxService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            Nox.ui?.finishAndRemoveTask()
            if (Nox.started) Nox.shutdown() else { stopSelf(); return START_NOT_STICKY }
            return START_NOT_STICKY
        }
        if (!Nox.started) Nox.init(this)
        val note = build(this, intent?.getStringExtra(EXTRA_STATUS) ?: lastStatus)
        // Micro inclus : la bulle peut écouter pendant qu'une autre appli est à l'écran
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
            micReady = mic
        } catch (t: Throwable) {
            try {
                startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                micReady = false
                NoxLog.w("APP", "Service sans micro (démarré hors de l'écran) : la bulle ouvrira Nox pour écouter", t)
            } catch (t2: Throwable) {
                NoxLog.w("APP", "Service d'arrière-plan refusé par Android", t2)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        micReady = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "nox_veille"
        private const val ID = 7001
        private const val ACTION_QUIT = "com.application.nox.QUIT"
        private const val EXTRA_STATUS = "status"
        @Volatile var running = false; private set
        /** Le service a le droit au micro en arrière-plan (démarré pendant que Nox était à l'écran). */
        @Volatile var micReady = false; private set
        private var lastStatus = "Nox veille"

        fun start(ctx: Context, status: String) {
            lastStatus = status
            if (!Nox.prefs.background) return
            if (running) {
                // Déjà au premier plan : on met juste la notification à jour (garder le droit au micro)
                try { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, status)) } catch (_: Throwable) {}
                return
            }
            try {
                ctx.startForegroundService(Intent(ctx, NoxService::class.java).putExtra(EXTRA_STATUS, status))
                running = true
            } catch (t: Throwable) {
                NoxLog.w("APP", "Impossible de lancer le service d'arrière-plan", t)
            }
        }

        fun stop(ctx: Context) {
            running = false
            ctx.stopService(Intent(ctx, NoxService::class.java))
        }

        private fun build(ctx: Context, status: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nox en veille", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Nox reste prêt en arrière-plan"
                setShowBadge(false)
            })
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
            val talk = PendingIntent.getActivity(ctx, 1,
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_VOICE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
            val quit = PendingIntent.getService(ctx, 2, Intent(ctx, NoxService::class.java).setAction(ACTION_QUIT), flags)
            val icon = Icon.createWithResource(ctx, ctx.resources.getIdentifier("ic_mic", "drawable", ctx.packageName))
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle("Nox est prêt")
                .setContentText("$status · appui long sur power pour parler")
                .setColor(Ui.ACCENT)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(icon, "Parler", talk).build())
                .addAction(Notification.Action.Builder(icon, "Quitter", quit).build())
                .build()
        }
    }
}
