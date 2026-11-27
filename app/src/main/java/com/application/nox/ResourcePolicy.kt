package com.application.nox

/**
 * Politique des ressources de Nox : décisions pures, déterministes et testables (aucune dépendance
 * Android). [ResourceManager] lui fournit les mesures et applique ses décisions ; les tests de la CI
 * la vérifient directement.
 *
 * Trois idées :
 *  - une seule échelle de pression (nominale, élevée, contrainte), avec hystérésis et temps de séjour
 *    pour ne jamais osciller ;
 *  - des activités qui se chevauchent (écoute, génération, parole, recherche) au lieu d'un état exclusif ;
 *  - un profil appris sur l'appareil (moyennes glissantes) qui ajuste les seuils au lieu de constantes figées.
 */
object ResourcePolicy {

    enum class Pressure { NOMINAL, ELEVATED, CONSTRAINED }

    enum class Activity { LISTENING, RETRIEVING, INFERENCE, SPEAKING, MULTIMODAL, MAINTENANCE }

    /** Mesures disponibles à un instant ; null = indisponible sur cet appareil. */
    data class Sample(
        /** PowerManager.THERMAL_STATUS_* (0 aucun … 6 arrêt). */
        val thermalStatus: Int,
        /** getThermalHeadroom : 0 froid → 1 bridage ; null si non fourni. */
        val headroom: Float?,
        val availMb: Long,
        val totalMb: Long,
        /** Android signale lui-même la mémoire basse. */
        val lowMemory: Boolean,
        /** Dernier niveau onTrimMemory reçu récemment (0 si aucun). */
        val trimLevel: Int,
    )

    // ------------------------------------------------------------------ pression

    /** Score de chaleur 0..1+ : le plus pessimiste du statut Android et de la marge thermique. */
    fun heat(s: Sample): Float {
        val byStatus = when (s.thermalStatus) {
            0 -> 0f; 1 -> 0.45f; 2 -> 0.65f; 3 -> 0.88f; else -> 1f
        }
        return maxOf(byStatus, s.headroom ?: 0f)
    }

    /** Score de mémoire 0..1 : part de RAM occupée, relevée si Android signale une tension. */
    fun memory(s: Sample): Float {
        if (s.totalMb <= 0) return 0f
        val used = 1f - s.availMb.toFloat() / s.totalMb
        // en dessous d'environ 9 % de RAM libre, Android commence à tuer des applis
        val byFree = ((used - 0.70f) / 0.21f).coerceIn(0f, 1f)
        val byTrim = when {
            s.trimLevel >= 15 -> 0.9f   // TRIM_MEMORY_RUNNING_CRITICAL
            s.trimLevel >= 10 -> 0.7f   // TRIM_MEMORY_RUNNING_LOW
            s.trimLevel >= 5 -> 0.5f    // TRIM_MEMORY_RUNNING_MODERATE
            else -> 0f
        }
        return maxOf(byFree, byTrim, if (s.lowMemory) 0.9f else 0f)
    }

    const val ENTER_ELEVATED = 0.62f
    const val EXIT_ELEVATED = 0.50f
    const val ENTER_CONSTRAINED = 0.86f
    const val EXIT_CONSTRAINED = 0.72f
    /** Une baisse de pression n'est acceptée qu'après ce temps passé au niveau courant. */
    const val DWELL_MS = 15_000L

    /**
     * Nouveau niveau de pression. Monter est immédiat (sécurité) ; descendre exige de passer sous le
     * seuil de sortie (hystérésis) et d'être resté [DWELL_MS] au niveau actuel (anti-oscillation).
     */
    fun pressure(current: Pressure, since: Long, now: Long, s: Sample): Pressure {
        val score = maxOf(heat(s), memory(s))
        val up = when {
            score >= ENTER_CONSTRAINED -> Pressure.CONSTRAINED
            score >= ENTER_ELEVATED -> Pressure.ELEVATED
            else -> Pressure.NOMINAL
        }
        if (up.ordinal > current.ordinal) return up
        if (now - since < DWELL_MS) return current
        return when (current) {
            Pressure.CONSTRAINED -> if (score < EXIT_CONSTRAINED) (if (score < EXIT_ELEVATED) Pressure.NOMINAL else Pressure.ELEVATED) else current
            Pressure.ELEVATED -> if (score < EXIT_ELEVATED) Pressure.NOMINAL else current
            Pressure.NOMINAL -> current
        }
    }

    // ------------------------------------------------------------------ animation

    /**
     * Durée d'une image d'animation (ms). La bouche qui parle garde au moins 25–30 images/s ; le reste
     * ralentit quand Gemma partage le processeur graphique ou quand le téléphone est sous contrainte.
     */
    fun frameBudgetMs(fps: Int, talking: Boolean, inference: Boolean, p: Pressure): Long {
        val nominal = 1000L / fps.coerceIn(1, 120)
        return when (p) {
            Pressure.CONSTRAINED -> if (talking) 40L else 66L
            Pressure.ELEVATED -> if (talking) 33L else maxOf(nominal, 50L)
            Pressure.NOMINAL -> when {
                inference -> if (talking) minOf(33L, nominal) else maxOf(42L, nominal)
                else -> nominal
            }
        }
    }

