package com.application.nox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Le visage holographique de Nox (étape 1) : dessiné à la main, sans modèle 3D ni IA en plus.
 * Respire, cligne, bouge le regard, réagit aux états réels de Nox (écoute, réflexion, parole),
 * prend l'émotion de la phrase en cours et articule les mots que la voix prononce vraiment.
 * Tout est interpolé ; le dessin s'arrête quand le visage n'est pas affiché.
 */
class FaceView(ctx: Context) : View(ctx) {

    var state = NeuronView.State.IDLE

    /** Émotion visée (Emotion.*) : le visage y glisse en douceur. */
    var emotion = Emotion.NEUTRE

    fun setLevel(v: Float) { level = v.coerceIn(0f, 1f) }

    // ---------------------------------------------------------------- diction (mots réellement prononcés)

    private class Shape(val open: Float, val width: Float, val ms: Float)

    private val shapes = ArrayDeque<Shape>()
    private var shapeLeft = 0f
    private var lastWordAt = 0L
    private var talking = false

    /** La voix commence une phrase : émotion de la phrase. */
    fun sentence(text: String) {
        emotion = Emotion.of(text)
        talking = true
    }

    /** La voix prononce ce mot maintenant : on en tire les formes de bouche, voyelle par voyelle. */
    fun word(w: String, rate: Float) {
        lastWordAt = SystemClock.uptimeMillis()
        val f = Text.fold(w.lowercase())
        val unit = 85f / rate.coerceIn(0.5f, 2f)
        var i = 0
        val out = ArrayList<Shape>()
        while (i < f.length) {
            val c = f[i]
            val two = if (i + 1 < f.length) f.substring(i, i + 2) else ""
            val three = if (i + 2 < f.length) f.substring(i, i + 3) else ""
            when {
                c in "mbp" -> { out += Shape(0f, 0.95f, unit * 0.55f); i++ }
                c in "fv" -> { out += Shape(0.12f, 1.02f, unit * 0.5f); i++ }
                three == "eau" -> { out += Shape(0.55f, 0.72f, unit * 1.2f); i += 3 }
                two == "ou" -> { out += Shape(0.32f, 0.66f, unit); i += 2 }
                two == "au" || two == "on" || two == "om" -> { out += Shape(0.55f, 0.74f, unit); i += 2 }
                two == "oi" -> { out += Shape(0.4f, 0.72f, unit * 0.5f); out += Shape(0.85f, 1f, unit * 0.7f); i += 2 }
                two == "an" || two == "en" || two == "am" || two == "em" -> { out += Shape(0.7f, 0.9f, unit); i += 2 }
                two == "in" || two == "ain" || two == "ein" -> { out += Shape(0.45f, 1.05f, unit); i += 2 }
                c == 'a' -> { out += Shape(0.9f, 1f, unit); i++ }
                c == 'o' -> { out += Shape(0.6f, 0.75f, unit); i++ }
                c == 'u' -> { out += Shape(0.3f, 0.65f, unit); i++ }
                c == 'e' -> { out += Shape(if (i == f.length - 1) 0.2f else 0.5f, 1.04f, unit * if (i == f.length - 1) 0.5f else 1f); i++ }
                c == 'i' || c == 'y' -> { out += Shape(0.3f, 1.15f, unit * 0.85f); i++ }
                c.isLetter() -> { out += Shape(0.22f, 1f, unit * 0.35f); i++ }
                else -> i++
            }
        }
        if (out.isEmpty()) out += Shape(0.4f, 1f, unit)
        shapes.clear()
        shapes.addAll(out)
        shapeLeft = 0f
    }

    /** La voix s'est tue (fin de phrase ou interruption). */
    fun silent() { talking = false; shapes.clear() }

    // ---------------------------------------------------------------- animation

    private class Pose(var smile: Float, var brow: Float, var arch: Float, var inner: Float, var asym: Float,
                       var eye: Float, var tilt: Float, var open: Float)

