package com.application.nox

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle

/**
 * Point d'entrée de l'assistant numérique : appui long sur le bouton power (ou geste assistant).
 * Réveille l'écran si besoin et ouvre directement la page vocale de Nox, à l'écoute.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        NoxLog.i("APP", "Déclenché comme assistant (${intent?.action})")
        val go = {
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = go()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else go()
    }
}
