package com.application.nox

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tuile des réglages rapides « Parler à Nox » : ouvre Nox en écoute, depuis n'importe où. */
class NoxTile : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Parler à Nox"
            updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_VOICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        NoxLog.i("TUILE", "Tuile « Parler à Nox » touchée")
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
