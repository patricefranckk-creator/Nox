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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Le visage holographique 2,5D de Nox : dessiné à la main, animé par [FaceBrain] (états réels de Nox,
 * émotion de la phrase, mots prononcés). Sert aussi de repli quand l'avatar 3D est trop lourd.
 * Le dessin s'arrête quand le visage n'est pas affiché.
 */
class FaceView(ctx: Context) : View(ctx) {

    private val b = FaceBrain
    private val cur get() = b.cur
    private val state get() = b.state
    private val emotion get() = b.emotion
    private val t get() = b.t
    private val mouthOpen get() = b.mouthOpen
    private val mouthWidth get() = b.mouthWidth
    private val levelS get() = b.levelS
    private val gx get() = b.gx
    private val gy get() = b.gy
    private var lastFrame = 0L

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
        b.step(dt)

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

        val breath = b.breath
        val nod = b.nod
        val sway = b.sway
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
        val blinkK = b.blinkK
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

}
