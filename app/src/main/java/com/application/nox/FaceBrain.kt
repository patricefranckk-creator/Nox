package com.application.nox

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * L'« esprit » du visage, commun au visage 2,5D et à l'avatar 3D : état réel de Nox, émotion de la
 * phrase, formes de bouche des mots prononcés, clignements, regard, respiration. Les vues ne font que
 * dessiner ce qu'il calcule ; la voix et la page vocale lui parlent directement.
 */
object FaceBrain {

    class Pose(var smile: Float, var brow: Float, var arch: Float, var inner: Float, var asym: Float,
               var eye: Float, var tilt: Float, var open: Float)

    private class Shape(val id: String, val open: Float, val width: Float, val ms: Float)

    @Volatile var state = NeuronView.State.IDLE
    @Volatile var emotion = Emotion.NEUTRE
    @Volatile private var level = 0f

    // sorties lues par les vues
    val cur = Pose(0f, 0f, 6f, 0f, 0f, 1f, 0f, 0f)
    var mouthOpen = 0f; private set
    var mouthWidth = 1f; private set
    /** Visème en cours (« A », « MBP », « OU »…), ou « » quand la bouche ne parle pas. */
    var viseme = ""; private set
    var levelS = 0f; private set
    var blinkK = 1f; private set
    var gx = 0f; private set
    var gy = 0f; private set
    var t = 0f; private set
    var breath = 0f; private set
    var nod = 0f; private set
    var sway = 0f; private set

    private val shapes = ArrayDeque<Shape>()
    private var shapeLeft = 0f
    private var lastWordAt = 0L
    private var talking = false
    private var blink = 0f
    private var nextBlink = 1.8f
    private var tgx = 0f; private var tgy = 0f; private var nextGaze = 1f
    private var lastState = state

    fun setLevel(v: Float) { level = v.coerceIn(0f, 1f) }

    /** La voix commence une phrase : émotion de la phrase. */
    @Synchronized fun sentence(text: String) {
        emotion = Emotion.of(text)
        talking = true
    }

    /** La voix prononce ce mot maintenant : formes de bouche (visèmes du français), son par son. */
    @Synchronized fun word(w: String, rate: Float) {
        lastWordAt = SystemClock.uptimeMillis()
        val f = Text.fold(w.lowercase())
        val unit = 85f / rate.coerceIn(0.5f, 2f)
        var i = 0
        val out = ArrayList<Shape>()
        while (i < f.length) {
            val c = f[i]
            val two = if (i + 1 < f.length) f.substring(i, i + 2) else ""
            val three = if (i + 2 < f.length) f.substring(i, i + 3) else ""
            val last = i == f.length - 1
            when {
                last && f.length > 2 && c in "stdxz" -> i++
                c in "mbp" -> { out += Shape("MBP", 0f, 0.95f, unit * 0.55f); i++ }
                c in "fv" -> { out += Shape("FV", 0.12f, 1.02f, unit * 0.5f); i++ }
                two == "ch" || c == 'j' || (c == 'g' && i + 1 < f.length && f[i + 1] in "ei") -> { out += Shape("CHJ", 0.15f, 0.8f, unit * 0.6f); i += if (two == "ch") 2 else 1 }
                two == "gn" -> { out += Shape("RKG", 0.25f, 1f, unit * 0.5f); i += 2 }
                three == "eau" -> { out += Shape("O", 0.55f, 0.72f, unit * 1.2f); i += 3 }
                three == "ain" || three == "ein" -> { out += Shape("E", 0.45f, 1.05f, unit); i += 3 }
                two == "ou" -> { out += Shape("OU", 0.32f, 0.66f, unit); i += 2 }
                two == "au" || two == "on" || two == "om" -> { out += Shape("O", 0.55f, 0.74f, unit); i += 2 }
                two == "oi" -> { out += Shape("OU", 0.4f, 0.72f, unit * 0.5f); out += Shape("A", 0.85f, 1f, unit * 0.7f); i += 2 }
                two == "eu" || two == "oe" -> { out += Shape("EU", 0.45f, 0.8f, unit); i += 2 }
                two == "an" || two == "en" || two == "am" || two == "em" -> { out += Shape("EU", 0.7f, 0.9f, unit); i += 2 }
                two == "ai" || two == "ei" -> { out += Shape("E", 0.5f, 1.04f, unit); i += 2 }
                two == "in" -> { out += Shape("E", 0.45f, 1.05f, unit); i += 2 }
                c == 'a' -> { out += Shape("A", 0.9f, 1f, unit); i++ }
                c == 'o' -> { out += Shape("O", 0.6f, 0.75f, unit); i++ }
                c == 'u' -> { out += Shape("OU", 0.3f, 0.65f, unit); i++ }
                c == 'e' -> { out += if (last) Shape("EU", 0.2f, 1f, unit * 0.5f) else Shape("E", 0.5f, 1.04f, unit); i++ }
                c == 'i' || c == 'y' -> { out += Shape("I", 0.3f, 1.15f, unit * 0.85f); i++ }
                c in "ltdn" -> { out += Shape("LTDN", 0.25f, 1f, unit * 0.4f); i++ }
                c in "szx" || (c == 'c' && i + 1 < f.length && f[i + 1] in "ei") -> { out += Shape("SZ", 0.15f, 1.05f, unit * 0.45f); i++ }
                c in "rkgqc" -> { out += Shape("RKG", 0.25f, 1f, unit * 0.4f); i++ }
                c.isLetter() -> { out += Shape("LTDN", 0.22f, 1f, unit * 0.35f); i++ }
                else -> i++
            }
        }
        if (out.isEmpty()) out += Shape("A", 0.4f, 1f, unit)
        shapes.clear()
        shapes.addAll(out)
        shapeLeft = 0f
    }