    private fun target(): Pose {
        val p = when (emotion) {
            Emotion.JOIE -> Pose(1f, -3f, 9f, 0f, 0f, 0.82f, 3f, 0.12f)
            Emotion.EMPATHIE -> Pose(0.35f, -1f, 3f, -7f, 0f, 0.88f, -7f, 0f)
            Emotion.SURPRISE -> Pose(0f, -11f, 12f, 0f, 0f, 1.3f, 0f, 0.45f)
            Emotion.QUESTION -> Pose(0.1f, -2f, 7f, 0f, 9f, 1.05f, 8f, 0f)
            Emotion.SERIEUX -> Pose(-0.25f, 4f, 2f, 5f, 0f, 0.9f, 0f, 0f)
            else -> Pose(0f, 0f, 6f, 0f, 0f, 1f, 0f, 0f)
        }
        when (state) {
            NeuronView.State.OFF -> { p.eye *= 0.45f; p.smile *= 0.4f }
            NeuronView.State.LISTENING -> { p.eye *= 1.06f; p.tilt += 4f }
            NeuronView.State.THINKING -> { p.brow += 2f; p.inner += 2f; p.eye *= 0.95f }
            else -> {}
        }
        return p
    }

    private val cur = Pose(0f, 0f, 6f, 0f, 0f, 1f, 0f, 0f)
    private var mouthOpen = 0f
    private var mouthWidth = 1f
    private var level = 0f
    private var levelS = 0f
    private var blink = 0f
    private var nextBlink = 1.8f
    private var gx = 0f; private var gy = 0f; private var tgx = 0f; private var tgy = 0f; private var nextGaze = 1f
    private var t = 0f
    private var lastFrame = 0L
    private var lastState = state

    private fun step(dt: Float) {
        t += dt
        if (state != lastState) {
            if (lastState == NeuronView.State.SPEAKING) { silent(); emotion = Emotion.NEUTRE }
            lastState = state
        }
        val tg = target()
        val k = min(1f, dt * 4f)
        cur.smile += (tg.smile - cur.smile) * k; cur.brow += (tg.brow - cur.brow) * k; cur.arch += (tg.arch - cur.arch) * k
        cur.inner += (tg.inner - cur.inner) * k; cur.asym += (tg.asym - cur.asym) * k; cur.eye += (tg.eye - cur.eye) * k
        cur.tilt += (tg.tilt - cur.tilt) * k; cur.open += (tg.open - cur.open) * k
        levelS += (level - levelS) * min(1f, dt * 10f)

        nextBlink -= dt
        if (blink > 0f) blink = max(0f, blink - dt)
        if (nextBlink <= 0f) {
            blink = 0.16f
            nextBlink = (if (state == NeuronView.State.OFF) 4.5f else 2f) + (Math.random() * 4).toFloat() - if (Math.random() < 0.15) 1.6f else 0f
        }
        nextGaze -= dt
        if (nextGaze <= 0f) {
            when (state) {
                NeuronView.State.THINKING -> { tgx = -4f + (Math.random() * 2).toFloat(); tgy = -5f }
                NeuronView.State.OFF -> { tgx = ((Math.random() - 0.5) * 3).toFloat(); tgy = 3f }
                NeuronView.State.LISTENING -> { tgx = ((Math.random() - 0.5) * 2).toFloat(); tgy = 0f }
                else -> { tgx = ((Math.random() - 0.5) * 6).toFloat(); tgy = ((Math.random() - 0.5) * 3).toFloat() }
            }
            nextGaze = 0.8f + (Math.random() * 2.6).toFloat()
        }
        val g = min(1f, dt * 4.5f)
        gx += (tgx - gx) * g; gy += (tgy - gy) * g

        // bouche : formes des mots réellement prononcés ; à défaut, rythme de parole tant que la voix parle
        var wantOpen = cur.open
        var wantWidth = 1f
        val speaking = state == NeuronView.State.SPEAKING && talking
        if (speaking && shapes.isNotEmpty()) {
            val s = shapes.first()
            wantOpen = max(wantOpen, s.open); wantWidth = s.width
            shapeLeft += dt * 1000f
            if (shapeLeft >= s.ms) { shapes.removeFirst(); shapeLeft = 0f }
        } else if (speaking && SystemClock.uptimeMillis() - lastWordAt > 450) {
            val syl = abs(sin(t * 10.5f)) * 0.6f + abs(sin(t * 6.7f + 1f)) * 0.4f
            wantOpen = max(wantOpen, max(0f, syl - 0.2f) * 0.8f)
        }
        val m = min(1f, dt * 18f)
        mouthOpen += (wantOpen - mouthOpen) * m
        mouthWidth += (wantWidth - mouthWidth) * m
    }

    // ---------------------------------------------------------------- dessin

