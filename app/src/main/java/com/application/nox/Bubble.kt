package com.application.nox

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * La bulle Nox : un petit neurone flottant par-dessus les autres applis quand Nox est en arrière-plan.
 * Touche : parler (ou interrompre, ou « j'ai fini ») · appui long : ouvrir Nox · glisser : déplacer,
 * elle se range contre le bord. Son anneau prend la couleur de l'état, et la réponse s'affiche à côté.
 * Fenêtre d'accessibilité si le pilotage est activé (aucune autorisation de plus), sinon superposition.
 */
object Bubble {

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var host: Context? = null
    private var bubble: FrameLayout? = null
    private var ring: GradientDrawable? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var label: TextView? = null
    private var labelParams: WindowManager.LayoutParams? = null
    private var pulse: ObjectAnimator? = null
    private var lastY = -1
    private var onLeft = false
    private val hideLabel = Runnable { removeLabel() }

    val shown get() = bubble != null

    /** Peut-on afficher la bulle (pilotage activé, ou autorisation de superposition) ? */
    fun possible(ctx: Context) = NoxAccess.instance != null || Settings.canDrawOverlays(ctx)

    fun show(ctx: Context) {
        if (bubble != null) return
        val acc = NoxAccess.instance
        val c: Context = acc ?: ctx.applicationContext
        val type = when {
            acc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(ctx) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> { NoxLog.i("BULLE", "Bulle impossible : ni pilotage (accessibilité) ni superposition autorisés"); return }
        }
        val w = c.getSystemService(WindowManager::class.java)
        val size = c.dp(60)
        val dm = c.resources.displayMetrics

        val ringDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xEE0D1322.toInt())
            setStroke(c.dp(3), Ui.FAINT)
        }
        val frame = FrameLayout(c).apply {
            background = ringDrawable
            elevation = c.dp(8).toFloat()
            contentDescription = "Nox : touche pour parler, appui long pour ouvrir"
        }
        val img = ImageView(c).apply {
            setImageResource(R.drawable.neurone)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }
        frame.addView(img, FrameLayout.LayoutParams(size - c.dp(10), size - c.dp(10), Gravity.CENTER))

        val p = WindowManager.LayoutParams(size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onLeft) c.dp(6) else dm.widthPixels - size - c.dp(6)
            y = if (lastY >= 0) lastY else (dm.heightPixels * 0.55).toInt()
        }
        frame.setOnTouchListener(Drag(c, p, size))
        try {
            w.addView(frame, p)
        } catch (t: Throwable) {
            NoxLog.w("BULLE", "Affichage de la bulle refusé", t); return
        }
        wm = w; host = c; bubble = frame; ring = ringDrawable; bubbleParams = p
        pulse = ObjectAnimator.ofPropertyValuesHolder(frame,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f)).apply {
            duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        }
        frame.alpha = 0f; frame.scaleX = 0.6f; frame.scaleY = 0.6f
        frame.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        NoxLog.i("BULLE", "Bulle affichée (${if (acc != null) "accessibilité" else "superposition"})")
    }

    fun hide() {
        main.removeCallbacks(hideLabel)
        removeLabel()
        pulse?.cancel(); pulse = null
        bubble?.let { v -> try { wm?.removeView(v) } catch (_: Throwable) {} }
        bubble = null; ring = null; bubbleParams = null; wm = null; host = null
    }

    /** L'anneau suit l'état de Nox : bleu écoute, violet réflexion, rose parole. */
    fun state(s: NeuronView.State) {
        val c = host ?: return
        val color = when (s) {
            NeuronView.State.LISTENING -> 0xFF60A5FA.toInt()
            NeuronView.State.THINKING -> 0xFFB57BFA.toInt()
            NeuronView.State.SPEAKING -> 0xFFF0ABFC.toInt()
            else -> Ui.FAINT
        }
        ring?.setStroke(c.dp(3), color)
        val active = s == NeuronView.State.LISTENING || s == NeuronView.State.THINKING || s == NeuronView.State.SPEAKING
        pulse?.let { if (active && !it.isRunning) it.start() else if (!active) { it.cancel(); bubble?.scaleX = 1f; bubble?.scaleY = 1f } }
    }

    /** Affiche la réponse quelques secondes à côté de la bulle (toucher : ouvrir Nox). */
    fun say(text: String) {
        val c = host ?: return
        val w = wm ?: return
        val bp = bubbleParams ?: return
        val clean = text.replace(Regex("[*_#`>]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        removeLabel()
        val dm = c.resources.displayMetrics
        val tv = TextView(c).apply {
            this.text = clean
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            textSize = 14f
            setTextColor(Ui.ANSWER)
            maxWidth = (dm.widthPixels * 0.68).toInt()
            setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(10))
            background = Ui.rounded(0xF20F1524.toInt(), c.dp(16).toFloat(), Ui.STROKE, c.dp(1))
            elevation = c.dp(6).toFloat()
            setOnClickListener { openNox(c) }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, bp.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or (if (onLeft) Gravity.START else Gravity.END)
            x = bp.width + c.dp(12)
            y = (bp.y - c.dp(4)).coerceAtLeast(c.dp(24))
        }
        try { w.addView(tv, lp) } catch (_: Throwable) { return }
        tv.alpha = 0f; tv.animate().alpha(1f).setDuration(180).start()
        label = tv; labelParams = lp
        main.removeCallbacks(hideLabel)
        main.postDelayed(hideLabel, (4000L + clean.length * 45L).coerceAtMost(12_000L))
    }

    private fun removeLabel() {
        label?.let { v -> try { wm?.removeView(v) } catch (_: Throwable) {} }
        label = null; labelParams = null
    }

    private fun openNox(c: Context) {
        hide()
        c.startActivity(Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun tap(c: Context) {
        val ui = Nox.ui
        if (ui != null && !ui.isFinishing && NoxService.micReady) ui.onNeuronTap()
        else c.startActivity(Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_VOICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Glisser pour déplacer (rangement contre le bord), toucher pour parler, appui long pour ouvrir. */
    private class Drag(private val c: Context, private val p: WindowManager.LayoutParams, private val size: Int) : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var moved = false; private var longDone = false
        private val long = Runnable { longDone = true; bubble?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); openNox(c) }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; longDone = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    main.postDelayed(long, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > c.dp(10)) { moved = true; main.removeCallbacks(long); removeLabel() }
                    if (moved) {
                        p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt()
                        try { wm?.updateViewLayout(v, p) } catch (_: Throwable) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(long)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    val dm = c.resources.displayMetrics
                    if (moved) {
                        onLeft = p.x + size / 2 < dm.widthPixels / 2
                        val target = if (onLeft) c.dp(6) else dm.widthPixels - size - c.dp(6)
                        p.y = p.y.coerceIn(c.dp(24), dm.heightPixels - size - c.dp(24))
                        lastY = p.y
                        ValueAnimator.ofInt(p.x, target).apply {
                            duration = 200
                            addUpdateListener { p.x = it.animatedValue as Int; try { wm?.updateViewLayout(v, p) } catch (_: Throwable) {} }
                        }.start()
                    } else if (!longDone && e.actionMasked == MotionEvent.ACTION_UP && abs(e.rawX - downX) < c.dp(10)) {
                        removeLabel()
                        tap(c)
                    }
                }
            }
            return true
        }
    }

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
