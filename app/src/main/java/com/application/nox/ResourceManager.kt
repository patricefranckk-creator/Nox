package com.application.nox

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.PowerManager
import android.os.SystemClock
import com.application.nox.ResourcePolicy.Activity
import com.application.nox.ResourcePolicy.Pressure
import java.util.concurrent.Executors

/**
 * Intelligence opérationnelle de Nox : la seule autorité qui décide de l'usage des ressources.
 *
 * Observation par événements (statut thermique, onTrimMemory, fin de tour, changements d'activité) et
 * lectures ponctuelles bon marché, jamais de sondage permanent. Les décisions viennent de [ResourcePolicy] ;
 * chaque composant garde son exécution (Gemma, bibliothèque, animations) et lui demande seulement quoi faire.
 *
 * Si quelque chose échoue ici, chaque décision retombe sur la valeur nominale d'avant : jamais de blocage.
 */
object ResourceManager {

    private const val TAG = "RESSOURCES"
    private const val SAMPLE_MIN_MS = 4_000L

    private lateinit var app: Context
    private lateinit var profile: SharedPreferences
    private var power: PowerManager? = null
    private var am: ActivityManager? = null
    private val lock = Object()
    private val started get() = ::app.isInitialized

    @Volatile var pressure = Pressure.NOMINAL; private set
    private var pressureSince = 0L
    private val active = HashMap<Activity, Int>()
    @Volatile private var activeSet: Set<Activity> = emptySet()
    @Volatile var visible = false; private set
    private var trimLevel = 0
    private var trimAt = 0L
    private var lastSample: ResourcePolicy.Sample? = null
    private var lastSampleAt = 0L
    private var maintenanceWaitingSince = 0L

    /** Décisions marquantes depuis le lancement, pour le diagnostic. */
    private val decisions = ArrayDeque<String>()

    fun init(ctx: Context) {
        if (started) return
        app = ctx.applicationContext
        profile = app.getSharedPreferences("nox_profil_appareil", Context.MODE_PRIVATE)
        power = app.getSystemService(PowerManager::class.java)
        am = app.getSystemService(ActivityManager::class.java)
        pressureSince = SystemClock.uptimeMillis()
        try {
            power?.addThermalStatusListener(Executors.newSingleThreadExecutor { r -> Thread(r, "nox-ressources") }) { reassess("thermique $it") }
        } catch (t: Throwable) { NoxLog.w(TAG, "Écoute thermique indisponible : ${t.message}") }
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                synchronized(lock) { trimLevel = level; trimAt = SystemClock.uptimeMillis() }
                reassess("Android demande de libérer de la mémoire (niveau $level)")
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Remplacé par onTrimMemory") override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        })
        recordPastKills()
        reassess("démarrage")
        NoxLog.i(TAG, "Pilotage des ressources prêt · ${describe()}")
    }

    // ------------------------------------------------------------------ activités

    /** Jeton d'activité : libéré une seule fois, même si release() est appelé plusieurs fois (erreurs comprises). */
    class Lease internal constructor(private val a: Activity) {
        @Volatile private var open = true
        fun release() { if (open) { open = false; end(a) } }
    }

    fun begin(a: Activity): Lease {
        if (started) synchronized(lock) {
            active[a] = (active[a] ?: 0) + 1
            activeSet = active.filterValues { it > 0 }.keys.toSet()
        }
        return Lease(a)
    }

    private fun end(a: Activity) {
        if (!started) return
        synchronized(lock) {
            val n = (active[a] ?: 0) - 1
            if (n <= 0) active.remove(a) else active[a] = n
            activeSet = active.keys.toSet()
            lock.notifyAll()
        }
    }

    /** États visibles de Nox : écoute et parole suivent la page vocale ; la génération a son propre jeton. */
    private var listenLease: Lease? = null
    private var speakLease: Lease? = null

    fun uiState(state: NeuronView.State) {
        if (state == NeuronView.State.LISTENING) { if (listenLease == null) listenLease = begin(Activity.LISTENING) }
        else { listenLease?.release(); listenLease = null }
        if (state == NeuronView.State.SPEAKING) { if (speakLease == null) speakLease = begin(Activity.SPEAKING) }
        else { speakLease?.release(); speakLease = null }
    }

    fun setVisible(v: Boolean) { visible = v }

    fun isActive(a: Activity) = a in activeSet

    // ------------------------------------------------------------------ observation et pression

