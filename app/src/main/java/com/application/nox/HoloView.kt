package com.application.nox

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/**
 * Mode « Hologramme » : les vidéos du pack (livré dans l'appli ou importé), une par état de Nox (démarrage, écoute,
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
    private var wasShown = false
    /** Pendant la parole : l'autre lecteur joue la boucle bouche fermée, montrée dès que la voix se tait. */
    private var restKey: String? = null
    private var silentMs = 0f
    private var lastTick = 0L

    init {
        for (s in slots) addView(s.view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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

    private val edge = Paint()

    /** Bords du carré fondus au noir : le sol lumineux de la vidéo ne s'arrête plus net sur un trait. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val v = slots[0].view
        val l = v.left.toFloat(); val t = v.top.toFloat(); val r = v.right.toFloat(); val b = v.bottom.toFloat()
        if (r <= l) return
        val f = (b - t) * EDGE
        fun band(x0: Float, y0: Float, x1: Float, y1: Float, gx0: Float, gy0: Float, gx1: Float, gy1: Float) {
            edge.shader = LinearGradient(gx0, gy0, gx1, gy1, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(x0, y0, x1, y1, edge)
        }
        band(l, b - f, r, b, 0f, b, 0f, b - f)
        band(l, t, r, t + f, 0f, t, 0f, t + f)
        band(l, t, l + f, b, l, 0f, l + f, 0f)
        band(r - f, t, r, b, r, 0f, r - f, 0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // vidéo carrée : le plus grand carré qui tient, centré (gravité des enfants), sans déformation, bords noirs
        val side = minOf(measuredWidth, measuredHeight)
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        for (s in slots) s.view.measure(spec, spec)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        shownChanged()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        shownChanged()
    }

    /** Chaque ouverture de la page vocale rallume l'hologramme : la vidéo de démarrage est rejouée. */
    private fun shownChanged() {
        val now = isShown && windowVisibility == VISIBLE
        if (now && !wasShown) {
            startupPlayed = false
            NoxLog.i("HOLO", "Page vocale ouverte : démarrage de l'hologramme")
        }
        wasShown = now
        if (now) sync() else releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    private fun target(p: HoloAssets.Pack): Pair<HoloAssets.Clip, Float>? {
        // l'allumage se joue même si le micro s'ouvre (la vidéo ne retarde rien) ; une réponse l'interrompt
        if (!startupPlayed && state != NeuronView.State.THINKING && state != NeuronView.State.SPEAKING)
            p.clip("startup")?.let { return it to it.speed }
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
        if (key == showing) { keepRest(p); return }
        // la bouche fermée recouvrait la parole : c'est elle qu'on voit, elle devient le point de départ du fondu
        if (restKey != null && restKey != key && slots[1 - front].view.alpha > 0.5f) {
            slots[front].stop(); slots[front].view.alpha = HIDDEN
            front = 1 - front
            showing = restKey
            restKey = null
        }
        val cur = slots[front]
        // même vidéo, autre vitesse (repos ↔ écoute) : on ajuste sans couper
        if (showing?.substringBeforeLast('@') == clip.id && cur.setSpeed(speed)) { showing = key; return }
        switching = true
        showing = key
        NoxLog.d("HOLO", "→ ${HoloAssets.LABELS[clip.state] ?: clip.state} (${clip.name})")
        val next = slots[1 - front]
        val t = ++token
        val reuse = restKey == key && next.key == key
        restKey = null
        next.view.animate().cancel()
        if (!reuse) next.view.alpha = HIDDEN
        next.view.bringToFront()
        var faded = false
        fun fade() {
            if (faded || t != token) return
            faded = true
            next.view.animate().alpha(1f).setDuration(p.transitionMs).withEndAction {
                if (t != token) return@withEndAction
                cur.stop()
                cur.view.alpha = HIDDEN
                front = 1 - front
                switching = false
                sync()
            }.start()
        }
        // la boucle bouche fermée tournait déjà sous la parole : on la garde, sans recharger
        if (reuse) { fade(); return }
        next.play(clip, speed, key,
            firstFrame = { post { fade() } },
            ended = { post { if (t == token || clip.state == "startup") { startupPlayed = true; if (!switching) sync() } } },
            failed = { msg -> post { if (t == token) { switching = false; showing = null; onFail?.invoke(msg) } } })
        // certains décodeurs n'annoncent pas la première image : on fond quand même
        postDelayed({ fade() }, FIRST_FRAME_TIMEOUT_MS)
    }

    /**
     * Pendant la parole, l'autre lecteur fait tourner la boucle d'écoute (bouche fermée) presque invisible.
     * Dès que la voix marque une pause, elle recouvre la parole ; dès que la voix reprend, elle s'efface.
     */
    private fun keepRest(p: HoloAssets.Pack) {
        if (state != NeuronView.State.SPEAKING || switching) return
        val rest = (p.clip("listening") ?: p.clip("idle")) ?: return
        val key = rest.id + "@" + rest.speed
        val other = slots[1 - front]
        if (restKey != key) {
            restKey = key
            other.view.animate().cancel()
            other.view.alpha = HIDDEN
            other.view.bringToFront()
            other.play(rest, rest.speed, key, firstFrame = {}, ended = {}, failed = { post { restKey = null } })
        }
        lastTick = 0L
        silentMs = 0f
        postOnAnimation(restTick)
    }

    private val restTick = object : Runnable {
        override fun run() {
            if (restKey == null || state != NeuronView.State.SPEAKING || switching || !isShown) return
            val now = System.nanoTime()
            val dt = if (lastTick == 0L) 0.016f else ((now - lastTick) / 1e9f).coerceIn(0f, 0.1f)
            lastTick = now
            FaceBrain.step(dt)
            silentMs = if (FaceBrain.voiced) 0f else silentMs + dt * 1000f
            val v = slots[1 - front].view
            // pause de plus de 150 ms : bouche fermée en 120 ms ; la voix reprend : la parole revient en 90 ms
            val want = if (silentMs > PAUSE_MS) 1f else HIDDEN
            val rate = if (want > v.alpha) dt / 0.12f else dt / 0.09f
            v.alpha = if (want > v.alpha) minOf(want, v.alpha + rate) else maxOf(want, v.alpha - rate)
            postOnAnimation(this)
        }
    }

    private fun releaseAll() {
        token++
        switching = false
        showing = null
        restKey = null
        for (s in slots) { s.view.animate().cancel(); s.view.alpha = HIDDEN; s.release() }
    }

    /** Un lecteur : sa surface d'affichage et son MediaPlayer (réutilisé, jamais plus de deux). */
    private inner class Slot {
        val view = TextureView(context).apply { isOpaque = false; alpha = HIDDEN }
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

        /** Ce que ce lecteur joue (fichier@vitesse), ou null. */
        var key: String? = null

        fun play(clip: HoloAssets.Clip, speed: Float, playKey: String, firstFrame: () -> Unit, ended: () -> Unit, failed: (String) -> Unit) {
            key = playKey
            val s = surface ?: run { pending = { play(clip, speed, playKey, firstFrame, ended, failed) }; return }
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
            key = null
            try { player?.reset() } catch (_: Throwable) {}
        }

        fun release() {
            pending = null
            key = null
            player?.let { try { it.reset(); it.release() } catch (_: Throwable) {} }
            player = null
        }
    }

    companion object {
        /**
         * Lecteur en attente : presque invisible mais jamais à 0, sinon Android ne le dessine pas et sa
         * surface vidéo n'est jamais créée (la vidéo suivante ne pourrait pas démarrer).
         */
        private const val HIDDEN = 0.01f
        /** La vidéo de démarrage n'est jouée qu'une fois par lancement de Nox. */
        private var startupPlayed = false
        private const val FIRST_FRAME_TIMEOUT_MS = 600L
        private const val PAUSE_MS = 150f
        /** Part du carré fondue au noir sur chaque bord. */
        private const val EDGE = 0.07f
    }
}
