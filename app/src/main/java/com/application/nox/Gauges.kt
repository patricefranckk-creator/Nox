package com.application.nox

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.application.nox.Ui.dp
import com.application.nox.Ui.text
import java.io.File
import java.util.Locale

/** Mesures du téléphone, lues sans permission particulière. */
class SystemStats(private val ctx: Context) {

    data class Snapshot(
        val ramUsedPct: Float, val ramTotal: Long, val ramAvail: Long, val noxRss: Long, val nativeHeap: Long,
        val noxCpuPct: Float, val cpuFreqPct: Float, val cpuFreqText: String,
        val gpuPct: Float?, val gpuFreqMhz: Int?,
        val battPct: Int, val battTempC: Float, val battMa: Int, val charging: Boolean, val thermal: String,
    )

    private var lastCpuTicks = -1L
    private var lastWall = 0L
    private val cores = Runtime.getRuntime().availableProcessors()
    private val gpuBusy = listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load")
    private val gpuFreq = listOf("/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")

    fun sample(): Snapshot {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        // CPU de Nox : temps processeur consommé / temps écoulé, ramené à tous les cœurs
        val ticks = procTicks()
        val now = SystemClock.elapsedRealtime()
        var cpu = 0f
        if (lastCpuTicks >= 0 && ticks >= 0 && now > lastWall) {
            val usedMs = (ticks - lastCpuTicks) * 10f            // 100 ticks par seconde
            cpu = (usedMs / ((now - lastWall) * cores) * 100f).coerceIn(0f, 100f)
        }
        lastCpuTicks = ticks; lastWall = now

        // Fréquences des cœurs : à quel point la puce est poussée
        var cur = 0L; var max = 0L
        val freqs = ArrayList<Int>()
        for (i in 0 until cores) {
            val c = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: continue
            val m = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") ?: continue
            cur += c; max += m; freqs += (c / 1000).toInt()
        }
        val freqPct = if (max > 0) cur * 100f / max else 0f
        val freqText = if (freqs.isEmpty()) "fréquences non lisibles" else "max ${freqs.max()} MHz · moy. ${freqs.average().toInt()} MHz"

        // GPU Adreno (kgsl) : lisible sur beaucoup de Snapdragon, sinon « n/d »
        val gpu = gpuBusy.firstNotNullOfOrNull { readText(it)?.trim()?.split(" ")?.firstOrNull()?.toFloatOrNull() }
        val gf = gpuFreq.firstNotNullOfOrNull { readLong(it) }?.let { if (it > 10_000_000) (it / 1_000_000).toInt() else (it / 1000).toInt() }

        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val charging = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val bm = ctx.getSystemService(BatteryManager::class.java)
        var ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (ua == Int.MIN_VALUE) ua = 0
        val ma = if (kotlin.math.abs(ua) > 20_000) ua / 1000 else ua   // certains fabricants donnent déjà des mA

        val pm = ctx.getSystemService(PowerManager::class.java)
        val thermal = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "normal"; PowerManager.THERMAL_STATUS_LIGHT -> "tiède"
            PowerManager.THERMAL_STATUS_MODERATE -> "chaud"; PowerManager.THERMAL_STATUS_SEVERE -> "très chaud · bridé"
            else -> "critique · bridé"
        }

        return Snapshot(
            ramUsedPct = (mi.totalMem - mi.availMem) * 100f / mi.totalMem, ramTotal = mi.totalMem, ramAvail = mi.availMem,
            noxRss = rss(), nativeHeap = Debug.getNativeHeapAllocatedSize(),
            noxCpuPct = cpu, cpuFreqPct = freqPct, cpuFreqText = freqText,
            gpuPct = gpu, gpuFreqMhz = gf,
            battPct = lvl, battTempC = temp, battMa = ma, charging = charging, thermal = thermal,
        )
    }

    private fun procTicks(): Long = try {
        val f = File("/proc/self/stat").readText()
        val p = f.substringAfterLast(')').trim().split(' ')
        p[11].toLong() + p[12].toLong()      // utime + stime
    } catch (_: Throwable) { -1 }

    private fun rss(): Long = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
            ?.replace(Regex("[^0-9]"), "")?.toLong()?.times(1024) ?: 0
    } catch (_: Throwable) { 0 }

    private fun readText(path: String): String? = try { File(path).readText() } catch (_: Throwable) { null }
    private fun readLong(path: String): Long? = readText(path)?.trim()?.toLongOrNull()
}

