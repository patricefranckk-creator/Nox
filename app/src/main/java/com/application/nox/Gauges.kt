package com.application.nox

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.application.nox.Ui.dp
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

/**
 * Le panneau des jauges, façon Lia : il descend sous l'en-tête quand on touche le bouton jauge.
 * Cartes à barre (RAM, Nox, CPU, GPU, batterie), rafraîchies chaque seconde tant qu'il est ouvert.
 */
class GaugePanel(private val a: MainActivity, private val root: FrameLayout, private val topOffset: () -> Int) {

    private class Card(ctx: Context, title: String) {
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, ctx.dp(18).toFloat())
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        }
        private val value = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Ui.BLACK }
        private val fill = View(ctx)
        private val rest = View(ctx)
        private val sub = TextView(ctx).apply { setTextColor(0xFFB9BDC8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Ui.BOLD }

        init {
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(TextView(ctx).apply { text = title; setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Ui.BLACK },
                LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(value)
            view.addView(head)
            val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            bar.addView(fill, LinearLayout.LayoutParams(0, ctx.dp(8), 50f))
            bar.addView(rest, LinearLayout.LayoutParams(0, ctx.dp(8), 50f).apply { marginStart = ctx.dp(6) })
            view.addView(bar, LinearLayout.LayoutParams(-1, ctx.dp(8)).apply { topMargin = ctx.dp(8) })
            view.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6) })
        }

        fun set(pct: Float?, valueText: String, color: Int, subText: String) {
            val p = (pct ?: 0f).coerceIn(0f, 100f)
            value.text = valueText
            value.setTextColor(if (pct == null) Ui.MUTED else color)
            val r = view.context.dp(4).toFloat()
            fill.background = Ui.rounded(if (pct == null) 0xFF4A4F5C.toInt() else color, r)
            rest.background = Ui.rounded(dim(color), r)
            (fill.layoutParams as LinearLayout.LayoutParams).weight = p.coerceAtLeast(1f)
            (rest.layoutParams as LinearLayout.LayoutParams).weight = (100f - p).coerceAtLeast(1f)
            fill.requestLayout()
            sub.text = subText
            sub.visibility = if (subText.isEmpty()) View.GONE else View.VISIBLE
        }

        /** Le même ton, assombri sur le gris du panneau (barre de fond). */
        private fun dim(c: Int): Int {
            val r = (c shr 16 and 0xFF) * 3 / 10 + 0x2A * 7 / 10
            val g = (c shr 8 and 0xFF) * 3 / 10 + 0x2E * 7 / 10
            val b = (c and 0xFF) * 3 / 10 + 0x38 * 7 / 10
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private val stats = SystemStats(a)
    private val handler = Handler(Looper.getMainLooper())
    private val scrim = View(a).apply { visibility = View.GONE; isClickable = true }
    private val panel = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.SHEET, a.dp(24).toFloat())
        setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(14))
        elevation = a.dp(16).toFloat()
        visibility = View.GONE
        isClickable = true
    }
    private val ram = Card(a, "RAM tél")
    private val nox = Card(a, "Nox")
    private val cpu = Card(a, "CPU Nox")
    private val gpu = Card(a, "GPU")
    private val bat = Card(a, "Batterie")
    var isOpen = false; private set
    var onChange: ((Boolean) -> Unit)? = null
    private val backCb = android.window.OnBackInvokedCallback { close() }

    private val tick = object : Runnable {
        override fun run() {
            if (!isOpen) return
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        val cards = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        listOf(ram, nox, cpu, gpu, bat).forEachIndexed { i, c ->
            cards.addView(c.view, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = a.dp(9) })
        }
        val links = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(6), a.dp(12), a.dp(6), 0) }
        links.addView(link("Détail RAM ›") { close(); details() }, LinearLayout.LayoutParams(0, -2, 1f))
        links.addView(link("Journal ›") { close(); JournalDialog(a).show() })
        cards.addView(links)
        panel.addView(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(cards) })

        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(panel, FrameLayout.LayoutParams((a.resources.displayMetrics.widthPixels * 0.9f).toInt(), -2, Gravity.TOP or Gravity.START))
        scrim.setOnClickListener { close() }
    }

    private fun link(t: String, onClick: () -> Unit) = TextView(a).apply {
        text = t; setTextColor(Ui.LAVENDER); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Ui.BLACK
        setPadding(0, a.dp(6), 0, a.dp(6))
        setOnClickListener { onClick() }
    }

    fun toggle() = if (isOpen) close() else open()

    fun open() {
        if (isOpen) return
        isOpen = true
        stats.sample()          // amorce la mesure CPU
        (panel.layoutParams as FrameLayout.LayoutParams).topMargin = topOffset()
        panel.requestLayout()
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        panel.alpha = 0f; panel.translationY = -a.dp(16).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(200).start()
        refresh()
        handler.postDelayed(tick, 1000)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            a.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCb)
        onChange?.invoke(true)
        NoxLog.d("APP", "Panneau des jauges ouvert")
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        handler.removeCallbacks(tick)
        if (android.os.Build.VERSION.SDK_INT >= 33) a.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCb)
        scrim.visibility = View.GONE
        panel.animate().alpha(0f).translationY(-a.dp(12).toFloat()).setDuration(160).withEndAction { panel.visibility = View.GONE }.start()
        onChange?.invoke(false)
    }

    private fun refresh() {
        val s = stats.sample()
        val e = Nox.engine
        ram.set(s.ramUsedPct, "${s.ramUsedPct.toInt()} %", level(s.ramUsedPct, 75f, 88f), "${NoxLog.gb(s.ramAvail)} libres")
        nox.set(s.noxRss * 100f / s.ramTotal, NoxLog.gb(s.noxRss), Ui.ACCENT, "sur ${NoxLog.gb(s.ramTotal)} · dont modèle ${NoxLog.gb(s.nativeHeap)}")
        cpu.set(s.noxCpuPct, "${s.noxCpuPct.toInt()} %", level(s.noxCpuPct, 60f, 85f),
            "${Runtime.getRuntime().availableProcessors()} cœurs · puce à ${s.cpuFreqPct.toInt()} %")
        if (s.gpuPct != null) gpu.set(s.gpuPct, "${s.gpuPct.toInt()} %", 0xFF5FD39A.toInt(), "Adreno" + (s.gpuFreqMhz?.let { " · $it MHz" } ?: ""))
        else gpu.set(null, "—", 0xFF5FD39A.toInt(), if (e.backendLabel.startsWith("GPU")) "Adreno actif · mesure bloquée par le téléphone" else "inactif (Nox tourne sur CPU)")
        val batColor = when { s.battPct <= 15 -> Ui.DANGER; s.battPct <= 30 -> Ui.AMBER; else -> Ui.GREEN }
        bat.set(s.battPct.toFloat(), String.format(Locale.FRANCE, "%d %% · %.0f °C", s.battPct, s.battTempC), batColor,
            (if (s.charging) "en charge" else "sur batterie") + " · ${s.battMa} mA · ${s.thermal}")
    }

    private fun level(v: Float, warn: Float, bad: Float) = when { v >= bad -> Ui.DANGER; v >= warn -> Ui.AMBER; else -> Ui.GREEN }

    /** Popup détaillée : mémoire, processeur, énergie, cerveau, bibliothèque. */
    private fun details() {
        val s = stats.sample()
        val e = Nox.engine
        val txt = buildString {
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
            append("BIBLIOTHÈQUE\n  ${Nox.library.stats()}")
        }
        val tv = TextView(a).apply {
            text = txt; setTextColor(0xFFC9CCD6.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        NoxDialog.Builder(a).setTitle("Détail").setIcon("📊").setView(tv).setPositiveButton("Fermer", null).show()
    }
}