    /** La voix s'est tue (fin de phrase ou interruption). */
    @Synchronized fun silent() { talking = false; shapes.clear() }

    private fun target(): Pose {
        val p = when (emotion) {
            Emotion.JOIE -> Pose(1f, -3f, 9f, 0f, 0f, 0.82f, 3f, 0.12f)
            Emotion.EMPATHIE -> Pose(0.35f, -1f, 3f, -7f, 0f, 0.88f, -7f, 0f)
            Emotion.SURPRISE -> Pose(0f, -11f, 12f, 0f, 0f, 1.3f, 0f, 0.45f)
            Emotion.QUESTION -> Pose(0.1f, -2f, 7f, 0f, 9f, 1.05f, 8f, 0f)
            Emotion.SERIEUX -> Pose(-0.25f, 4f, 2f, 5f, 0f, 0.9f, 0f, 0f)
            else -> Pose(0f, 0f, 6f, 0f, 0f, 1f, 0f, 0f)
        }
        when (state) {
            NeuronView.State.OFF -> { p.eye *= 0.45f; p.smile *= 0.4f }
            NeuronView.State.LISTENING -> { p.eye *= 1.06f; p.tilt += 4f }
            NeuronView.State.THINKING -> { p.brow += 2f; p.inner += 2f; p.eye *= 0.95f }
            else -> {}
        }
        return p
    }

    /** Avance l'animation de dt secondes (appelé par la vue affichée, à chaque image). */
    @Synchronized fun step(dt: Float) {
        t += dt
        if (state != lastState) {
            if (lastState == NeuronView.State.SPEAKING) { talking = false; shapes.clear(); emotion = Emotion.NEUTRE }
            lastState = state
        }
        val tg = target()
        val k = min(1f, dt * 4f)
        cur.smile += (tg.smile - cur.smile) * k; cur.brow += (tg.brow - cur.brow) * k; cur.arch += (tg.arch - cur.arch) * k
        cur.inner += (tg.inner - cur.inner) * k; cur.asym += (tg.asym - cur.asym) * k; cur.eye += (tg.eye - cur.eye) * k
        cur.tilt += (tg.tilt - cur.tilt) * k; cur.open += (tg.open - cur.open) * k
        levelS += (level - levelS) * min(1f, dt * 10f)

        nextBlink -= dt
        if (blink > 0f) blink = max(0f, blink - dt)
        if (nextBlink <= 0f) {
            blink = 0.16f
            nextBlink = (if (state == NeuronView.State.OFF) 4.5f else 2f) + (Math.random() * 4).toFloat() - if (Math.random() < 0.15) 1.6f else 0f
        }
        blinkK = if (blink > 0f) abs(blink - 0.08f) / 0.08f else 1f

        nextGaze -= dt
        if (nextGaze <= 0f) {
            when (state) {
                NeuronView.State.THINKING -> { tgx = -4f + (Math.random() * 2).toFloat(); tgy = -5f }
                NeuronView.State.OFF -> { tgx = ((Math.random() - 0.5) * 3).toFloat(); tgy = 3f }
                NeuronView.State.LISTENING -> { tgx = ((Math.random() - 0.5) * 2).toFloat(); tgy = 0f }
                else -> { tgx = ((Math.random() - 0.5) * 6).toFloat(); tgy = ((Math.random() - 0.5) * 3).toFloat() }
            }
            nextGaze = 0.8f + (Math.random() * 2.6).toFloat()
        }
        val g = min(1f, dt * 4.5f)
        gx += (tgx - gx) * g; gy += (tgy - gy) * g

        val sleeping = state == NeuronView.State.OFF
        breath = sin(t * (if (sleeping) 1.1f else 1.7f)) * (if (sleeping) 2.2f else 1.4f)
        nod = if (state == NeuronView.State.LISTENING) max(0f, sin(t * 2.6f)) * (1.5f + levelS * 3f) else 0f
        sway = sin(t * 0.7f) * 1.2f + if (state == NeuronView.State.SPEAKING) sin(t * 2.3f) * 1.3f else 0f

        var wantOpen = cur.open
        var wantWidth = 1f
        val speaking = state == NeuronView.State.SPEAKING && talking
        viseme = ""
        if (speaking && shapes.isNotEmpty()) {
            val s = shapes.first()
            viseme = s.id
            wantOpen = max(wantOpen, s.open); wantWidth = s.width
            shapeLeft += dt * 1000f
            if (shapeLeft >= s.ms) { shapes.removeFirst(); shapeLeft = 0f }
        } else if (speaking && SystemClock.uptimeMillis() - lastWordAt > 450) {
            val syl = abs(sin(t * 10.5f)) * 0.6f + abs(sin(t * 6.7f + 1f)) * 0.4f
            wantOpen = max(wantOpen, max(0f, syl - 0.2f) * 0.8f)
        }
        val m = min(1f, dt * 18f)
        mouthOpen += (wantOpen - mouthOpen) * m
        mouthWidth += (wantWidth - mouthWidth) * m
    }
}