/** Jauge en arc de cercle (240°), couleur selon le niveau. */
class ArcGauge(ctx: Context, private val label: String) : View(ctx) {
    private var value = 0f          // 0..100
    private var shown = 0f
    private var main = "—"
    private var sub = ""
    private var inverted = false    // batterie : bas = rouge
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF1C2238.toInt() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun set(pct: Float?, mainText: String, subText: String, invert: Boolean = false) {
        value = (pct ?: 0f).coerceIn(0f, 100f); main = mainText; sub = subText; inverted = invert
        if (pct == null) value = 0f
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = MeasureSpec.getSize(w)
        setMeasuredDimension(size, (size * 0.92f).toInt())
    }

    override fun onDraw(c: Canvas) {
        shown += (value - shown) * 0.25f
        if (kotlin.math.abs(value - shown) > 0.3f) postInvalidateOnAnimation()
        val w = width.toFloat()
        val stroke = w * 0.08f
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        val pad = stroke / 2 + w * 0.06f
        rect.set(pad, pad, w - pad, w - pad)
        c.drawArc(rect, 150f, 240f, false, track)
        val level = if (inverted) 100 - shown else shown
        arc.color = when { level < 60 -> 0xFF5FE3B0.toInt(); level < 85 -> 0xFFFFC266.toInt(); else -> 0xFFFF6B81.toInt() }
        c.drawArc(rect, 150f, 240f * shown / 100f, false, arc)
        big.textSize = w * 0.17f
        small.textSize = w * 0.085f
        c.drawText(main, w / 2, w * 0.52f, big)
        c.drawText(label, w / 2, w * 0.66f, small)
        small.textSize = w * 0.075f
        c.drawText(sub, w / 2, w * 0.88f, small)
    }
}

/**
 * Le tiroir des jauges : glisse depuis la droite (geste depuis le bord droit, ou ⋮ › Jauges).
 * Mesures rafraîchies chaque seconde, uniquement quand il est ouvert.
 */
class GaugeDrawer(private val a: MainActivity, private val root: FrameLayout) {

