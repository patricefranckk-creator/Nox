package com.application.nox

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * L'avatar 3D de Nox, en hologramme (OpenGL ES 3) : un vrai visage en relief (maillage MediaPipe déformé
 * par [FaceMesh]), un crâne et des oreilles en volume, un cou et un buste reliés, des yeux en globes
 * lumineux qui suivent le regard, et des particules qui montent au repos, tournent autour de la tête
 * quand il réfléchit et pulsent quand il parle. Surfaces éclairées par les bords, maillage lumineux,
 * balayage. Son propre fil de rendu : 30 images/s, 15 en cas de chauffe, arrêt quand il est caché.
 * Si OpenGL ES 3 manque, [onFail] est appelé et la page vocale repasse au visage 2,5D.
 */
class Avatar3DView(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {

    /** Appelé (sur le fil principal) si le rendu 3D est impossible sur cet appareil. */
    var onFail: (() -> Unit)? = null

    private var renderer: Renderer? = null
    private var texture: SurfaceTexture? = null
    private var tw = 0
    private var th = 0
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val power = ctx.getSystemService(PowerManager::class.java)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        texture = st; tw = w; th = h
        startRenderer()
    }

    /** (Re)lance le rendu : à la création de la surface, ou quand l'avatar revient à l'écran après un échec. */
    private fun startRenderer() {
        val st = texture ?: return
        if (renderer?.isAlive == true) return
        renderer = Renderer(st, FaceMesh(context)).also { it.w = tw; it.h = th; it.paused = !isShown; it.start() }
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

    // ================================================================== volumes fixes

    /** Une surface fixe (crâne, oreille, buste, globe oculaire) : positions, normales, triangles, arêtes à tracer. */
    private class Solid(val pos: FloatArray, val nor: FloatArray, val tris: ShortArray, val edges: ShortArray) {
        val vbo = IntArray(4)
    }

    /** Ellipsoïde en latitude-longitude ; [keep] choisit les points gardés (un triangle est gardé si ses 3 points le sont). */
    private fun ellipsoid(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, lat: Int, lon: Int,
                          edgeEvery: Int, keep: (Float, Float, Float) -> Boolean): Solid {
        val pos = ArrayList<Float>(); val nor = ArrayList<Float>(); val ok = ArrayList<Boolean>()
        for (i in 0..lat) {
            val th = (PI * i / lat).toFloat()
            for (j in 0..lon) {
                val ph = (2 * PI * j / lon).toFloat()
                val nx = sin(th) * cos(ph); val ny = cos(th); val nz = sin(th) * sin(ph)
                val x = cx + rx * nx; val y = cy + ry * ny; val z = cz + rz * nz
                pos += x; pos += y; pos += z
                val gx = nx / rx; val gy = ny / ry; val gz = nz / rz
                val l = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6f)
                nor += gx / l; nor += gy / l; nor += gz / l
                ok += keep(x, y, z)
            }
        }
        val tris = ArrayList<Short>(); val edges = ArrayList<Short>()
        fun id(i: Int, j: Int) = i * (lon + 1) + j
        for (i in 0 until lat) for (j in 0 until lon) {
            val a = id(i, j); val b = id(i + 1, j); val c = id(i + 1, j + 1); val d = id(i, j + 1)
            if (ok[a] && ok[b] && ok[c]) { tris += a.toShort(); tris += b.toShort(); tris += c.toShort() }
            if (ok[a] && ok[c] && ok[d]) { tris += a.toShort(); tris += c.toShort(); tris += d.toShort() }
            if (i % edgeEvery == 0 && ok[a] && ok[d]) { edges += a.toShort(); edges += d.toShort() }
            if (j % edgeEvery == 0 && ok[a] && ok[b]) { edges += a.toShort(); edges += b.toShort() }
        }
        return Solid(pos.toFloatArray(), nor.toFloatArray(), tris.toShortArray(), edges.toShortArray())
    }

    /** Cou et buste : anneaux elliptiques (y, demi-largeur, demi-profondeur, profondeur du centre) reliés. */
    private fun bust(): Solid {
        val rings = listOf(
            floatArrayOf(-6.5f, 4.0f, 3.3f, 0.2f), floatArrayOf(-10f, 4.1f, 3.4f, 0.2f), floatArrayOf(-13.2f, 4.5f, 3.7f, 0.1f),
            floatArrayOf(-14.6f, 7.5f, 4.6f, -0.2f), floatArrayOf(-15.6f, 12f, 5.4f, -0.5f), floatArrayOf(-17f, 15.2f, 5.9f, -0.8f),
            floatArrayOf(-19.5f, 17f, 6.2f, -0.9f), floatArrayOf(-24f, 18f, 6.4f, -1f),
        )
        val seg = 44
        val pos = ArrayList<Float>(); val nor = ArrayList<Float>()
        for (r in rings) for (j in 0..seg) {
            val a = (2 * PI * j / seg).toFloat()
            pos += r[1] * cos(a); pos += r[0]; pos += r[3] + r[2] * sin(a)
            val gx = cos(a) / r[1]; val gz = sin(a) / r[2]
            val l = sqrt(gx * gx + gz * gz)
            nor += gx / l; nor += 0f; nor += gz / l
        }
        val tris = ArrayList<Short>(); val edges = ArrayList<Short>()
        fun id(i: Int, j: Int) = (i * (seg + 1) + j).toShort()
        for (i in 0 until rings.size - 1) for (j in 0 until seg) {
            tris += id(i, j); tris += id(i + 1, j); tris += id(i + 1, j + 1)
            tris += id(i, j); tris += id(i + 1, j + 1); tris += id(i, j + 1)
            edges += id(i, j); edges += id(i, j + 1)
            if (j % 4 == 0) { edges += id(i, j); edges += id(i + 1, j) }
        }
        return Solid(pos.toFloatArray(), nor.toFloatArray(), tris.toShortArray(), edges.toShortArray())
    }

    // ================================================================== rendu

    private inner class Renderer(private val st: SurfaceTexture, private val mesh: FaceMesh) : Thread("nox-avatar-3d") {
        @Volatile var running = true
        @Volatile var paused = false
        @Volatile var w = 0
        @Volatile var h = 0

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var surfProg = 0
        private var lineProg = 0
        private val vbo = IntArray(7)
        private lateinit var posBuf: FloatBuffer
        private lateinit var norBuf: FloatBuffer
        private lateinit var partBuf: FloatBuffer
        private var bodyLines = 0
        private val circleN = 32

        // crâne (derrière et au-dessus du masque), oreilles, buste, globes oculaires
        private val skull = ellipsoid(0f, 0.4f, -0.6f, 7.95f, 9.95f, 7.6f, 26, 40, 2) { _, y, z -> z < 2.2f || y > 7.6f }
        private val earL = ellipsoid(-7.75f, 1.2f, -0.4f, 0.9f, 2.5f, 1.7f, 10, 16, 3) { x, _, _ -> x < -7.3f }
        private val earR = ellipsoid(7.75f, 1.2f, -0.4f, 0.9f, 2.5f, 1.7f, 10, 16, 3) { x, _, _ -> x > 7.3f }
        private val torso = bust()
        private val eyeball = ellipsoid(0f, 0f, 0f, 1f, 1f, 1f, 12, 18, 3) { _, _, _ -> true }
        private val solids = listOf(skull, earL, earR, torso, eyeball)

        // particules : x, y, z, vitesse, phase
        private val parts = 72
        private val pdata = FloatArray(parts * 5)
        private val ppos = FloatArray(parts * 3)

        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val body = FloatArray(16)
        private val head = FloatArray(16)
        private val mvHead = FloatArray(16)
        private val mvpHead = FloatArray(16)
        private val mvBody = FloatArray(16)
        private val mvpBody = FloatArray(16)
        private val eyeM = FloatArray(16)
        private val mvEye = FloatArray(16)
        private val mvpEye = FloatArray(16)

        override fun run() {
            try {
                egl()
                setup()
                NoxLog.i("AVATAR", "Avatar 3D prêt (OpenGL ES ${GLES30.glGetString(GLES30.GL_VERSION)})")
                var last = SystemClock.uptimeMillis()
                var hot = false
                var hotCheck = 0L
                while (running) {
                    if (paused) { sleep(120); last = SystemClock.uptimeMillis(); continue }
                    val now = SystemClock.uptimeMillis()
                    if (now - hotCheck > 5000) { hotCheck = now; hot = (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE }
                    val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.1f)
                    last = now
                    FaceBrain.step(dt)
                    synchronized(FaceBrain) { mesh.update(FaceBrain) }
                    particles(dt)
                    draw(hot)
                    EGL14.eglSwapBuffers(display, surface)
                    val spent = SystemClock.uptimeMillis() - now
                    val frame = if (hot) 66L else 33L
                    if (spent < frame) sleep(frame - spent)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                NoxLog.e("AVATAR", "Avatar 3D impossible sur cet appareil, retour au visage 2,5D", t)
                main.post { onFail?.invoke() }
            } finally {
                release()
            }
        }

        // ------------------------------------------------------------ EGL

        private fun egl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "pas de configuration OpenGL ES 3" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "contexte OpenGL ES 3 refusé" }
            surface = EGL14.eglCreateWindowSurface(display, configs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "surface refusée" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        }

        private fun release() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }

        // ------------------------------------------------------------ préparation

        private fun setup() {
            surfProg = program(SURF_VS, SURF_FS)
            lineProg = program(LINE_VS, LINE_FS)
            GLES30.glGenBuffers(vbo.size, vbo, 0)
            posBuf = floats(mesh.count * 3)
            norBuf = floats(mesh.count * 3)
            partBuf = floats(parts * 3)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.count * 12, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.count * 12, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[2])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.triangles.size * 2, shorts(mesh.triangles), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[3])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.edges.size * 2, shorts(mesh.edges), GLES30.GL_STATIC_DRAW)

            val lines = bodyCircuits()
            bodyLines = lines.size / 3
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[4])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lines.size * 4, floats(lines), GLES30.GL_STATIC_DRAW)

            val circle = FloatArray((circleN + 2) * 3)
            for (i in 0..circleN) {
                val a = (i * 2 * PI / circleN).toFloat()
                circle[(i + 1) * 3] = cos(a); circle[(i + 1) * 3 + 1] = sin(a)
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[5])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, circle.size * 4, floats(circle), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[6])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, parts * 12, null, GLES30.GL_DYNAMIC_DRAW)

            for (s in solids) {
                GLES30.glGenBuffers(4, s.vbo, 0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vbo[0])
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, s.pos.size * 4, floats(s.pos), GLES30.GL_STATIC_DRAW)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vbo[1])
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, s.nor.size * 4, floats(s.nor), GLES30.GL_STATIC_DRAW)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.vbo[2])
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.tris.size * 2, shorts(s.tris), GLES30.GL_STATIC_DRAW)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.vbo[3])
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.edges.size * 2, shorts(s.edges), GLES30.GL_STATIC_DRAW)
            }

            for (i in 0 until parts) respawn(i, Math.random().toFloat())
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnable(GLES30.GL_BLEND)
        }

        /** Circuits lumineux sur le buste : clavicules et lignes de données. */
        private fun bodyCircuits(): FloatArray {
            val l = ArrayList<Float>()
            fun seg(vararg p: Float) { p.forEach { l += it } }
            for (sx in floatArrayOf(-1f, 1f)) {
                seg(sx * 1.2f, -15.4f, 3.2f, sx * 6f, -15.2f, 3.6f)
                seg(sx * 6f, -15.2f, 3.6f, sx * 11f, -16.1f, 3.4f)
                seg(sx * 2.4f, -17.5f, 5.0f, sx * 2.4f, -21.5f, 5.3f)
                seg(sx * 2.4f, -21.5f, 5.3f, sx * 5.5f, -23f, 5.3f)
                seg(sx * 8.5f, -18f, 4.8f, sx * 8.5f, -22f, 5.0f)
            }
            return l.toFloatArray()
        }

        // ------------------------------------------------------------ particules

        private fun respawn(i: Int, life: Float) {
            val a = (Math.random() * 2 * PI).toFloat()
            val r = 9f + (Math.random() * 9).toFloat()
            pdata[i * 5] = cos(a) * r
            pdata[i * 5 + 1] = -22f + life * 38f
            pdata[i * 5 + 2] = sin(a) * r * 0.5f - 2f
            pdata[i * 5 + 3] = 0.8f + (Math.random() * 1.6).toFloat()
            pdata[i * 5 + 4] = (Math.random() * 2 * PI).toFloat()
        }

        private fun particles(dt: Float) {
            val b = FaceBrain
            val thinking = b.state == NeuronView.State.THINKING
            for (i in 0 until parts) {
                val k = i * 5
                if (thinking) {
                    pdata[k + 4] += dt * (0.9f + pdata[k + 3] * 0.3f)
                    val r = 10.5f + (i % 3) * 1.2f
                    ppos[i * 3] = cos(pdata[k + 4]) * r
                    ppos[i * 3 + 1] = 9.5f + sin(pdata[k + 4] * 2f + i) * 0.8f + (i % 4) * 0.6f
                    ppos[i * 3 + 2] = sin(pdata[k + 4]) * r * 0.45f
                } else {
                    pdata[k + 1] += dt * pdata[k + 3] * (if (b.state == NeuronView.State.SPEAKING) 2.2f else 1f)
                    if (pdata[k + 1] > 16f) respawn(i, 0f)
                    ppos[i * 3] = pdata[k] + sin(b.t * 0.8f + pdata[k + 4]) * 0.6f
                    ppos[i * 3 + 1] = pdata[k + 1]
                    ppos[i * 3 + 2] = pdata[k + 2]
                }
            }
        }

        // ------------------------------------------------------------ image

        private fun draw(hot: Boolean) {
            val b = FaceBrain
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (w == 0 || h == 0) return

            val sleeping = b.state == NeuronView.State.OFF
            val alpha = if (sleeping) 0.55f else 1f
            Matrix.perspectiveM(proj, 0, 32f, w.toFloat() / h, 10f, 200f)
            Matrix.setLookAtM(view, 0, 0f, -4f, 66f, 0f, -5f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(body, 0)
            Matrix.translateM(body, 0, 0f, b.breath * 0.07f, 0f)
            Matrix.scaleM(body, 0, 1f + b.breath * 0.002f, 1f, 1f + b.breath * 0.004f)
            System.arraycopy(body, 0, head, 0, 16)
            Matrix.translateM(head, 0, 0f, -7f + b.nod * 0.05f, 0f)
            Matrix.rotateM(head, 0, b.gx * 1.8f + sin(b.t * 0.45f) * 2.5f, 0f, 1f, 0f)
            Matrix.rotateM(head, 0, -b.gy * 1.2f + b.nod * 1.6f, 1f, 0f, 0f)
            Matrix.rotateM(head, 0, -(b.cur.tilt + b.sway), 0f, 0f, 1f)
            Matrix.translateM(head, 0, 0f, 7f, 0f)
            Matrix.multiplyMM(mvHead, 0, view, 0, head, 0)
            Matrix.multiplyMM(mvpHead, 0, proj, 0, mvHead, 0)
            Matrix.multiplyMM(mvBody, 0, view, 0, body, 0)
            Matrix.multiplyMM(mvpBody, 0, proj, 0, mvBody, 0)

            posBuf.clear(); posBuf.put(mesh.pos).flip()
            norBuf.clear(); norBuf.put(mesh.nor).flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, mesh.count * 12, posBuf)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1])
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, mesh.count * 12, norBuf)

            val scan = 11f - ((b.t / 6f) % 1f) * 36f
            val speakGlow = if (b.state == NeuronView.State.SPEAKING) b.mouthOpen * 0.25f else 0f
            val listenGlow = if (b.state == NeuronView.State.LISTENING) b.levelS * 0.3f else 0f
            val glow = (1f + speakGlow + listenGlow) * alpha

            // intérieur sombre de la bouche (mélange normal)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            flat(mvpHead, 0f, -4.45f - b.mouthOpen * 0.7f, 4.1f, 2.1f, 0.25f + b.mouthOpen * 0.85f, 0.01f, 0.03f, 0.07f, 0.9f * alpha)

            // tout le reste en lumière (mélange additif)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)

            // buste et cou
            solid(torso, mvpBody, mvBody, 0.24f, 0.86f, 0.59f, 0.75f * alpha, scan, 0.20f * alpha)
            lines(mvpBody, 0.24f, 0.86f, 0.59f, 0.5f * alpha, scan)
            attrib(vbo[4], 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, bodyLines)

            // crâne et oreilles
            solid(skull, mvpHead, mvHead, 0.31f, 0.65f, 1f, 0.85f * glow, scan, 0.13f * alpha)
            solid(earL, mvpHead, mvHead, 0.31f, 0.65f, 1f, 0.8f * glow, scan, 0.16f * alpha)
            solid(earR, mvpHead, mvHead, 0.31f, 0.65f, 1f, 0.8f * glow, scan, 0.16f * alpha)

            // globes oculaires, derrière les paupières
            for (e in 0..1) {
                System.arraycopy(head, 0, eyeM, 0, 16)
                Matrix.translateM(eyeM, 0, FaceMesh.EYES[e * 3], FaceMesh.EYES[e * 3 + 1], 2.55f)
                Matrix.rotateM(eyeM, 0, b.gx * 2.5f, 0f, 1f, 0f)
                Matrix.rotateM(eyeM, 0, -b.gy * 2.5f, 1f, 0f, 0f)
                Matrix.scaleM(eyeM, 0, 1.25f, 1.25f, 1.25f)
                Matrix.multiplyMM(mvEye, 0, view, 0, eyeM, 0)
                Matrix.multiplyMM(mvpEye, 0, proj, 0, mvEye, 0)
                solid(eyeball, mvpEye, mvEye, 0.5f, 0.85f, 1f, 0.9f * alpha, -1000f, 0f)
            }

            // visage : surface, maillage, points
            GLES30.glUseProgram(surfProg)
            uMat(surfProg, "uMvp", mvpHead); uMat(surfProg, "uMv", mvHead)
            u3(surfProg, "uColor", 0.31f, 0.65f, 1f)
            u1(surfProg, "uAlpha", glow)
            u1(surfProg, "uScan", scan)
            attrib(vbo[0], 0); attrib(vbo[1], 1)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[2])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.triangles.size, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glDisableVertexAttribArray(1)

            lines(mvpHead, 0.30f, 0.62f, 1f, (0.26f + speakGlow) * alpha, scan)
            attrib(vbo[0], 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[3])
            GLES30.glDrawElements(GLES30.GL_LINES, mesh.edges.size, GLES30.GL_UNSIGNED_SHORT, 0)

            if (!hot) {
                val pulse = 0.3f + 0.25f * (0.5f + 0.5f * sin(b.t * 2.2f))
                lines(mvpHead, 0.24f, 0.86f, 0.59f, pulse * alpha, scan, point = 2.0f * density)
                attrib(vbo[0], 0)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, mesh.count)

                partBuf.clear(); partBuf.put(ppos).flip()
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[6])
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, parts * 12, partBuf)
                val pa = when (b.state) { NeuronView.State.THINKING -> 0.9f; NeuronView.State.SPEAKING -> 0.45f + b.mouthOpen * 0.5f; else -> 0.35f }
                lines(mvpBody, 0.5f, 0.85f, 1f, pa * alpha, -1000f, point = 3.2f * density)
                attrib(vbo[6], 0)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, parts)
            }

            // iris, pupille et lueur des yeux, guidés par le regard
            if (b.blinkK * (b.cur.eye + 0.28f) > 0.4f) {
                val (r, g, bl) = when (b.emotion) {
                    Emotion.JOIE -> Triple(0.24f, 0.86f, 0.59f)
                    Emotion.EMPATHIE, Emotion.SURPRISE -> Triple(0.5f, 0.85f, 1f)
                    else -> Triple(0.31f, 0.65f, 1f)
                }
                for (e in 0..1) {
                    val ex = FaceMesh.EYES[e * 3] + b.gx * 0.08f
                    val ey = FaceMesh.EYES[e * 3 + 1] + b.gy * 0.06f
                    flat(mvpHead, ex, ey, 3.7f, 0.62f, 0.62f, r, g, bl, 0.9f * alpha)
                    flat(mvpHead, ex, ey, 3.75f, 0.26f, 0.26f, 0.85f, 0.97f, 1f, alpha)
                    if (!hot) {
                        lines(mvpHead, r, g, bl, 0.35f * alpha, -1000f, point = 26f * density, ox = ex, oy = ey, oz = 3.8f)
                        attrib(vbo[5], 0)
                        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, 1)
                    }
                }
            }
        }

        private fun solid(s: Solid, mvp: FloatArray, mv: FloatArray, r: Float, g: Float, b: Float, a: Float, scan: Float, edgeA: Float) {
            GLES30.glUseProgram(surfProg)
            uMat(surfProg, "uMvp", mvp); uMat(surfProg, "uMv", mv)
            u3(surfProg, "uColor", r, g, b)
            u1(surfProg, "uAlpha", a)
            u1(surfProg, "uScan", scan)
            attrib(s.vbo[0], 0); attrib(s.vbo[1], 1)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.vbo[2])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, s.tris.size, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glDisableVertexAttribArray(1)
            if (edgeA > 0f && s.edges.isNotEmpty()) {
                lines(mvp, r, g, b, edgeA, scan)
                attrib(s.vbo[0], 0)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.vbo[3])
                GLES30.glDrawElements(GLES30.GL_LINES, s.edges.size, GLES30.GL_UNSIGNED_SHORT, 0)
            }
        }

        private fun flat(mvp: FloatArray, x: Float, y: Float, z: Float, sx: Float, sy: Float, r: Float, g: Float, b: Float, a: Float) {
            lines(mvp, r, g, b, a, -1000f, ox = x, oy = y, oz = z, sx = sx, sy = sy)
            attrib(vbo[5], 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, circleN + 2)
        }

        private fun lines(mvp: FloatArray, r: Float, g: Float, b: Float, a: Float, scan: Float, point: Float = 0f,
                          ox: Float = 0f, oy: Float = 0f, oz: Float = 0f, sx: Float = 1f, sy: Float = 1f) {
            GLES30.glUseProgram(lineProg)
            uMat(lineProg, "uMvp", mvp)
            u3(lineProg, "uColor", r, g, b)
            u1(lineProg, "uAlpha", a)
            u1(lineProg, "uScan", scan)
            u3(lineProg, "uOffset", ox, oy, oz)
            u3(lineProg, "uScale", sx, sy, 1f)
            u1(lineProg, "uPoint", point)
        }

        // ------------------------------------------------------------ aides GL

        private fun attrib(buffer: Int, loc: Int) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, 3, GLES30.GL_FLOAT, false, 12, 0)
        }

        private fun uMat(p: Int, n: String, m: FloatArray) = GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, n), 1, false, m, 0)
        private fun u1(p: Int, n: String, v: Float) = GLES30.glUniform1f(GLES30.glGetUniformLocation(p, n), v)
        private fun u3(p: Int, n: String, a: Float, b: Float, c: Float) = GLES30.glUniform3f(GLES30.glGetUniformLocation(p, n), a, b, c)

        private fun program(vs: String, fs: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src)
                GLES30.glCompileShader(s)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader : " + GLES30.glGetShaderInfoLog(s) }
                return s
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "programme : " + GLES30.glGetProgramInfoLog(p) }
            return p
        }

        private fun floats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private fun floats(a: FloatArray): FloatBuffer = floats(a.size).apply { put(a); flip() }
        private fun shorts(a: ShortArray): ShortBuffer = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); flip() }
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x40

        // Les deux étapes déclarent leurs nombres en haute précision : une uniforme partagée entre elles
        // doit avoir la même précision, sinon certains GPU refusent la liaison.
        private const val SURF_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNor;
