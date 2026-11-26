package com.application.nox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Le médaillon de Nox (son visage holographique) : respire au repos ; l'anneau, le halo et les ondes
 * prennent la couleur de l'état (écoute verte, réflexion bleue avec orbite, parole ambrée) et réagissent à la voix.
 * Le visage lui-même garde ses vraies couleurs.
 */
class NeuronView(ctx: Context, private val style: Style) : View(ctx) {

    enum class Style { HEADER, SPLASH, VOICE }

    enum class State(val hue: Float, val ring: Int, val glow: Int) {
        OFF(0f, 0xFF3E5A92.toInt(), 0x334FA6FF),
        IDLE(0f, 0xFF3E5A92.toInt(), 0x554FA6FF),
        LISTENING(-125f, 0xFF5FB37A.toInt(), 0x555FD39A),
        THINKING(-55f, 0xFF4FA6FF.toInt(), 0x554FA6FF),
        SPEAKING(125f, 0xFFE8A33D.toInt(), 0x55E8A33D),
    }

    var state = State.IDLE
        set(v) { if (field != v) { fromHue = curHue; field = v; blend = 0f; invalidate() } }

    private val bmp: Bitmap? = run {
        BitmapFactory.decodeResource(ctx.resources, R.drawable.nox_visage)
    }
    private val src = Rect()
    private val dst = RectF()
    private val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var fromHue = 0f
    private var curHue = 0f
    private var blend = 1f
    private var level = 0f
    private var targetLevel = 0f
    private val born = System.nanoTime()

    /** Badge « ☰ » en bas à droite (en-tête : ouvre le menu). */
    var badge = false

    fun setLevel(v: Float) { targetLevel = v.coerceIn(0f, 1f) }

    override fun onDraw(c: Canvas) {
        val t = (System.nanoTime() - born) / 1e9f
        if (blend < 1f) blend = (blend + 0.06f).coerceAtMost(1f)
        curHue = fromHue + (state.hue - fromHue) * blend
        level += (targetLevel - level) * 0.25f
        val synthetic = if (state == State.SPEAKING) (0.35f + 0.3f * sin(t * 7.3f) * sin(t * 3.1f + 1f)).coerceAtLeast(0f) else 0f
        val lv = maxOf(level, synthetic)

        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f

        when (style) {
            Style.HEADER -> {
                fillPaint.shader = RadialGradient(cx, cy, r, intArrayOf(0xFF1A2A5C.toInt(), 0xFF0E1838.toInt()), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r - 1f, fillPaint)
                ringPaint.strokeWidth = r * 0.05f
                ringPaint.color = if (state == State.IDLE || state == State.OFF) 0xFF3E5A92.toInt() else state.ring
                ringPaint.alpha = 255
                c.drawCircle(cx, cy, r - ringPaint.strokeWidth, ringPaint)
                drawNeuron(c, cx, cy, r * 0.86f * breath(t, 0.025f))
                if (state == State.THINKING) orbit(c, cx, cy, r * 0.82f, t, r * 0.08f)
                if (badge) {
                    val br = r * 0.27f
                    val bx = w - br * 0.9f; val by = h - br * 1.05f
                    dotPaint.color = 0xFF16233F.toInt(); c.drawCircle(bx, by, br, dotPaint)
                    dotPaint.color = 0xFFCFE0FF.toInt()
                    val lw = br * 0.9f; val lh = br * 0.13f
                    for (i in -1..1) c.drawRoundRect(bx - lw / 2, by + i * br * 0.36f - lh / 2, bx + lw / 2, by + i * br * 0.36f + lh / 2, lh, lh, dotPaint)
                }
            }
            Style.SPLASH -> {
                fillPaint.shader = RadialGradient(cx, cy, r, intArrayOf(0x8C4FA6FF.toInt(), 0x2E4FA6FF, 0x004FA6FF), floatArrayOf(0f, 0.45f, 0.7f), Shader.TileMode.CLAMP)
                fillPaint.alpha = (110 + 50 * sin(t * 2.2f)).toInt()
                c.drawCircle(cx, cy, r, fillPaint)
                drawNeuron(c, cx, cy, r * 0.7f * breath(t, 0.035f))
            }
            Style.VOICE -> {
                // Mode vocal épuré : le neurone seul, son halo, et deux ondes dont la couleur et le rythme disent l'état
                val core = r * 0.62f
                val (wave, speed, pulse) = when (state) {
                    State.LISTENING -> Triple(0xFF60A5FA.toInt(), 0.42f, 0.42f)
                    State.THINKING -> Triple(0xFFA855F7.toInt(), 0.75f, 1.1f)
                    State.SPEAKING -> Triple(0xFFF0ABFC.toInt(), 1.0f, 2.2f)
                    else -> Triple(0xFF3E5A92.toInt(), 0.22f, 0.24f)
                }
                val haloR = core * 1.3f * (1f + lv * 0.2f)
                fillPaint.shader = RadialGradient(cx, cy, haloR, intArrayOf((wave and 0x00FFFFFF) or 0x48000000, (wave and 0x00FFFFFF) or 0x10000000, 0),
                    floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                fillPaint.alpha = 255
                c.drawCircle(cx, cy, haloR, fillPaint)
                ringPaint.color = wave
                ringPaint.strokeWidth = r * 0.008f
                for (k in 0..1) {
                    val p = (t * speed + k * 0.5f) % 1f
                    ringPaint.alpha = ((1f - p) * 150 * (0.6f + lv * 0.6f)).toInt().coerceIn(0, 255)
                    c.drawCircle(cx, cy, core * 0.9f + (r - core * 0.9f) * p, ringPaint)
                }
                val beat = 1f + 0.045f * sin(t * 2f * PI.toFloat() * pulse)
                drawNeuron(c, cx, cy, core * 0.95f * beat * (1f + lv * 0.08f))
            }
        }
        postInvalidateOnAnimation()
    }

    private fun breath(t: Float, amp: Float) = 1f + amp * sin(t * 2f * PI.toFloat() / 4.2f)

    private fun orbit(c: Canvas, cx: Float, cy: Float, rad: Float, t: Float, dot: Float) {
        for (i in 0 until 3) {
            val a = t * 2.4f + i * 2.094f
            dotPaint.color = State.THINKING.ring
            dotPaint.alpha = 255 - i * 60
            c.drawCircle(cx + rad * cos(a), cy + rad * sin(a), dot * (1f - i * 0.2f), dotPaint)
        }
        dotPaint.alpha = 255
    }

    private fun drawNeuron(c: Canvas, cx: Float, cy: Float, half: Float) {
        val b = bmp ?: return
        src.set(0, 0, b.width, b.height)
        dst.set(cx - half, cy - half, cx + half, cy + half)
        c.drawBitmap(b, src, dst, imgPaint)
    }

    companion object {
        /** Rotation de teinte qui préserve la luminance (même matrice que le hue-rotate CSS). */
        fun hueMatrix(deg: Float): ColorMatrix {
            val a = deg * PI.toFloat() / 180f
            val c = cos(a); val s = sin(a)
            val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
            return ColorMatrix(floatArrayOf(
                lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0f, 0f,
                lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0f, 0f,
                lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
        }
    }
}
