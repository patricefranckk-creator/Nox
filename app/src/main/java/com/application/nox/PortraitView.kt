package com.application.nox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.TextureView
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Le portrait animé de Nox (marionnette 2D multicouche, OpenGL ES 3).
 * Passe 1 : le portrait et ses variantes (bouches, yeux, sourcils) sont mélangés dans une texture,
 * avec des poids lissés calculés d'après [FaceBrain] (visèmes des mots prononcés, clignements, émotion).
 * Passe 2 : cette texture est posée sur une grille déformée (tête qui tourne et s'incline, hochements,
 * respiration des épaules, regard), et la lumière améthyste est ajoutée (veines, gemme, halo, énergie
 * qui circule). Fil de rendu à 60 images/s, 15 en cas de chauffe, pause quand il est caché.
 */
class PortraitView(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {

    /** Appelé (fil principal) si le rendu est impossible ou si les ressources manquent. */
    var onFail: ((String) -> Unit)? = null

    private var renderer: Renderer? = null
    private var texture: SurfaceTexture? = null
    private var tw = 0
    private var th = 0
    private val main = Handler(Looper.getMainLooper())
    private val power = ctx.getSystemService(PowerManager::class.java)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    /** Nouvelles ressources importées : le rendu repart avec elles. */
    fun reload() {
        renderer?.let { it.running = false; it.interrupt(); it.join(500) }
        renderer = null
        if (isShown) startRenderer()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        texture = st; tw = w; th = h
        startRenderer()
    }

    private fun startRenderer() {
        val st = texture ?: return
        if (renderer?.isAlive == true || !PortraitAssets.ready(context)) return
        renderer = Renderer(st).also { it.w = tw; it.h = th; it.paused = !isShown; it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { tw = w; th = h; renderer?.let { it.w = w; it.h = h } }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        renderer?.let { it.running = false; it.interrupt(); it.join(500) }
        renderer = null
        texture = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        renderer?.paused = !isShown
        if (isShown && renderer?.isAlive != true) startRenderer()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        renderer?.paused = visibility != VISIBLE || !isShown
    }

    // ================================================================== rendu

    private inner class Renderer(st: SurfaceTexture) : GlThread(st, "nox-portrait", power, 60, { t ->
        NoxLog.e("PORTRAIT", "Portrait animé impossible, retour au visage 2,5D", t)
        main.post { onFail?.invoke("Portrait animé indisponible : ${t.message}") }
    }) {
        private val W = PortraitAssets.W.toFloat()
        private val H = PortraitAssets.H.toFloat()
        private var compProg = 0
        private var warpProg = 0
        private val buf = IntArray(3)
        private var gridIndexCount = 0
        private val fbo = IntArray(1)
        private val fboTex = IntArray(1)
        private var baseTex = 0
        private var lightTex = 0
        private var gemX = 515f
        private var gemY = 273f

        private inner class Tex(val layer: PortraitAssets.Layer, val id: Int)
        private val layers = HashMap<String, Tex>()
        private val weights = HashMap<String, Float>()
        private val targets = HashMap<String, Float>()

        private var vein = 0.4f
        private var gem = 0.5f
        private var dim = 0f
        private var flow = 0f
        private var logged = false

        override fun setup() {
            val assets = PortraitAssets.load(context) ?: error("ressources du portrait absentes")
            compProg = program(COMP_VS, COMP_FS)
            warpProg = program(WARP_VS, WARP_FS)
            baseTex = upload(assets.base)
            lightTex = upload(assets.light)
            gemX = assets.gemX; gemY = assets.gemY
            for ((name, l) in assets.layers) layers[name] = Tex(l, upload(l.bitmap))
            assets.base.recycle(); assets.light.recycle(); assets.layers.values.forEach { it.bitmap.recycle() }

            GLES30.glGenBuffers(3, buf, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)), GLES30.GL_STATIC_DRAW)
            val cols = 33; val rows = 37
            val grid = FloatArray(cols * rows * 2)
            for (r in 0 until rows) for (c in 0 until cols) { grid[(r * cols + c) * 2] = W * c / (cols - 1); grid[(r * cols + c) * 2 + 1] = H * r / (rows - 1) }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf[1])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, grid.size * 4, floats(grid), GLES30.GL_STATIC_DRAW)
            val idx = ArrayList<Short>()
            for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
                val a = r * cols + c; val b = a + 1; val d = a + cols; val e = d + 1
                idx += a.toShort(); idx += d.toShort(); idx += b.toShort(); idx += b.toShort(); idx += d.toShort(); idx += e.toShort()
            }
            gridIndexCount = idx.size
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buf[2])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, shorts(idx.toShortArray()), GLES30.GL_STATIC_DRAW)

            GLES30.glGenTextures(1, fboTex, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, W.toInt(), H.toInt(), 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            texParams()
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex[0], 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "tampon de composition refusé" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            NoxLog.i("PORTRAIT", "Portrait animé prêt : ${layers.size} variante(s) ${layers.keys.sorted().joinToString()} · OpenGL ES ${GLES30.glGetString(GLES30.GL_VERSION)}")
        }

        private fun upload(b: Bitmap): Int {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            val bb = ByteBuffer.allocateDirect(b.byteCount).order(ByteOrder.nativeOrder())
            b.copyPixelsToBuffer(bb); bb.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, b.width, b.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
            texParams()
            return t[0]
        }

        private fun texParams() {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        // ------------------------------------------------------------ poids des variantes

        private fun has(n: String) = layers.containsKey(n.lowercase())

        private fun aim(dt: Float) {
            val b = FaceBrain
            targets.clear()
            val st = b.state
            // bouche : visèmes des mots prononcés, sinon bouche d'expression
            if (st == NeuronView.State.SPEAKING) {
                val v = "bouche_" + b.viseme
                when {
                    b.viseme == "REPOS" && has("bouche_repos") -> targets["bouche_repos"] = 1f
                    b.viseme.isNotEmpty() && has(v) -> targets[v] = 1f
                    (b.viseme == "MBP" || b.viseme == "FV") && has("bouche_MBP") -> targets["bouche_MBP"] = if (b.viseme == "MBP") 1f else 0.7f
                    else -> {
                        val a = ((b.mouthOpen - 0.12f) / 0.75f).coerceIn(0f, 1f)
                        if (has("bouche_A")) targets["bouche_A"] = a
                        if (has("bouche_repos")) targets["bouche_repos"] = (1f - a) * 0.85f
                    }
                }
            } else {
                val (name, w, alt) = when {
                    st == NeuronView.State.THINKING -> Triple("bouche_reflexion", 1f, "bouche_repos")
                    st == NeuronView.State.OFF -> Triple("bouche_repos", 0.6f, null)
                    b.emotion == Emotion.JOIE -> Triple("bouche_sourire", 1f, null)
                    b.emotion == Emotion.EMPATHIE -> Triple("bouche_empathie", 1f, null)
                    b.emotion == Emotion.SURPRISE -> Triple("bouche_surprise", 1f, "bouche_A")
                    b.emotion == Emotion.QUESTION -> Triple("bouche_question", 1f, "bouche_repos")
                    b.emotion == Emotion.SERIEUX -> Triple("bouche_reflexion", 1f, "bouche_repos")
                    st == NeuronView.State.LISTENING -> Triple("bouche_repos", 0.4f, null)
                    else -> Triple("", 0f, null)
                }
                when {
                    has(name) -> targets[name] = w
                    alt != null && has(alt) -> targets[alt] = if (alt == "bouche_A") 0.45f else 0.7f
                }
            }
            // yeux : clignement et veille, puis émotion
            val closed = (1f - b.blinkK * min(1f, b.cur.eye + 0.05f)).coerceIn(0f, 1f)
            if (has("yeux_mi_clos")) {
                targets["yeux_mi_clos"] = if (closed < 0.5f) closed * 2f else 2f - closed * 2f
                if (has("yeux_fermes")) targets["yeux_fermes"] = ((closed - 0.5f) * 2f).coerceIn(0f, 1f)
            } else if (has("yeux_fermes")) targets["yeux_fermes"] = closed
            val open = 1f - closed
            when (b.emotion) {
                Emotion.JOIE -> if (has("yeux_plisses")) targets["yeux_plisses"] = 0.8f * open
                Emotion.SURPRISE -> if (has("yeux_ecarquilles")) targets["yeux_ecarquilles"] = open
                Emotion.EMPATHIE -> if (has("yeux_tristes")) targets["yeux_tristes"] = 0.5f * open
            }
            // sourcils
            when {
                b.emotion == Emotion.SURPRISE -> targets["sourcils_leves"] = 1f
                b.emotion == Emotion.JOIE -> targets["sourcils_leves"] = 0.35f
                b.emotion == Emotion.QUESTION -> targets["sourcils_question"] = 1f
                b.emotion == Emotion.EMPATHIE -> targets["sourcils_tristes"] = 0.7f
                b.emotion == Emotion.SERIEUX -> targets["sourcils_fronces"] = 0.9f
                st == NeuronView.State.THINKING -> targets["sourcils_fronces"] = 0.7f
            }
            // variante éteinte (si fournie) quand Nox dort
            if (st == NeuronView.State.OFF) targets["base_eteinte"] = 0.8f

            // les fichiers importés sont rangés en minuscules (bouche_a, bouche_mbp…)
            val wanted = HashMap<String, Float>()
            for ((k, v) in targets) wanted[k.lowercase()] = v
            for (name in layers.keys) {
                val t = wanted[name] ?: 0f
                val rate = when {
                    name.startsWith("bouche_") -> 22f
                    name.startsWith("yeux_") -> 40f
                    else -> 6f
                }
                val w = weights[name] ?: 0f
                weights[name] = w + (t - w) * min(1f, dt * rate)
            }

            // lumière améthyste
            val t = b.t
            val tau = (2 * PI).toFloat()
            val (tv, tg, td, tf) = when (st) {
                NeuronView.State.OFF -> { val p = 0.5f + 0.5f * sin(t * tau / 4f); floatArrayOf(0.15f + 0.25f * p, 0.25f + 0.35f * p, 0.55f, 0f) }
                NeuronView.State.LISTENING -> floatArrayOf(0.55f + b.levelS * 0.4f, 0.7f + b.levelS * 0.3f, 0f, 0f)
                NeuronView.State.THINKING -> floatArrayOf(0.3f, 0.6f + 0.35f * sin(t * 4f), 0f, 1f)
                NeuronView.State.SPEAKING -> floatArrayOf(0.4f + b.mouthOpen * 0.6f, 0.6f + b.mouthOpen * 0.5f, 0f, 0f)
                else -> floatArrayOf(0.35f + 0.1f * sin(t * 1.2f), 0.5f + 0.2f * sin(t * 1.7f + 1f), 0.1f, 0f)
            }
            val k = min(1f, dt * if (st == NeuronView.State.SPEAKING) 16f else 5f)
            vein += (tv - vein) * k; gem += (tg - gem) * k; dim += (td - dim) * k; flow += (tf - flow) * min(1f, dt * 3f)
        }

        // ------------------------------------------------------------ image

        override fun frame(dt: Float, hot: Boolean) {
            FaceBrain.step(dt)
            synchronized(FaceBrain) { aim(dt) }
            val b = FaceBrain

            // passe 1 : composition du portrait et des variantes, à plat
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glViewport(0, 0, W.toInt(), H.toInt())
            GLES30.glUseProgram(compProg)
            attrib(buf[0], 0, 2)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            u1i(compProg, "uTex", 0)
            GLES30.glDisable(GLES30.GL_BLEND)
            quad(baseTex, 0f, 0f, W, H, 1f)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            for (group in GROUPS) {
                val names = layers.keys.filter { n -> group.any { n.startsWith(it) } }.sorted()
                // fondu linéaire : alpha_k = w_k / (1 - somme des poids dessinés après)
                var after = 0f
                val alphas = names.reversed().map { n -> val w = (weights[n] ?: 0f).coerceIn(0f, 1f); val a = if (after >= 0.999f) 0f else w / (1f - after); after += w; n to a.coerceIn(0f, 1f) }.reversed()
                for ((n, a) in alphas) if (a > 0.003f) layers[n]?.let { tx -> quad(tx.id, tx.layer.x.toFloat(), tx.layer.y.toFloat(), tx.layer.w.toFloat(), tx.layer.h.toFloat(), a) }
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            // passe 2 : grille déformée + lumière
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (w == 0 || h == 0) return
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(warpProg)
            val s = min(w / W, h / H)
            u4(warpProg, "uFit", s, (w - W * s) / 2f, (h - H * s) / 2f, 0f)
            u2(warpProg, "uView", w.toFloat(), h.toFloat())
            u2(warpProg, "uCanvas", W, H)
            val yaw = (b.gx / 6f + sin(b.t * 0.45f) * 0.12f).coerceIn(-0.7f, 0.7f)
            val pitch = (-b.gy / 7f + b.nod * 0.12f).coerceIn(-0.7f, 0.7f)
            u4(warpProg, "uHead", yaw, pitch, Math.toRadians((-(b.cur.tilt + b.sway) * 0.55f).toDouble()).toFloat(), b.breath)
            u2(warpProg, "uGaze", b.gx * 1.1f, b.gy * 0.8f)
            u4(warpProg, "uLight", vein, gem, dim, flow)
            u2(warpProg, "uGem", gemX, gemY)
            u1(warpProg, "uPhase", (b.t * 0.55f) % 1f)
            u1(warpProg, "uHot", if (hot || Nox.engine.busy) 1f else 0f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex[0])
            u1i(warpProg, "uComp", 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lightTex)
            u1i(warpProg, "uLightMap", 1)
            attrib(buf[1], 0, 2)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buf[2])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, gridIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

            if (!logged) { logged = true; NoxLog.i("PORTRAIT", "Première image du portrait dessinée (${w}×$h)") }
        }

        private fun quad(tex: Int, x: Float, y: Float, qw: Float, qh: Float, alpha: Float) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            u4(compProg, "uRect", x, y, qw, qh)
            u2(compProg, "uCanvas", W, H)
            u1(compProg, "uAlpha", alpha)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    companion object {
        /** Ordre de dessin : éteinte, sourcils, yeux, bouche (une zone peut toucher la voisine). */
        private val GROUPS = listOf(listOf("base_eteinte"), listOf("sourcils_"), listOf("yeux_"), listOf("bouche_"))

        private const val COMP_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
uniform vec4 uRect;
uniform vec2 uCanvas;
out vec2 vUv;
void main() {
    vec2 p = uRect.xy + aPos * uRect.zw;
    gl_Position = vec4(p.x / uCanvas.x * 2.0 - 1.0, 1.0 - p.y / uCanvas.y * 2.0, 0.0, 1.0);
    vUv = aPos;
}"""

        private const val COMP_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uAlpha;
out vec4 o;
void main() {
    vec4 c = texture(uTex, vUv);
    o = vec4(c.rgb, c.a * uAlpha);
}"""

        // Déformation : la tête (ellipse autour du visage) tourne, s'incline et hoche ; les épaules respirent.
        private const val WARP_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
uniform vec4 uFit;
uniform vec2 uView;
uniform vec4 uHead;
out vec2 vP;
void main() {
    vec2 p = aPos;
    vec2 c = vec2(512.0, 470.0);
    vec2 d = (p - c) / vec2(345.0, 480.0);
    float r = length(d);
    float head = (1.0 - smoothstep(0.9, 1.3, r)) * (1.0 - smoothstep(780.0, 900.0, p.y));
    float bulge = sqrt(max(0.0, 1.0 - min(r * r, 1.0)));
    vec2 q = p;
    q += vec2(uHead.x * 26.0, uHead.y * 20.0) * bulge * head;
    vec2 piv = vec2(512.0, 880.0);
    float a = uHead.z * head;
    vec2 v = q - piv;
    q = piv + vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a));
    float sh = smoothstep(860.0, 1040.0, p.y);
    q.y -= uHead.w * (1.2 * (1.0 - sh) + 2.6 * sh);
    q.x = 512.0 + (q.x - 512.0) * (1.0 + uHead.w * 0.003 * sh);
    vec2 s = q * uFit.x + uFit.yz;
    gl_Position = vec4(s.x / uView.x * 2.0 - 1.0, 1.0 - s.y / uView.y * 2.0, 0.0, 1.0);
    vP = aPos;
}"""

        // Couleur : portrait composé + regard (déplacement local autour des iris) + lumière améthyste.
        private const val WARP_FS = """#version 300 es
