package com.application.nox

import com.application.nox.ResourcePolicy.Activity
import com.application.nox.ResourcePolicy.Pressure

/** Politique des ressources : hystérésis, animation, maintenance, compactage appris, mémoire de travail. */
fun testResources(): Int {
    var bad = 0
    fun check(name: String, ok: Boolean) { if (ok) println("✓ ressources · $name") else { bad++; println("✗ ressources · $name") } }
    fun s(thermal: Int = 0, head: Float? = null, avail: Long = 4000, total: Long = 8000, low: Boolean = false, trim: Int = 0) =
        ResourcePolicy.Sample(thermal, head, avail, total, low, trim)

    check("froid et RAM libre → nominale", ResourcePolicy.pressure(Pressure.NOMINAL, 0, 100_000, s()) == Pressure.NOMINAL)
    check("très chaud → contrainte immédiate", ResourcePolicy.pressure(Pressure.NOMINAL, 99_000, 100_000, s(thermal = 3)) == Pressure.CONSTRAINED)
    check("mémoire basse Android → contrainte", ResourcePolicy.pressure(Pressure.NOMINAL, 0, 100_000, s(low = true)) == Pressure.CONSTRAINED)
    check("refroidi mais trop tôt → reste contrainte", ResourcePolicy.pressure(Pressure.CONSTRAINED, 95_000, 100_000, s()) == Pressure.CONSTRAINED)
    check("refroidi après le temps de séjour → nominale", ResourcePolicy.pressure(Pressure.CONSTRAINED, 0, 100_000, s()) == Pressure.NOMINAL)
    check("hystérésis : 0,55 ne fait pas redescendre l'élevée", ResourcePolicy.pressure(Pressure.ELEVATED, 0, 100_000, s(head = 0.55f)) == Pressure.ELEVATED)
    check("hystérésis : 0,55 ne fait pas monter la nominale", ResourcePolicy.pressure(Pressure.NOMINAL, 0, 100_000, s(head = 0.55f)) == Pressure.NOMINAL)
    check("RAM à 95 % → contrainte", ResourcePolicy.pressure(Pressure.NOMINAL, 0, 100_000, s(avail = 400)) == Pressure.CONSTRAINED)

    check("animation nominale 60 i/s", ResourcePolicy.frameBudgetMs(60, false, false, Pressure.NOMINAL) == 16L)
    check("Gemma réfléchit → 24 i/s", ResourcePolicy.frameBudgetMs(60, false, true, Pressure.NOMINAL) == 42L)
    check("parole pendant la génération → 30 i/s", ResourcePolicy.frameBudgetMs(60, true, true, Pressure.NOMINAL) == 33L)
    check("contrainte : parole 25 i/s", ResourcePolicy.frameBudgetMs(60, true, false, Pressure.CONSTRAINED) == 40L)
    check("contrainte : repos 15 i/s", ResourcePolicy.frameBudgetMs(60, false, false, Pressure.CONSTRAINED) == 66L)

    check("indexation libre au repos", ResourcePolicy.maintenanceAllowed(emptySet(), Pressure.NOMINAL, 0))
    check("indexation en pause pendant la génération", !ResourcePolicy.maintenanceAllowed(setOf(Activity.INFERENCE), Pressure.NOMINAL, 999_999))
    check("indexation en pause pendant l'écoute", !ResourcePolicy.maintenanceAllowed(setOf(Activity.LISTENING), Pressure.NOMINAL, 0))
    check("indexation en pause sous contrainte", !ResourcePolicy.maintenanceAllowed(emptySet(), Pressure.CONSTRAINED, 999_999))
    check("parole : attend, puis passe (pas de famine)",
        !ResourcePolicy.maintenanceAllowed(setOf(Activity.SPEAKING), Pressure.NOMINAL, 10_000) &&
            ResourcePolicy.maintenanceAllowed(setOf(Activity.SPEAKING), Pressure.NOMINAL, ResourcePolicy.STARVATION_MS))

    check("compactage : défaut sans mesures", ResourcePolicy.earlyCompact(FloatArray(4), IntArray(4)) == ResourcePolicy.EARLY_COMPACT_DEFAULT)
    check("compactage : lenteur dès 50 % → compacter à 50 %",
        ResourcePolicy.earlyCompact(floatArrayOf(900f, 1100f, 2000f, 2600f), intArrayOf(9, 9, 9, 9)) == 0.5f)
    check("compactage : toujours rapide → laisser grandir",
        ResourcePolicy.earlyCompact(floatArrayOf(900f, 950f, 1000f, 1050f), intArrayOf(9, 9, 9, 9)) == ResourcePolicy.EARLY_COMPACT_MAX)
    check("compactage : jamais sous le minimum",
        ResourcePolicy.earlyCompact(floatArrayOf(900f, 2000f, 0f, 0f), intArrayOf(9, 9, 0, 0)) == ResourcePolicy.EARLY_COMPACT_MIN)

    val now = 1_000_000_000_000L
    check("mémoire de travail : une fermeture ne change rien", ResourcePolicy.contextCap(4096, listOf(now - 1000), now) == 4096)
    check("mémoire de travail : deux fermetures → un cran plus bas", ResourcePolicy.contextCap(4096, listOf(now - 1000, now - 2000), now) == 3072)
    check("mémoire de travail : fermetures anciennes oubliées",
        ResourcePolicy.contextCap(4096, listOf(now - ResourcePolicy.KILL_WINDOW_MS - 1, now - ResourcePolicy.KILL_WINDOW_MS - 2), now) == 4096)
    check("mémoire de travail : jamais sous 2048", ResourcePolicy.contextCap(2048, listOf(now - 1, now - 2), now) == 2048)
    return bad
}