    private val cyan = 0xFF4FA6FF.toInt()
    private val green = 0xFF3DDC97.toInt()
    private val light = 0xFFBFE6FF.toInt()
    private val ice = 0xFF7FD8FF.toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val head = Path().apply {
        moveTo(150f, 30f); cubicTo(215f, 30f, 245f, 85f, 243f, 150f); cubicTo(241f, 215f, 215f, 268f, 180f, 292f)
        cubicTo(165f, 302f, 135f, 302f, 120f, 292f); cubicTo(85f, 268f, 59f, 215f, 57f, 150f); cubicTo(55f, 85f, 85f, 30f, 150f, 30f); close()
    }
    private val circuitsTop = Path().apply {
        moveTo(150f, 34f); lineTo(150f, 70f); lineTo(128f, 92f); lineTo(128f, 112f)
        moveTo(150f, 70f); lineTo(172f, 92f); lineTo(172f, 112f)
        moveTo(96f, 70f); lineTo(112f, 86f); lineTo(112f, 104f)
        moveTo(204f, 70f); lineTo(188f, 86f); lineTo(188f, 104f)
    }
    private val circuitsCheeks = Path().apply {
        moveTo(64f, 176f); lineTo(84f, 196f); lineTo(84f, 232f); lineTo(104f, 252f)
        moveTo(236f, 176f); lineTo(216f, 196f); lineTo(216f, 232f); lineTo(196f, 252f)
    }
    private val shoulders = Path().apply {
        moveTo(8f, 360f); cubicTo(30f, 334f, 86f, 322f, 122f, 326f); lineTo(124f, 300f)
        moveTo(292f, 360f); cubicTo(270f, 334f, 214f, 322f, 178f, 326f); lineTo(176f, 300f)
    }
    private val nose = Path().apply { moveTo(150f, 168f); lineTo(145f, 206f); quadTo(150f, 211f, 157f, 207f) }
    private val nodes = floatArrayOf(128f, 112f, 172f, 112f, 150f, 70f, 104f, 252f, 196f, 252f)

