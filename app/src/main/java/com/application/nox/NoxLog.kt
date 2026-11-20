package com.application.nox

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Journal de bord de Nox : tout ce qui compte pour les performances et les erreurs.
 *
 *  - Niveaux : D (détail), I (info), P (performance chiffrée), W (avertissement), E (erreur).
 *  - Catégories : APP, MOTEUR, GEN, RAG, OUTIL, VOIX, OREILLE, VUE, SYS.
 *  - Écrit dans un fichier tournant (2 × 1,5 Mo) + mémoire vive pour l'affichage en direct.
 *  - Capture les plantages Java, et au lancement suivant la raison de la dernière fermeture
 *    donnée par Android (y compris les plantages natifs du pilote GPU).
 *  - Garde les mesures des derniers tours pour calculer des moyennes.
 */
object NoxLog {

    enum class Lvl(val tag: Char) { D('D'), I('I'), P('P'), W('W'), E('E') }

    data class Entry(val time: Long, val lvl: Lvl, val cat: String, val msg: String) {
        fun line(): String = "${TS.get()!!.format(Date(time))} ${lvl.tag} %-7s %s".format(Locale.ROOT, cat, msg)
    }

    /** Mesures d'un tour de parole, pour les moyennes. */
    data class Turn(
        val time: Long, val mode: String, val ttftMs: Long, val decodeMs: Long, val tokens: Int,
        val tps: Double, val ragMs: Long, val ragHits: Int, val tools: Int, val ctxPct: Int, val backend: String,
    )