    private val stats = SystemStats(a)
    private val handler = Handler(Looper.getMainLooper())
    private val scrim = View(a).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f; visibility = View.GONE }
    private val panel = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF0B1022.toInt())
        setPadding(a.dp(16), a.dp(20), a.dp(16), a.dp(16))
        elevation = a.dp(12).toFloat()
        isClickable = true
    }
    private val ram = ArcGauge(a, "RAM")
    private val cpu = ArcGauge(a, "CPU · Nox")
    private val gpu = ArcGauge(a, "GPU Adreno")
    private val bat = ArcGauge(a, "Batterie")
    private val details = a.text("", 12f, Ui.MUTED)
    private val width get() = (a.resources.displayMetrics.widthPixels * 0.86f).toInt()
    var isOpen = false; private set
    private val backCb = android.window.OnBackInvokedCallback { close() }

    private val tick = object : Runnable {
        override fun run() {
            if (!isOpen) return
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        panel.addView(a.text("Jauges", 20f, Ui.TEXT, bold = true))
        panel.addView(a.text("En direct, rafraîchi chaque seconde", 12f, Ui.MUTED).apply { setPadding(0, 0, 0, a.dp(10)) })
        val grid = GridLayout(a).apply { columnCount = 2 }
        listOf(ram, cpu, gpu, bat).forEach { g ->
            grid.addView(g, GridLayout.LayoutParams().apply {
                width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
            })
        }
        panel.addView(grid)
        panel.addView(ScrollView(a).apply {
            addView(details.apply { typeface = Typeface.MONOSPACE; setPadding(a.dp(4), a.dp(10), a.dp(4), 0) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.END))
        panel.translationX = width.toFloat()
        panel.visibility = View.GONE
        scrim.setOnClickListener { close() }

        // Geste : balayer depuis le bord droit de l'écran ouvre le tiroir ; vers la droite le referme
        var downX = 0f
        panel.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; true }
                MotionEvent.ACTION_UP -> { if (e.rawX - downX > a.dp(60)) close(); true }
                else -> true
            }
        }
    }

    /** À appeler depuis dispatchTouchEvent de l'activité. */
    private var edgeStartX = -1f
    fun onTouch(e: MotionEvent): Boolean {
        if (isOpen) return false
        val screenW = a.resources.displayMetrics.widthPixels
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> edgeStartX = if (e.rawX > screenW - a.dp(22)) e.rawX else -1f
            MotionEvent.ACTION_MOVE -> if (edgeStartX > 0 && edgeStartX - e.rawX > a.dp(50)) { edgeStartX = -1f; open(); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeStartX = -1f
        }
        return false
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        stats.sample()          // amorce la mesure CPU
        scrim.visibility = View.VISIBLE; panel.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(220).start()
        panel.animate().translationX(0f).setDuration(260).start()
        refresh()
        handler.postDelayed(tick, 1000)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            a.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCb)
        NoxLog.d("APP", "Tiroir des jauges ouvert")
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        handler.removeCallbacks(tick)
        if (android.os.Build.VERSION.SDK_INT >= 33) a.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCb)
        scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationX(width.toFloat()).setDuration(220).withEndAction { panel.visibility = View.GONE }.start()
    }

    private fun refresh() {
        val s = stats.sample()
        ram.set(s.ramUsedPct, "${s.ramUsedPct.toInt()} %", "Nox ${NoxLog.gb(s.noxRss)}")
        cpu.set(s.noxCpuPct, "${s.noxCpuPct.toInt()} %", "puce à ${s.cpuFreqPct.toInt()} %")
        if (s.gpuPct != null) gpu.set(s.gpuPct, "${s.gpuPct.toInt()} %", s.gpuFreqMhz?.let { "$it MHz" } ?: "")
        else gpu.set(null, "n/d", if (a.engine.backendLabel.startsWith("GPU")) "actif (non mesurable)" else "inactif")
        bat.set(s.battPct.toFloat(), "${s.battPct} %", String.format(Locale.FRANCE, "%.1f °C", s.battTempC), invert = true)

        val e = a.engine
        details.text = buildString {
            append("MÉMOIRE\n")
            append("  RAM totale ${NoxLog.gb(s.ramTotal)} · libre ${NoxLog.gb(s.ramAvail)}\n")
            append("  Nox ${NoxLog.gb(s.noxRss)} (dont modèle/moteur ${NoxLog.gb(s.nativeHeap)})\n\n")
            append("PROCESSEUR\n")
            append("  Nox ${s.noxCpuPct.toInt()} % de la puce · ${s.cpuFreqText}\n")
            append("  GPU : ${s.gpuPct?.let { "${it.toInt()} % de charge" } ?: "mesure bloquée par Android"}${s.gpuFreqMhz?.let { " · $it MHz" } ?: ""}\n\n")
            append("ÉNERGIE\n")
            append("  ${s.battPct} % · ${if (s.charging) "en charge" else "sur batterie"} · ${s.battMa} mA · ${String.format(Locale.FRANCE, "%.1f", s.battTempC)} °C\n")
            append("  Thermique : ${s.thermal}\n\n")
            append("CERVEAU\n")
            append("  ${if (e.ready) "${e.modelLabel} · ${e.backendLabel}" else "non chargé"}${if (e.busy) " · en train d'écrire" else ""}\n")
            append("  Contexte occupé ${e.contextPct()} %\n")
            append("  ${NoxLog.summary()}\n\n")
            append("BIBLIOTHÈQUE\n  ${a.library.stats()}")
        }
    }
}