    private fun sample(force: Boolean): ResourcePolicy.Sample? {
        val now = SystemClock.uptimeMillis()
        lastSample?.let { if (!force && now - lastSampleAt < SAMPLE_MIN_MS) return it }
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi) ?: return null
        val headroom = try { power?.getThermalHeadroom(10)?.takeIf { !it.isNaN() } } catch (_: Throwable) { null }
        val trim = synchronized(lock) { if (now - trimAt < 60_000) trimLevel else 0 }
        val s = ResourcePolicy.Sample(power?.currentThermalStatus ?: 0, headroom, mi.availMem shr 20, mi.totalMem shr 20, mi.lowMemory, trim)
        lastSample = s; lastSampleAt = now
        return s
    }

    /** Recalcule la pression ; appelé sur événement, ou au plus toutes les quelques secondes par [current]. */
    fun reassess(cause: String) {
        if (!started) return
        try {
            val s = sample(force = true) ?: return
            val now = SystemClock.uptimeMillis()
            val next = ResourcePolicy.pressure(pressure, pressureSince, now, s)
            if (next != pressure) {
                decide("Pression ${label(pressure)} → ${label(next)} ($cause · chaleur ${"%.2f".format(ResourcePolicy.heat(s))} · mémoire ${"%.2f".format(ResourcePolicy.memory(s))} · libre ${s.availMb} Mo)")
                pressure = next
                pressureSince = now
                synchronized(lock) { lock.notifyAll() }
            }
        } catch (t: Throwable) { NoxLog.w(TAG, "Évaluation impossible ($cause) : ${t.message}") }
    }

    /** Pression actuelle, réévaluée si la dernière mesure date (lecture bon marché, sans réveil). */
    fun current(): Pressure {
        if (started && SystemClock.uptimeMillis() - lastSampleAt > SAMPLE_MIN_MS) reassess("mesure")
        return pressure
    }

    private fun label(p: Pressure) = when (p) { Pressure.NOMINAL -> "nominale"; Pressure.ELEVATED -> "élevée"; Pressure.CONSTRAINED -> "contrainte" }

    private fun decide(line: String) {
        NoxLog.i(TAG, line)
        synchronized(decisions) {
            decisions.addLast(java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date()) + " " + line)
            while (decisions.size > 12) decisions.removeFirst()
        }
    }

    // ------------------------------------------------------------------ décisions demandées par les composants

    /** Animations : durée d'une image. Repli nominal si le pilotage n'est pas prêt. */
    fun frameBudgetMs(fps: Int): Long = try {
        ResourcePolicy.frameBudgetMs(fps, FaceBrain.state == NeuronView.State.SPEAKING,
            Activity.INFERENCE in activeSet || (started && Nox.started && Nox.engine.busy), current())
    } catch (_: Throwable) { 1000L / fps.coerceAtLeast(1) }

    /** Rythme de mise à jour du texte pendant la génération. */
    fun uiIntervalMs(lastRenderMs: Long): Long = try { ResourcePolicy.uiIntervalMs(lastRenderMs, pressure) } catch (_: Throwable) { 33L }

    /**
     * Hologramme pendant la parole : la boucle « bouche fermée » demande un second décodeur vidéo.
     * Sous contrainte on s'en passe (la bouche suit alors la seule vidéo de parole).
     */
    fun secondDecoderAllowed(): Boolean = pressure != Pressure.CONSTRAINED

    /** Effets graphiques coûteux autorisés ? */
    fun richEffects(): Boolean = ResourcePolicy.richEffects(pressure)

    /**
     * Point de passage des tâches de fond : attend, sans boucle active, que la maintenance soit permise.
     * Réveillé à chaque fin d'activité et à chaque baisse de pression ; revérifie toutes les 30 s.
     */
    fun awaitMaintenance() {
        if (!started) return
        synchronized(lock) {
            if (maintenanceWaitingSince == 0L) maintenanceWaitingSince = SystemClock.uptimeMillis()
            var logged = false
            while (true) {
                val waited = SystemClock.uptimeMillis() - maintenanceWaitingSince
                if (ResourcePolicy.maintenanceAllowed(activeSet, pressure, waited)) break
                if (!logged) { NoxLog.d(TAG, "Indexation en pause (activité ${activeSet.joinToString()} · pression ${label(pressure)})"); logged = true }
                try { lock.wait(30_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                if (pressure != Pressure.NOMINAL) reassess("attente de maintenance")
            }
            maintenanceWaitingSince = 0L
        }
    }

    /** Seuil de compactage anticipé, appris sur cet appareil (valeur par défaut tant qu'il n'y a pas assez de mesures). */
    fun earlyCompact(): Float = try {
        if (!started) ResourcePolicy.EARLY_COMPACT_DEFAULT
        else ResourcePolicy.earlyCompact(FloatArray(4) { profile.getFloat("ttft_$it", 0f) }, IntArray(4) { profile.getInt("n_$it", 0) })
    } catch (_: Throwable) { ResourcePolicy.EARLY_COMPACT_DEFAULT }

    /** Mémoire de travail à charger (décidée seulement au chargement de Gemma). */
    fun contextFor(requested: Int): Int {
        if (!started) return requested
        val cap = ResourcePolicy.contextCap(requested, kills(), System.currentTimeMillis())
        if (cap != requested) decide("Mémoire de travail limitée à $cap jetons au lieu de $requested : Android a fermé Nox plusieurs fois par manque de mémoire cette semaine")
        return cap
    }

    // ------------------------------------------------------------------ apprentissage

    /** Fin d'un tour de génération : on nourrit le profil de l'appareil. */
    fun onTurn(ttftMs: Long, tps: Double, ctxPct: Int, backend: String) {
        if (!started || ttftMs <= 0) return
        try {
            val b = ResourcePolicy.bucket(ctxPct)
            val n = profile.getInt("n_$b", 0)
            val e = profile.edit()
            e.putFloat("ttft_$b", ResourcePolicy.ewma(profile.getFloat("ttft_$b", 0f), ttftMs.toFloat(), n))
            e.putInt("n_$b", (n + 1).coerceAtMost(10_000))
            if (tps > 0) {
                val k = "tps_" + backend.replace(Regex("[^A-Za-z0-9]"), "")
                e.putFloat(k, ResourcePolicy.ewma(profile.getFloat(k, 0f), tps.toFloat(), profile.getInt("n$k", 0)))
                e.putInt("n$k", profile.getInt("n$k", 0) + 1)
            }
            e.apply()
            val before = earlyCompactLogged
            val now = earlyCompact()
            if (before != null && kotlin.math.abs(now - before) > 0.01f) decide("Compactage anticipé : ${(before * 100).toInt()} % → ${(now * 100).toInt()} % du contexte (temps avant le premier mot mesuré par tranche)")
            earlyCompactLogged = now
        } catch (t: Throwable) { NoxLog.w(TAG, "Profil non mis à jour : ${t.message}") }
    }

    private var earlyCompactLogged: Float? = null

    /** Fermetures forcées pour manque de mémoire, relevées dans l'historique Android (une fois par lancement). */
    private fun recordPastKills() {
        try {
            val known = kills().toMutableSet()
            val since = profile.getLong("kills_seen", 0L)
            var newest = since
            for (x in am?.getHistoricalProcessExitReasons(app.packageName, 0, 16).orEmpty()) {
                if (x.timestamp <= since) continue
                newest = maxOf(newest, x.timestamp)
                val lowMem = x.reason == ApplicationExitInfo.REASON_LOW_MEMORY || (x.description ?: "").contains("LowMem", true)
                if (lowMem) known += x.timestamp
            }
            val cut = System.currentTimeMillis() - ResourcePolicy.KILL_WINDOW_MS
            profile.edit().putString("kills", known.filter { it > cut }.sorted().joinToString(",")).putLong("kills_seen", newest).apply()
            earlyCompactLogged = earlyCompact()
        } catch (t: Throwable) { NoxLog.w(TAG, "Historique des fermetures illisible : ${t.message}") }
    }

    private fun kills(): List<Long> = profile.getString("kills", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    // ------------------------------------------------------------------ observabilité

    fun describe(): String {
        if (!started) return "non démarré"
        val s = lastSample
        val heat = s?.let { "%.2f".format(ResourcePolicy.heat(it)) } ?: "?"
        val mem = s?.let { "%.2f".format(ResourcePolicy.memory(it)) } ?: "?"
        val tt = (0..3).joinToString(" / ") { b -> profile.getInt("n_$b", 0).let { n -> if (n == 0) "—" else "${profile.getFloat("ttft_$b", 0f).toInt()} ms (${n})" } }
        return "pression ${label(pressure)} · chaleur $heat · mémoire $mem · activités ${activeSet.joinToString().ifEmpty { "aucune" }} · " +
            "compactage anticipé ${(earlyCompact() * 100).toInt()} % · fermetures mémoire (7 j) ${kills().size} · 1er mot par remplissage 0-25/25-50/50-75/75-100 % : $tt"
    }

    fun recentDecisions(): List<String> = synchronized(decisions) { decisions.toList() }
}