precision highp float;
in vec2 vP;
uniform vec2 uCanvas;
uniform vec2 uGaze;
uniform vec4 uLight;
uniform vec2 uGem;
uniform float uPhase;
uniform float uHot;
uniform sampler2D uComp;
uniform sampler2D uLightMap;
out vec4 o;
float eye(vec2 p, vec2 e) { vec2 d = p - e; return exp(-dot(d, d) / (2.0 * 15.0 * 15.0)); }
void main() {
    vec2 p = vP;
    float g = max(eye(p, vec2(390.0, 420.0)), eye(p, vec2(642.0, 420.0)));
    vec2 sp = p - uGaze * g;
    vec2 uv = sp / uCanvas;
    vec3 col = texture(uComp, vec2(uv.x, 1.0 - uv.y)).rgb;
    vec4 L = texture(uLightMap, uv);
    float vein = L.r, gem = L.g, dist = L.b;
    float hv = 0.0, hg = 0.0;
    if (uHot < 0.5) {
        for (int i = 0; i < 8; i++) {
            float an = float(i) * 0.7853982;
            vec2 o1 = vec2(cos(an), sin(an));
            vec4 a1 = texture(uLightMap, (sp + o1 * 4.0) / uCanvas);
            vec4 a2 = texture(uLightMap, (sp + o1 * 10.0) / uCanvas);
            hv += a1.r * 0.6 + a2.r * 0.4;
            hg += a1.g * 0.5 + a2.g * 0.5;
        }
        hv /= 8.0; hg /= 8.0;
    }
    vec3 am = vec3(0.72, 0.40, 1.0);
    col = mix(col, col * vec3(0.62, 0.55, 0.72), vein * uLight.z);
    float fd = (dist - uPhase) * 8.0;
    float f = uLight.w * exp(-fd * fd) * step(0.02, vein);
    float vi = vein * uLight.x + f * 1.4;
    col += am * (vi * 0.5 + hv * (uLight.x + f) * 0.45 + gem * uLight.y * 0.55 + hg * uLight.y * 0.7);
    float e = min(min(p.x, uCanvas.x - p.x), min(p.y, uCanvas.y - p.y));
    float a = smoothstep(0.0, 70.0, e);
    o = vec4(min(col, vec3(1.0)) * a, a);
}"""
    }
}