uniform mat4 uMvp;
uniform mat4 uMv;
out vec3 vN;
out float vY;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vN = mat3(uMv) * aNor;
    vY = aPos.y;
}"""

        private const val SURF_FS = """#version 300 es
precision highp float;
in vec3 vN;
in float vY;
uniform vec3 uColor;
uniform float uAlpha;
uniform float uScan;
out vec4 o;
void main() {
    vec3 n = normalize(vN);
    float rim = 1.0 - abs(n.z);
    float key = max(dot(n, normalize(vec3(-0.4, 0.6, 0.7))), 0.0);
    float a = (0.03 + pow(rim, 2.2) * 0.55 + key * 0.05) * uAlpha;
    float s = exp(-(vY - uScan) * (vY - uScan) * 1.2) * 0.2 * uAlpha;
    vec3 c = uColor * (a + s) + vec3(0.6, 0.9, 1.0) * s * 0.4;
    o = vec4(c, clamp(a + s, 0.0, 1.0));
}"""

        private const val LINE_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
uniform vec3 uOffset;
uniform vec3 uScale;
uniform float uPoint;
out float vY;
void main() {
    vec3 p = aPos * uScale + uOffset;
    gl_Position = uMvp * vec4(p, 1.0);
    gl_PointSize = max(uPoint, 1.0);
    vY = p.y;
}"""

        private const val LINE_FS = """#version 300 es
precision highp float;
in float vY;
uniform vec3 uColor;
uniform float uAlpha;
uniform float uScan;
uniform float uPoint;
out vec4 o;
void main() {
    float a = uAlpha;
    if (uPoint > 0.0) {
        float d = length(gl_PointCoord - vec2(0.5));
        if (d > 0.5) discard;
        a *= pow(1.0 - d * 2.0, 1.6);
    }
    float s = exp(-(vY - uScan) * (vY - uScan) * 1.2) * 0.8;
    a = clamp(a * (1.0 + s), 0.0, 1.0);
    o = vec4(uColor * a, a);
}"""
    }
}
