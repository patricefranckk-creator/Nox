package com.application.nox

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/**
 * Mode « Hologramme HQ » : les vidéos du pack importé, une par état de Nox (démarrage, écoute,
 * réflexion, parole). Deux lecteurs seulement : celui qu'on voit et celui qui prépare l'état suivant,
 * fondu de l'un à l'autre. Décodage matériel par MediaPlayer, son des vidéos coupé, tout est libéré
 * dès que la vue est cachée. Rien ne passe par Gemma.
 */
class HoloView(ctx: Context) : FrameLayout(ctx) {

    var onFail: ((String) -> Unit)? = null

    var state: NeuronView.State = NeuronView.State.IDLE
        set(v) { field = v; if (isShown) sync() }

    private var pack: HoloAssets.Pack? = null
    private val slots = arrayOf(Slot(), Slot())
    private var front = 0
    /** Fichier et vitesse affichés (ou en préparation). */
    private var showing: String? = null
    private var switching = false
    private var token = 0

    init {
        setBackgroundColor(Color.BLACK)
        for (s in slots) addView(s.view, LayoutParams(0, 0, Gravity.CENTER))
    }

    /** Mode hologramme choisi : la vidéo de démarrage est rejouée une fois. */
    fun activate() {
        startupPlayed = false
        if (isShown) { releaseAll(); sync() }
    }

    /** Pack réimporté : on relit le manifeste et on repart de zéro. */
    fun reload() {
        releaseAll()
        pack = null
        startupPlayed = false
        if (isShown) sync()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // vidéo carrée : le plus grand carré qui tient, centré, sans déformation, bords noirs
        val side = minOf(w, h)
        for (s in slots) s.view.layoutParams = LayoutParams(side, side, Gravity.CENTER)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) sync() else releaseAll()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isShown) sync() else releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    private fun target(p: HoloAssets.Pack): Pair<HoloAssets.Clip, Float>? {
        // le démarrage ne retarde jamais une réponse : dès que Nox écoute, réfléchit ou parle, on passe à l'état
        if (!startupPlayed && state == NeuronView.State.IDLE) p.clip("startup")?.let { return it to it.speed }
        val name = when (state) {
            NeuronView.State.LISTENING -> "listening"
            NeuronView.State.THINKING -> "thinking"
            NeuronView.State.SPEAKING -> "speaking"
            else -> "idle"
        }
        p.clip(name)?.let { return it to it.speed }
        // pas de vidéo de repos : la boucle d'écoute (comme le prévoit le pack)
        return p.clip("listening")?.let { it to it.speed }
    }

    /** Met la vidéo en accord avec l'état ; si un fondu est en cours, on y revient à sa fin. */
    private fun sync() {
        if (switching) return
        val p = pack ?: HoloAssets.load(context).also { pack = it }
        val (clip, speed) = target(p) ?: run { onFail?.invoke("Pack hologramme incomplet : réimporte-le (appui long sur « Hologramme »)"); return }
        if (clip.state != "startup") startupPlayed = true
        val key = clip.id + "@" + speed
        if (key == showing) return
        val cur = slots[front]
        // même vidéo, autre vitesse (repos ↔ écoute) : on ajuste sans couper
        if (showing?.substringBeforeLast('@') == clip.id && cur.setSpeed(speed)) { showing = key; return }
        switching = true
        showing = key
        val next = slots[1 - front]
        val t = ++token
        next.view.alpha = 0f
        next.view.bringToFront()
        var faded = false
        fun fade() {
            if (faded || t != token) return
            faded = true
            next.view.animate().alpha(1f).setDuration(p.transitionMs).withEndAction {
                if (t != token) return@withEndAction
                cur.stop()
                cur.view.alpha = 0f
                front = 1 - front
                switching = false
                sync()
            }.start()
        }
        next.play(clip, speed,
            firstFrame = { post { fade() } },
            ended = { post { if (t == token || clip.state == "startup") { startupPlayed = true; if (!switching) sync() } } },
            failed = { msg -> post { if (t == token) { switching = false; showing = null; onFail?.invoke(msg) } } })
        // certains décodeurs n'annoncent pas la première image : on fond quand même
        postDelayed({ fade() }, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun releaseAll() {
        token++
        switching = false
        showing = null
        for (s in slots) { s.view.animate().cancel(); s.view.alpha = 0f; s.release() }
    }

    /** Un lecteur : sa surface d'affichage et son MediaPlayer (réutilisé, jamais plus de deux). */
    private inner class Slot {
        val view = TextureView(context).apply { isOpaque = false; alpha = 0f }
        private var surface: Surface? = null
        private var player: MediaPlayer? = null
        private var pending: (() -> Unit)? = null

        init {
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    surface = Surface(st)
                    pending?.let { pending = null; it() }
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    release()
                    surface?.release(); surface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }

        fun play(clip: HoloAssets.Clip, speed: Float, firstFrame: () -> Unit, ended: () -> Unit, failed: (String) -> Unit) {
            val s = surface ?: run { pending = { play(clip, speed, firstFrame, ended, failed) }; return }
            val p = player ?: MediaPlayer().also { player = it }
            try {
                p.reset()
                p.setSurface(s)
                val f = clip.file
                if (f != null) p.setDataSource(f.absolutePath)
                else context.assets.openFd(clip.asset!!).use { fd -> p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                p.isLooping = clip.loop
                p.setVolume(0f, 0f)
                p.setOnPreparedListener { mp ->
                    muteAudio(mp)
                    if (speed != 1f) setSpeed(speed)
                    mp.start()
                }
                p.setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) firstFrame(); false }
                p.setOnCompletionListener { ended() }
                p.setOnErrorListener { _, what, extra ->
                    NoxLog.w("HOLO", "Lecture de ${clip.name} impossible (erreur $what/$extra)")
                    failed("Vidéo ${clip.name} illisible sur ce téléphone")
                    true
                }
                p.prepareAsync()
            } catch (t: Throwable) {
                NoxLog.e("HOLO", "Préparation de ${clip.name} impossible", t)
                failed("Vidéo ${clip.name} illisible : ${t.message}")
            }
        }

        fun setSpeed(speed: Float): Boolean {
            val p = player ?: return false
            return try { p.playbackParams = p.playbackParams.setSpeed(speed); true } catch (_: Throwable) { false }
        }

        /** Le son des vidéos ne doit jamais gêner la voix de Nox : piste audio retirée si possible. */
        private fun muteAudio(mp: MediaPlayer) {
            try {
                mp.trackInfo.forEachIndexed { i, t -> if (t.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) mp.deselectTrack(i) }
            } catch (_: Throwable) {}
        }

        fun stop() {
            pending = null
            try { player?.reset() } catch (_: Throwable) {}
        }

        fun release() {
            pending = null
            player?.let { try { it.reset(); it.release() } catch (_: Throwable) {} }
            player = null
        }
    }

    companion object {
        /** La vidéo de démarrage n'est jouée qu'une fois par lancement de Nox. */
        private var startupPlayed = false
        private const val FIRST_FRAME_TIMEOUT_MS = 600L
    }
}