    private val power = ctx.getSystemService(PowerManager::class.java)
    private var hot = false
    private var hotCheck = 0L

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.033f else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.1f)
        lastFrame = now
        if (now - hotCheck > 5000) {
            hotCheck = now
            hot = (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE
        }
        step(dt)

        val scale = min(width / 300f, height / 360f)
        c.save()
        c.translate((width - 300f * scale) / 2f, (height - 360f * scale) / 2f)
        c.scale(scale, scale)
        val sleeping = state == NeuronView.State.OFF
        val alpha = if (sleeping) 0.6f else 1f
        val glow = if (hot) 0f else if (sleeping) 3f else 7f

        // halo
        val haloA = when (state) { NeuronView.State.SPEAKING -> 0.24f + mouthOpen * 0.12f; NeuronView.State.LISTENING -> 0.18f + levelS * 0.2f; NeuronView.State.OFF -> 0.07f; else -> 0.16f }
        halo.shader = RadialGradient(150f, 165f, 210f, intArrayOf(withA(cyan, haloA), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(-60f, -40f, 360f, 400f, halo)

        val breath = sin(t * (if (sleeping) 1.1f else 1.7f)) * (if (sleeping) 2.2f else 1.4f)
        val nod = if (state == NeuronView.State.LISTENING) max(0f, sin(t * 2.6f)) * (1.5f + levelS * 3f) else 0f
        val sway = sin(t * 0.7f) * 1.2f + if (state == NeuronView.State.SPEAKING) sin(t * 2.3f) * 1.3f else 0f
        val flow = -(t * 18f) % 15f

        // épaules et cou
        c.save()
        c.translate(0f, breath * 0.5f)
        line(c, shoulders, green, 1.6f, 0.7f * alpha, glow)
        stroke.pathEffect = DashPathEffect(floatArrayOf(6f, 9f), flow)
        path.reset(); path.moveTo(40f, 352f); path.cubicTo(70f, 338f, 100f, 334f, 132f, 336f)
        path.moveTo(260f, 352f); path.cubicTo(230f, 338f, 200f, 334f, 168f, 336f)
        line(c, path, cyan, 1f, alpha, 0f)
        stroke.pathEffect = null
        c.restore()

        // tête
        c.save()
        c.translate(0f, breath + nod)
        c.rotate(cur.tilt + sway, 150f, 220f)
        fill.color = withA(0xFF0B1424.toInt(), 0.72f * alpha)
        c.drawPath(head, fill)
        line(c, head, cyan, 1.8f, alpha, glow)

        c.save()
        c.clipPath(head)
        val scanY = ((t / 5.5f) % 1f) * 420f - 40f
        fill.color = withA(ice, 0.35f * alpha)
        c.drawRect(40f, scanY, 260f, scanY + 2f, fill)
        stroke.pathEffect = DashPathEffect(floatArrayOf(6f, 9f), flow)
        line(c, circuitsTop, green, 1f, alpha, 0f)
        line(c, circuitsCheeks, cyan, 1f, alpha, 0f)
        stroke.pathEffect = null
        for (i in nodes.indices step 2) {
            val pulse = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 2.6f + i))
            fill.color = withA(if (i % 4 == 0) green else ice, pulse * alpha)
            c.drawCircle(nodes[i], nodes[i + 1], 2.4f, fill)
        }
        c.restore()

        // sourcils
        val by = 132f + cur.brow + nod * 0.3f
        path.reset(); path.moveTo(90f, by + 2f); path.quadTo(112f, by - cur.arch, 134f, by + cur.inner)
        line(c, path, light, 3f, alpha, glow * 0.6f)
        path.reset(); path.moveTo(166f, by + cur.inner - cur.asym); path.quadTo(188f, by - cur.arch - cur.asym * 1.4f, 210f, by + 2f - cur.asym)
        line(c, path, light, 3f, alpha, glow * 0.6f)

        // yeux
        val blinkK = if (blink > 0f) abs(blink - 0.08f) / 0.08f else 1f
        val ry = max(0.6f, 12f * cur.eye * blinkK * (1f + levelS * 0.08f))
        for (cx in floatArrayOf(112f, 188f)) {
            fill.color = withA(0xFF06101E.toInt(), alpha)
            c.drawOval(cx - 23f, 160f - ry, cx + 23f, 160f + ry, fill)
            stroke.color = withA(cyan, alpha); stroke.strokeWidth = 1.6f; stroke.setShadowLayer(glow * 0.5f, 0f, 0f, cyan)
            c.drawOval(cx - 23f, 160f - ry, cx + 23f, 160f + ry, stroke)
            stroke.clearShadowLayer()
            if (ry > 3f) {
                c.save()
                c.clipRect(cx - 23f, 160f - ry, cx + 23f, 160f + ry)
                val iris = when (emotion) { Emotion.JOIE -> green; Emotion.EMPATHIE, Emotion.SURPRISE -> ice; else -> cyan }
                fill.color = withA(iris, 0.9f * alpha)
                c.drawCircle(cx + gx, 160f + gy, 8f, fill)
                fill.color = withA(0xFFE8F7FF.toInt(), alpha)
                c.drawCircle(cx + gx, 160f + gy, 3.2f, fill)
                c.restore()
            }
        }

        // nez
        line(c, nose, cyan, 1.3f, 0.7f * alpha, 0f)

        // bouche
        val w = 46f * (1f + cur.smile * 0.12f) * mouthWidth
        val cy = 244f - cur.smile * 7f
        val x1 = 150f - w / 2f; val x2 = 150f + w / 2f
        val open = min(1.1f, mouthOpen)
        path.reset(); path.moveTo(x1, cy)
        path.quadTo(150f, 241f - open * 3f - cur.smile * 1.5f, x2, cy)
        path.quadTo(150f, 247f + open * 22f + cur.smile * 5f, x1, cy); path.close()
        fill.color = withA(0xFF050A14.toInt(), alpha)
        c.drawPath(path, fill)
        line(c, path, ice, 2f, alpha, glow * 0.7f)
        if (open < 0.2f) {
            path.reset(); path.moveTo(x1 + 4f, cy + 0.5f); path.quadTo(150f, cy + 2f + open * 6f + cur.smile * 2f, x2 - 4f, cy + 0.5f)
            line(c, path, light, 1.2f, 0.6f * alpha, 0f)
        }
        c.restore()
        c.restore()

        if (isShown) {
            if (hot) postInvalidateDelayed(66) else postInvalidateOnAnimation()
        }
    }

    private fun line(c: Canvas, p: Path, color: Int, w: Float, a: Float, glow: Float) {
        stroke.color = withA(color, a)
        stroke.strokeWidth = w
        if (glow > 0f) stroke.setShadowLayer(glow, 0f, 0f, withA(color, 0.9f)) else stroke.clearShadowLayer()
        c.drawPath(p, stroke)
        stroke.clearShadowLayer()
    }

    private fun withA(color: Int, a: Float): Int = (((color ushr 24) * a.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0x00FFFFFF)

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) { lastFrame = 0L; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); current = this }
    override fun onDetachedFromWindow() { if (current === this) current = null; super.onDetachedFromWindow() }

    companion object {
        /** Le visage affiché, s'il y en a un : la voix lui signale phrases et mots. */
        @Volatile var current: FaceView? = null
    }
}
