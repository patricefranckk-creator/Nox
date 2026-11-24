package com.application.nox

import android.content.Context
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Le maillage du visage 3D : le visage canonique de MediaPipe (468 points, licence Apache 2.0,
 * assets/avatar), déformé à chaque image selon [FaceBrain] : mâchoire et lèvres qui articulent,
 * coins de bouche qui sourient, sourcils, paupières qui clignent. La bouche et les yeux sont ouverts
 * dans le maillage, pour laisser voir l'intérieur de la bouche et les yeux dessinés derrière.
 */
class FaceMesh(ctx: Context) {

    val count: Int
    private val rest: FloatArray
    /** Positions déformées (x, y, z) et normales, recalculées par [update]. */
    val pos: FloatArray
    val nor: FloatArray
    val triangles: ShortArray
    val edges: ShortArray

    private val upperLip = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308)
    private val lowerLip = intArrayOf(146, 91, 181, 84, 17, 314, 405, 321, 375, 95, 88, 178, 87, 14, 317, 402, 318, 324)
    private val corners = intArrayOf(61, 291, 78, 308)
    private val isUpper: BooleanArray
    private val isLower: BooleanArray
    private val isCorner: BooleanArray

    init {
        val vs = ArrayList<Float>()
        val tris = ArrayList<Short>()
        val holes = listOf(
            setOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 95, 88, 178, 87, 14, 317, 402, 318, 324),
            setOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246),
            setOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466),
        )
        ctx.assets.open("avatar/canonical_face_model.obj").bufferedReader().useLines { lines ->
            lines.forEach { l ->
                when {
                    l.startsWith("v ") -> l.split(' ').drop(1).take(3).forEach { vs += it.toFloat() }
                    l.startsWith("f ") -> {
                        val f = l.split(' ').drop(1).take(3).map { it.substringBefore('/').toInt() - 1 }
                        if (holes.none { h -> f.all { it in h } }) f.forEach { tris += it.toShort() }
                    }
                }
            }
        }
        count = vs.size / 3
        rest = vs.toFloatArray()
        pos = rest.copyOf()
        nor = FloatArray(rest.size)
        triangles = tris.toShortArray()
        val seen = HashSet<Int>()
        val e = ArrayList<Short>()
        for (i in triangles.indices step 3) for (k in 0 until 3) {
            val a = triangles[i + k].toInt(); val b = triangles[i + (k + 1) % 3].toInt()
            val key = if (a < b) a * 1000 + b else b * 1000 + a
            if (seen.add(key)) { e += a.toShort(); e += b.toShort() }
        }
        edges = e.toShortArray()
        isUpper = BooleanArray(count).also { a -> upperLip.forEach { a[it] = true } }
        isLower = BooleanArray(count).also { a -> lowerLip.forEach { a[it] = true } }
        isCorner = BooleanArray(count).also { a -> corners.forEach { a[it] = true } }
        NoxLog.i("AVATAR", "Visage 3D chargé : $count points, ${triangles.size / 3} triangles, ${edges.size / 2} arêtes")
    }

    private fun g(dx: Float, dy: Float, r: Float) = exp(-(dx * dx + dy * dy) / (r * r))

    /** Déforme le visage selon l'état courant du cerveau du visage. */
    fun update(b: FaceBrain) {
        val p = b.cur
        val open = b.mouthOpen.coerceIn(0f, 1.1f)
        val round = (1f - b.mouthWidth).coerceIn(-0.2f, 0.4f)
        // au repos les yeux sont un peu plus ouverts que le masque d'origine (regard plus présent)
        val eyeOpen = ((p.eye + 0.28f) * b.blinkK).coerceIn(0f, 1.7f)
        val browLift = -p.brow * 0.075f
        val arch = (p.arch - 6f) * 0.05f
        for (i in 0 until count) {
            val x = rest[i * 3]; val y = rest[i * 3 + 1]; val z = rest[i * 3 + 2]
            var dx = 0f; var dy = 0f; var dz = 0f

            // mâchoire et lèvre du bas
            val side = (1f - (abs(x) - 3.2f) / 3.6f).coerceIn(0f, 1f)
            val jaw = when {
                isUpper[i] && !isCorner[i] -> 0f
                isLower[i] -> 1f
                isCorner[i] -> 0.45f
                y < -4.3f -> ((-4.3f - y) / 0.5f).coerceIn(0f, 1f) * side
                else -> 0f
            }
            dy -= open * 1.5f * jaw
            dz -= open * 0.45f * jaw
            if (isUpper[i] && !isCorner[i]) dy += open * 0.18f

            // forme de la bouche : arrondie (o, ou) ou étirée (i), sourire aux coins
            val mouth = g(x, y + 4.3f, 2.4f)
            dx -= round * x * 0.55f * mouth
            dz += round * 0.5f * mouth
            val cs = g(abs(x) - 2.46f, y + 4.34f, 1.3f)
            dx += p.smile * 0.3f * cs * (if (x < 0) -1f else 1f)
            dy += p.smile * 0.45f * cs
            dy += p.smile * 0.12f * g(abs(x) - 3.2f, y - 1.9f, 1.1f)

            // sourcils (levés, froncés, intérieur relevé, un seul levé pour la question)
            if (y > 3.6f) {
                val brow = g(abs(x) - 3.3f, y - 5.0f, 1.9f)
                dy += (browLift + arch * g(abs(x) - 3.6f, y - 5.2f, 1.0f)) * brow
                dy += -p.inner * 0.07f * g(abs(x) - 1.4f, y - 5.0f, 0.9f)
                if (x > 0) dy += p.asym * 0.07f * brow
            }

            // paupières : la paupière haute descend vers la basse pour cligner, remonte pour s'écarquiller
            val ex = abs(x) - 3.15f
            if (abs(ex) < 1.9f && y > 2.6f && y < 3.9f) {
                val lid = (1f - (y - 2.96f) / 0.85f).coerceIn(0f, 1f) * (1f - abs(ex) / 1.9f).coerceIn(0f, 1f)
                dy += if (eyeOpen < 1f) -(1f - eyeOpen) * (y - 2.33f) * lid else (eyeOpen - 1f) * 0.55f * lid
            }
            if (abs(ex) < 1.6f && y < 2.6f && y > 1.6f) dy += p.smile * 0.14f * (1f - abs(ex) / 1.6f)

            pos[i * 3] = x + dx; pos[i * 3 + 1] = y + dy; pos[i * 3 + 2] = z + dz
        }
        normals()
    }

    private fun normals() {
        java.util.Arrays.fill(nor, 0f)
        for (i in triangles.indices step 3) {
            val a = triangles[i] * 3; val b = triangles[i + 1] * 3; val c = triangles[i + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            nor[a] += nx; nor[a + 1] += ny; nor[a + 2] += nz
            nor[b] += nx; nor[b + 1] += ny; nor[b + 2] += nz
            nor[c] += nx; nor[c + 1] += ny; nor[c + 2] += nz
        }
        for (i in 0 until count) {
            val l = sqrt(nor[i * 3] * nor[i * 3] + nor[i * 3 + 1] * nor[i * 3 + 1] + nor[i * 3 + 2] * nor[i * 3 + 2]).coerceAtLeast(1e-6f)
            nor[i * 3] /= l; nor[i * 3 + 1] /= l; nor[i * 3 + 2] /= l
        }
    }

    companion object {
        /** Centres des yeux (x, y, z) dans le repère du maillage. */
        val EYES = floatArrayOf(-3.15f, 2.63f, 3.45f, 3.15f, 2.63f, 3.45f)
    }
}
