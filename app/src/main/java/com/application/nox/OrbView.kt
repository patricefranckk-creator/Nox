package com.application.nox

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * L'orbe vivant de Nox. Il respire au repos, ondule avec ta voix,
 * tourbillonne quand il réfléchit et pulse quand il parle.
 */
class OrbView(ctx: Context) : View(ctx) {

    enum class Mode(val core: Int, val edge: Int) {
        OFF(0xFF3A3A55.toInt(), 0xFF1A1A2A.toInt()),
        IDLE(0xFF8B7CFF.toInt(), 0xFF3B2F9E.toInt()),
        LISTENING(0xFF4FE3FF.toInt(), 0xFF1466B8.toInt()),
        THINKING(0xFFFFB547.toInt(), 0xFFB8336A.toInt()),
        SPEAKING(0xFFFF5FC8.toInt(), 0xFF6A2BD9.toInt()),
    }

    var mode = Mode.OFF
        set(v) { if (field != v) { field = v; fromCore = curCore; fromEdge = curEdge; blend = 0f } }

    private var targetLevel = 0f
    private var level = 0f
    private var t = 0f
    private var blend = 1f
    private var fromCore = Mode.OFF.core
    private var fromEdge = Mode.OFF.edge
    private var curCore = Mode.OFF.core
    private var curEdge = Mode.OFF.edge
    private var lastNanos = 0L

    private val argb = ArgbEvaluator()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setLevel(v: Float) { targetLevel = v.coerceIn(0f, 1f) }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
        lastNanos = now

        val speed = when (mode) {
            Mode.THINKING -> 2.6f; Mode.SPEAKING -> 1.8f; Mode.LISTENING -> 1.4f; else -> 0.6f
        }
        t += dt * speed
        blend = (blend + dt * 2.5f).coerceAtMost(1f)
        curCore = argb.evaluate(blend, fromCore, mode.core) as Int
        curEdge = argb.evaluate(blend, fromEdge, mode.edge) as Int

        // Niveau sonore lissé ; en mode parole on simule une prosodie
        val synthetic = if (mode == Mode.SPEAKING) (0.35f + 0.3f * sin(t * 7.3f) * sin(t * 3.1f + 1f)).coerceAtLeast(0f) else 0f
        val goal = if (mode == Mode.LISTENING) targetLevel else synthetic
        level += (goal - level) * (dt * 10f).coerceAtMost(1f)

        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.26f
        val breath = 1f + 0.035f * sin(t * 1.6f)
        val r = base * breath * (1f + 0.22f * level)

        // Halo
        glow.shader = RadialGradient(cx, cy, r * 2.1f,
            intArrayOf(withAlpha(curCore, 120), withAlpha(curEdge, 40), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 2.1f, glow)

        // Corps déformé (blob)
        val n = 96
        val wob = when (mode) {
            Mode.THINKING -> 0.10f; Mode.LISTENING -> 0.04f + 0.14f * level
            Mode.SPEAKING -> 0.05f + 0.10f * level; else -> 0.035f
        }
        path.reset()
        for (i in 0..n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat()
            val d = sin(a * 3f + t * 1.7f) * 0.5f + sin(a * 5f - t * 2.3f) * 0.3f + sin(a * 2f + t * 0.9f) * 0.2f
            val rr = r * (1f + wob * d)
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val ox = cx - r * 0.35f * cos(t * 0.7f)
        val oy = cy - r * 0.35f * sin(t * 0.5f) - r * 0.2f
        body.shader = RadialGradient(ox, oy, r * 1.6f,
            intArrayOf(lighten(curCore), curCore, curEdge),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, body)

        // Anneaux orbitaux (réflexion)
        if (mode == Mode.THINKING || (mode == Mode.LISTENING && level > 0.05f)) {
            ring.strokeWidth = r * 0.03f
            for (k in 0 until 3) {
                val phase = t * (1.2f + k * 0.4f) + k * 2.1f
                val rr = r * (1.25f + 0.18f * k + 0.05f * sin(phase))
                ring.color = withAlpha(curCore, (110 - k * 30).coerceAtLeast(20))
                val sweep = 70f + 50f * sin(phase * 0.7f)
                c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, (phase * 57.3f) % 360f, sweep, false, ring)
            }
        }
        postInvalidateOnAnimation()
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun lighten(c: Int) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * 0.55f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * 0.55f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * 0.55f).toInt(),
    )
}
