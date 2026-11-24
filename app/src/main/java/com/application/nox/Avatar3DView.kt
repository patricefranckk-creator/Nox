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
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * L'avatar 3D de Nox : un vrai visage en relief (maillage MediaPipe déformé par [FaceMesh]) rendu en
 * hologramme avec OpenGL ES 3 : surface translucide éclairée par les bords, maillage lumineux, points
 * qui scintillent, balayage, crâne, cou et épaules en fil de lumière. La tête tourne et s'incline
 * vraiment. Son propre fil de rendu, 30 images/s, 15 en cas de chauffe, arrêt quand il est caché.
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
        private val vbo = IntArray(6)
        private lateinit var posBuf: FloatBuffer
        private lateinit var norBuf: FloatBuffer
        private var headLines = 0
        private var bodyLines = 0
        private val circleN = 32

        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val body = FloatArray(16)
        private val head = FloatArray(16)
        private val tmp = FloatArray(16)
        private val mvHead = FloatArray(16)
        private val mvpHead = FloatArray(16)
        private val mvpBody = FloatArray(16)

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
            GLES30.glGenBuffers(6, vbo, 0)
            posBuf = floats(mesh.count * 3)
            norBuf = floats(mesh.count * 3)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.count * 12, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.count * 12, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[2])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.triangles.size * 2, shorts(mesh.triangles), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[3])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.edges.size * 2, shorts(mesh.edges), GLES30.GL_STATIC_DRAW)

            val (hl, bl) = staticLines()
            headLines = hl.size / 3
            bodyLines = bl.size / 3
            val all = hl + bl
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[4])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, all.size * 4, floats(all), GLES30.GL_STATIC_DRAW)

            val circle = FloatArray((circleN + 2) * 3)
            for (i in 0..circleN) {
                val a = (i * 2 * PI / circleN).toFloat()
                circle[(i + 1) * 3] = cos(a); circle[(i + 1) * 3 + 1] = sin(a)
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[5])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, circle.size * 4, floats(circle), GLES30.GL_STATIC_DRAW)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnable(GLES30.GL_BLEND)
        }

        /** Crâne (suit la tête), cou et épaules (suivent le corps), en segments de lumière. */
        private fun staticLines(): Pair<FloatArray, FloatArray> {
            val headL = ArrayList<Float>()
            val bodyL = ArrayList<Float>()
            fun seg(l: ArrayList<Float>, a: FloatArray, b: FloatArray) { a.forEach { l += it }; b.forEach { l += it } }
            val rx = 7.9f; val ry = 10.2f; val rz = 7.4f; val cy = 0.2f; val cz = 0.6f
            for (y in floatArrayOf(-5.5f, -2.5f, 0.5f, 3.5f, 6.5f, 8.8f)) {
                val s = sqrt(max(0f, 1f - ((y - cy) / ry) * ((y - cy) / ry)))
                var prev: FloatArray? = null
                for (k in 0..24) {
                    val a = (PI + k * PI / 24).toFloat()
                    val p = floatArrayOf(rx * s * cos(a), y, cz + rz * s * sin(a))
                    prev?.let { seg(headL, it, p) }
                    prev = p
                }
            }
            for (phi in floatArrayOf(200f, 235f, 270f, 305f, 340f)) {
                val a = Math.toRadians(phi.toDouble()).toFloat()
                var prev: FloatArray? = null
                for (k in 0..30) {
                    val t = (-PI / 2 * 0.82 + k * PI * 0.9 / 30).toFloat()
                    val p = floatArrayOf(rx * cos(t) * cos(a), cy + ry * sin(t), cz + rz * cos(t) * sin(a))
                    prev?.let { seg(headL, it, p) }
                    prev = p
                }
            }
            for (sx in floatArrayOf(-1f, 1f)) {
                seg(bodyL, floatArrayOf(sx * 5.0f, -7.5f, 1.2f), floatArrayOf(sx * 4.4f, -13.5f, 0.6f))
                val pts = listOf(floatArrayOf(sx * 4.4f, -13.5f, 0.6f), floatArrayOf(sx * 9.5f, -15.4f, 0f), floatArrayOf(sx * 15.5f, -17.2f, -1f), floatArrayOf(sx * 19f, -21f, -1.5f))
                for (i in 0 until pts.size - 1) seg(bodyL, pts[i], pts[i + 1])
                val low = pts.map { floatArrayOf(it[0] * 0.94f, it[1] - 1.8f, it[2] + 0.6f) }
                for (i in 0 until low.size - 1) seg(bodyL, low[i], low[i + 1])
                seg(bodyL, floatArrayOf(sx * 1.2f, -15.6f, 3f), floatArrayOf(sx * 8.5f, -15.9f, 1.8f))
            }
            for (y in floatArrayOf(-9.8f, -12.6f)) {
                var prev: FloatArray? = null
                for (k in 0..32) {
                    val a = (k * 2 * PI / 32).toFloat()
                    val p = floatArrayOf(4.5f * cos(a), y, 0.8f + 3.6f * sin(a))
                    prev?.let { seg(bodyL, it, p) }
                    prev = p
                }
            }
            return headL.toFloatArray() to bodyL.toFloatArray()
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
            Matrix.perspectiveM(proj, 0, 30f, w.toFloat() / h, 10f, 200f)
            Matrix.setLookAtM(view, 0, 0f, -4.5f, 66f, 0f, -4.5f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(body, 0)
            Matrix.translateM(body, 0, 0f, b.breath * 0.07f, 0f)
            System.arraycopy(body, 0, head, 0, 16)
            Matrix.translateM(head, 0, 0f, -7f + b.nod * 0.05f, 0f)
            Matrix.rotateM(head, 0, b.gx * 1.8f + sin(b.t * 0.45f) * 2.5f, 0f, 1f, 0f)
            Matrix.rotateM(head, 0, -b.gy * 1.2f + b.nod * 1.6f, 1f, 0f, 0f)
            Matrix.rotateM(head, 0, -(b.cur.tilt + b.sway), 0f, 0f, 1f)
            Matrix.translateM(head, 0, 0f, 7f, 0f)
            Matrix.multiplyMM(mvHead, 0, view, 0, head, 0)
            Matrix.multiplyMM(mvpHead, 0, proj, 0, mvHead, 0)
            Matrix.multiplyMM(tmp, 0, view, 0, body, 0)
            Matrix.multiplyMM(mvpBody, 0, proj, 0, tmp, 0)

            posBuf.clear(); posBuf.put(mesh.pos).flip()
            norBuf.clear(); norBuf.put(mesh.nor).flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, mesh.count * 12, posBuf)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1])
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, mesh.count * 12, norBuf)

            val scan = 10f - ((b.t / 5.5f) % 1f) * 32f
            val speakGlow = if (b.state == NeuronView.State.SPEAKING) b.mouthOpen * 0.25f else 0f
            val listenGlow = if (b.state == NeuronView.State.LISTENING) b.levelS * 0.3f else 0f

            // fond des yeux et de la bouche (sombre, mélange normal)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            for (e in 0..1) circle(mvpHead, FaceMesh.EYES[e * 3], FaceMesh.EYES[e * 3 + 1], 3.0f, 1.35f, 1.1f, 0.02f, 0.05f, 0.1f, 0.92f * alpha, scan)
            circle(mvpHead, 0f, -4.45f - b.mouthOpen * 0.7f, 4.1f, 2.1f, 0.25f + b.mouthOpen * 0.85f, 0.01f, 0.03f, 0.07f, 0.9f * alpha, scan)

            // hologramme : surface, maillage, points (mélange additif)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glUseProgram(surfProg)
            uMat(surfProg, "uMvp", mvpHead); uMat(surfProg, "uMv", mvHead)
            u3(surfProg, "uColor", 0.31f, 0.65f, 1f)
            u1(surfProg, "uAlpha", (0.9f + speakGlow + listenGlow) * alpha)
            u1(surfProg, "uScan", scan)
            attrib(vbo[0], 0); attrib(vbo[1], 1)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[2])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.triangles.size, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glDisableVertexAttribArray(1)

            line(mvpHead, 0.30f, 0.62f, 1f, (0.32f + speakGlow) * alpha, scan, 0f, 0f, 0f, 1f)
            attrib(vbo[0], 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[3])
            GLES30.glDrawElements(GLES30.GL_LINES, mesh.edges.size, GLES30.GL_UNSIGNED_SHORT, 0)

            if (!hot) {
                val pulse = 0.35f + 0.25f * (0.5f + 0.5f * sin(b.t * 2.2f))
                line(mvpHead, 0.24f, 0.86f, 0.59f, pulse * alpha, scan, 0f, 0f, 0f, 1f, point = 2.2f * density)
                attrib(vbo[0], 0)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, mesh.count)
            }

            // crâne, cou, épaules
            line(mvpHead, 0.31f, 0.65f, 1f, 0.22f * alpha, scan, 0f, 0f, 0f, 1f)
            attrib(vbo[4], 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, headLines)
            line(mvpBody, 0.24f, 0.86f, 0.59f, 0.55f * alpha, scan, 0f, 0f, 0f, 1f)
            attrib(vbo[4], 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, headLines, bodyLines)

            // iris et pupilles, guidés par le regard
            if (b.blinkK * b.cur.eye > 0.35f) {
                val (r, g, bl) = when (b.emotion) {
                    Emotion.JOIE -> Triple(0.24f, 0.86f, 0.59f)
                    Emotion.EMPATHIE, Emotion.SURPRISE -> Triple(0.5f, 0.85f, 1f)
                    else -> Triple(0.31f, 0.65f, 1f)
                }
                for (e in 0..1) {
                    val ex = FaceMesh.EYES[e * 3] + b.gx * 0.07f
                    val ey = FaceMesh.EYES[e * 3 + 1] + b.gy * 0.05f
                    circle(mvpHead, ex, ey, 3.55f, 0.5f, 0.5f, r, g, bl, 0.95f * alpha, scan)
                    circle(mvpHead, ex, ey, 3.6f, 0.2f, 0.2f, 0.9f, 0.97f, 1f, alpha, scan)
                }
            }
        }

        private fun circle(mvp: FloatArray, x: Float, y: Float, z: Float, sx: Float, sy: Float, r: Float, g: Float, b: Float, a: Float, scan: Float) {
            line(mvp, r, g, b, a, scan, x, y, z, 1f, sx = sx, sy = sy, flat = true)
            attrib(vbo[5], 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, circleN + 2)
        }

        private fun line(mvp: FloatArray, r: Float, g: Float, b: Float, a: Float, scan: Float,
                         ox: Float, oy: Float, oz: Float, scale: Float, point: Float = 0f, sx: Float = scale, sy: Float = scale, flat: Boolean = false) {
            GLES30.glUseProgram(lineProg)
            uMat(lineProg, "uMvp", mvp)
            u3(lineProg, "uColor", r, g, b)
            u1(lineProg, "uAlpha", a)
            u1(lineProg, "uScan", if (flat) -1000f else scan)
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

        private const val SURF_VS = """#version 300 es
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
precision mediump float;
in vec3 vN;
in float vY;
uniform vec3 uColor;
uniform float uAlpha;
uniform float uScan;
out vec4 o;
void main() {
    vec3 n = normalize(vN);
    float rim = 1.0 - abs(n.z);
    float a = (0.035 + pow(rim, 2.4) * 0.55) * uAlpha;
    float s = exp(-(vY - uScan) * (vY - uScan) * 1.2) * 0.22 * uAlpha;
    vec3 c = uColor * (a + s) + vec3(0.6, 0.9, 1.0) * s * 0.4;
    o = vec4(c, clamp(a + s, 0.0, 1.0));
}"""

        private const val LINE_VS = """#version 300 es
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
        a *= 1.0 - d * 1.6;
    }
    float s = exp(-(vY - uScan) * (vY - uScan) * 1.2) * 0.8;
    a = clamp(a * (1.0 + s), 0.0, 1.0);
    o = vec4(uColor * a, a);
}"""
    }
}