    private val TS = ThreadLocal.withInitial { SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.FRANCE) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "nox-log").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val ring = ArrayDeque<Entry>()
    private val turns = ArrayDeque<Turn>()
    private var dir: File? = null
    private var appCtx: Context? = null
    @Volatile var verbose = false
    var listener: ((Entry) -> Unit)? = null

    const val MAX_RING = 3000
    private const val MAX_FILE = 1_500_000L

    // ------------------------------------------------------------------ démarrage

    fun init(ctx: Context, version: String) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        dir = File(ctx.filesDir, "journal").apply { mkdirs() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            // Écriture SYNCHRONE : le processus va mourir
            writeNow(Entry(System.currentTimeMillis(), Lvl.E, "CRASH", "Plantage dans « ${th.name} » : ${stack(ex)}"))
            prev?.uncaughtException(th, ex)
        }
        i("APP", "──────── Nox $version démarre ────────")
        i("SYS", device())
        lastExit(ctx)
    }

    private fun device(): String {
        val am = appCtx!!.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · SoC $soc · " +
            "${Runtime.getRuntime().availableProcessors()} cœurs · RAM ${gb(mi.totalMem)} (libre ${gb(mi.availMem)})"
    }

    /** Pourquoi Nox s'est-il fermé la dernière fois ? (Android 11+) */
    private fun lastExit(ctx: Context) {
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return
            val reason = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH -> "plantage Java"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage NATIF (pilote GPU / moteur)"
                ApplicationExitInfo.REASON_ANR -> "application figée (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "tuée par manque de mémoire"
                ApplicationExitInfo.REASON_SIGNALED -> "tuée par un signal"
                ApplicationExitInfo.REASON_EXIT_SELF -> "fermeture normale"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "fermée par l'utilisateur"
                ApplicationExitInfo.REASON_USER_STOPPED -> "arrêt forcé par l'utilisateur"
                ApplicationExitInfo.REASON_OTHER -> "autre (système)"
                else -> "code ${info.reason}"
            }
            val lvl = if (info.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY)) Lvl.E else Lvl.I
            log(lvl, "SYS", "Dernière fermeture : $reason le ${TS.get()!!.format(Date(info.timestamp))}" +
                (info.description?.let { " · $it" } ?: "") + " · mémoire alors ${gb(info.pss * 1024)} (PSS)")
        } catch (t: Throwable) {
            w("SYS", "Raison de fermeture illisible : ${t.message}")
        }
    }

    // ------------------------------------------------------------------ écriture

    fun d(cat: String, msg: String) { if (verbose) log(Lvl.D, cat, msg) }
    fun i(cat: String, msg: String) = log(Lvl.I, cat, msg)
    fun p(cat: String, msg: String) = log(Lvl.P, cat, msg)
    fun w(cat: String, msg: String, t: Throwable? = null) = log(Lvl.W, cat, if (t == null) msg else "$msg · ${t.javaClass.simpleName}: ${t.message}")
    fun e(cat: String, msg: String, t: Throwable? = null) = log(Lvl.E, cat, if (t == null) msg else "$msg · ${stack(t)}")

    fun log(lvl: Lvl, cat: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), lvl, cat, msg)
        when (lvl) {
            Lvl.E -> Log.e("Nox/$cat", msg); Lvl.W -> Log.w("Nox/$cat", msg); else -> Log.i("Nox/$cat", msg)
        }
        synchronized(ring) { ring.addLast(e); while (ring.size > MAX_RING) ring.removeFirst() }
        io.execute { writeNow(e) }
        listener?.let { l -> main.post { l(e) } }
    }

    /** Attend que les lignes en file soient écrites (avant de tuer le processus). */
    fun flush() {
        try { io.submit {}.get(800, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
    }

    private fun writeNow(e: Entry) {
        val d = dir ?: return
        try {
            val f = File(d, "nox.log")
            if (f.length() > MAX_FILE) { File(d, "nox.1.log").delete(); f.renameTo(File(d, "nox.1.log")) }
            File(d, "nox.log").appendText(e.line() + "\n")
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ mesures

    fun turn(t: Turn) {
        synchronized(turns) { turns.addLast(t); while (turns.size > 100) turns.removeFirst() }
        p("GEN", String.format(Locale.FRANCE,
            "Tour %s · 1er mot %d ms · %d jetons en %d ms = %.1f tok/s · RAG %d ms (%d) · outils %d · contexte %d %% · %s",
            t.mode, t.ttftMs, t.tokens, t.decodeMs, t.tps, t.ragMs, t.ragHits, t.tools, t.ctxPct, t.backend))
    }

    fun summary(): String {
        val l = synchronized(turns) { turns.toList() }
        if (l.isEmpty()) return "Aucun tour mesuré pour l'instant."
        fun med(v: List<Long>) = v.sorted()[v.size / 2]
        val tt = l.map { it.ttftMs }
        return String.format(Locale.FRANCE,
            "%d tours · 1er mot : médiane %d ms (min %d, max %d) · vitesse moyenne %.1f tok/s · RAG moyen %d ms · outils appelés %d fois · dernier moteur %s",
            l.size, med(tt), tt.min(), tt.max(), l.map { it.tps }.average(), l.map { it.ragMs }.average().toLong(),
            l.sumOf { it.tools }, l.last().backend)
    }

    /** Photo de la mémoire et de la température : à chaque chargement et chaque tour. */
    fun memSnapshot(): String {
        val ctx = appCtx ?: return ""
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val native = Debug.getNativeHeapAllocatedSize()
        val pm = ctx.getSystemService(PowerManager::class.java)
        val thermal = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "froid"; PowerManager.THERMAL_STATUS_LIGHT -> "tiède"
            PowerManager.THERMAL_STATUS_MODERATE -> "chaud"; PowerManager.THERMAL_STATUS_SEVERE -> "TRÈS chaud (bridage)"
            else -> "critique (bridage fort)"
        }
        return "RAM libre ${gb(mi.availMem)}${if (mi.lowMemory) " ⚠ BASSE" else ""} · tas natif ${gb(native)} · thermique $thermal"
    }

    // ------------------------------------------------------------------ lecture

    fun entries(): List<Entry> = synchronized(ring) { ring.toList() }

    /** Journal complet (fichiers), pour l'export. */
    fun fullText(): String {
        val d = dir ?: return ""
        return listOf(File(d, "nox.1.log"), File(d, "nox.log")).filter { it.exists() }.joinToString("") { it.readText() }
    }

    fun clear() {
        synchronized(ring) { ring.clear() }
        synchronized(turns) { turns.clear() }
        io.execute { dir?.listFiles()?.forEach { it.delete() } }
        i("APP", "Journal effacé")
    }

    fun gb(b: Long): String = if (b >= 1_000_000_000L) String.format(Locale.FRANCE, "%.2f Go", b / 1e9) else String.format(Locale.FRANCE, "%.0f Mo", b / 1e6)

    fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().lines().take(14).joinToString("\n    ")
    }

    /** Petit chronomètre : val c = NoxLog.chrono(); … c.ms() */
    class Chrono { private val t0 = System.nanoTime(); fun ms(): Long = (System.nanoTime() - t0) / 1_000_000 }
    fun chrono() = Chrono()
}
