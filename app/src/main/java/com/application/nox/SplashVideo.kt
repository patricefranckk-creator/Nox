package com.application.nox

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView

/**
 * Vidéo d'allumage de l'écran de démarrage (assets/holo/nox_startup.mp4) : jouée une fois, sans son,
 * figée sur sa dernière image. Décodage matériel, tout est libéré à la fermeture de l'écran.
 */
class SplashVideo(ctx: Context) {

    val view = TextureView(ctx).apply { isOpaque = false }
    private val assets = ctx.assets
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var wanted = false
    private var released = false

    init {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                if (wanted) start()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    fun play() {
        wanted = true
        if (surface != null) start()
    }

    private fun start() {
        val s = surface ?: return
        if (released || player != null) return
        try {
            val p = MediaPlayer()
            player = p
            assets.openFd(ASSET).use { fd -> p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            p.setSurface(s)
            p.isLooping = false
            p.setVolume(0f, 0f)
            p.setOnPreparedListener { it.start() }
            p.setOnErrorListener { _, what, extra -> NoxLog.w("SPLASH", "Vidéo d'allumage illisible ($what/$extra)"); true }
            p.prepareAsync()
        } catch (t: Throwable) {
            NoxLog.w("SPLASH", "Vidéo d'allumage indisponible : ${t.message}")
        }
    }

    fun release() {
        released = true
        player?.let { try { it.reset(); it.release() } catch (_: Throwable) {} }
        player = null
        surface?.release()
        surface = null
    }

    private companion object {
        const val ASSET = "holo/nox_startup.mp4"
    }
}