    /**
     * Intervalle entre deux redessins du texte en cours de génération (ms) : au moins 4 fois le coût du
     * dernier rendu (le texte long coûte plus cher à remettre en forme), borné entre une image et 1/4 s.
     */
    fun uiIntervalMs(lastRenderMs: Long, p: Pressure): Long {
        val base = when (p) { Pressure.NOMINAL -> 33L; Pressure.ELEVATED -> 66L; Pressure.CONSTRAINED -> 120L }
        return (lastRenderMs * 4).coerceIn(base, 250L)
    }

    /** Effets coûteux (halos, flous) seulement hors contrainte. */
    fun richEffects(p: Pressure) = p != Pressure.CONSTRAINED

    // ------------------------------------------------------------------ maintenance

    /** Au-delà, une tâche de fond différée obtient une tranche même pendant la parole (pas de famine). */
    const val STARVATION_MS = 120_000L

    /**
     * La maintenance (vectorisation de la bibliothèque…) peut-elle avancer ? Jamais pendant une génération
     * ni pendant l'écoute (une réponse va suivre : on garde la place), jamais sous contrainte ; après une
     * longue attente, elle passe pendant la parole pour ne pas être affamée.
     */
    fun maintenanceAllowed(active: Set<Activity>, p: Pressure, waitedMs: Long): Boolean {
        if (p == Pressure.CONSTRAINED) return false
        if (Activity.INFERENCE in active || Activity.LISTENING in active || Activity.RETRIEVING in active || Activity.MULTIMODAL in active) return false
        if (Activity.SPEAKING in active) return waitedMs >= STARVATION_MS
        return true
    }

    // ------------------------------------------------------------------ contexte

    const val EARLY_COMPACT_DEFAULT = 0.62f
    const val EARLY_COMPACT_MIN = 0.45f
    const val EARLY_COMPACT_MAX = 0.75f
    /** Mesures minimales par tranche avant de s'écarter du réglage par défaut. */
    const val MIN_SAMPLES = 5

    /**
     * Seuil de compactage anticipé appris : [ttft] = temps moyen avant le premier mot par tranche de
     * remplissage du contexte (0–25 %, 25–50 %, 50–75 %, 75–100 %), [counts] = nombre de mesures.
     * Si une tranche répond deux fois plus lentement que la première, on compacte avant elle ; si les
     * tranches hautes restent rapides, on laisse la conversation grandir un peu plus.
     */
    fun earlyCompact(ttft: FloatArray, counts: IntArray): Float {
        if (counts.isEmpty() || counts[0] < MIN_SAMPLES || ttft[0] <= 0f) return EARLY_COMPACT_DEFAULT
        for (b in 1 until ttft.size) {
            if (counts[b] < MIN_SAMPLES) continue
            if (ttft[b] >= ttft[0] * 2f) return (b * 0.25f).coerceIn(EARLY_COMPACT_MIN, EARLY_COMPACT_MAX)
        }
        val high = (2 until ttft.size).filter { counts[it] >= MIN_SAMPLES }
        return if (high.isNotEmpty() && high.all { ttft[it] < ttft[0] * 1.3f }) EARLY_COMPACT_MAX else EARLY_COMPACT_DEFAULT
    }

    /** Tranche de remplissage (0..3) d'un pourcentage de contexte. */
    fun bucket(ctxPct: Int) = (ctxPct / 25).coerceIn(0, 3)

    /** Moyenne glissante exponentielle (poids des nouvelles mesures : [alpha]). */
    fun ewma(old: Float, sample: Float, n: Int, alpha: Float = 0.2f) = if (n <= 0 || old <= 0f) sample else old + alpha * (sample - old)

    // ------------------------------------------------------------------ taille de mémoire de travail

    val CONTEXT_STEPS = intArrayOf(2048, 3072, 4096, 6144, 8192)
    const val KILL_WINDOW_MS = 7L * 24 * 3600 * 1000

    /**
     * Mémoire de travail de Gemma à charger. Elle ne change qu'au prochain chargement (un rechargement
     * coûte cher) : deux fermetures forcées par Android pour manque de mémoire en 7 jours font descendre
     * d'un cran sous la demande ; sans nouvelle fermeture pendant 7 jours, on revient à la demande.
     */
    fun contextCap(requested: Int, lowMemKills: List<Long>, now: Long): Int {
        val recent = lowMemKills.count { now - it < KILL_WINDOW_MS }
        if (recent < 2) return requested
        val below = CONTEXT_STEPS.filter { it < requested }
        return below.lastOrNull()?.coerceAtLeast(2048) ?: requested
    }
}
