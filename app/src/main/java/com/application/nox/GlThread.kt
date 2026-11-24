package com.application.nox

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.PowerManager
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Fil de rendu OpenGL ES 3 commun aux avatars (3D et portrait) : contexte EGL sur une SurfaceTexture,
 * boucle à 30 ou 60 images/s, 15 en cas de chauffe, pause quand la vue est cachée. Les sous-classes
 * préparent leurs ressources dans [setup] et dessinent une image dans [frame].
 */
abstract class GlThread(
    private val st: SurfaceTexture,
    name: String,
    private val power: PowerManager?,
    private val fps: Int,
    private val onError: (Throwable) -> Unit,
) : Thread(name) {

    @Volatile var running = true
    @Volatile var paused = false
    @Volatile var w = 0
    @Volatile var h = 0

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** Prépare shaders, tampons et textures (contexte GL courant). */
    protected abstract fun setup()

    /** Dessine une image ; [hot] = le téléphone chauffe, alléger. */
    protected abstract fun frame(dt: Float, hot: Boolean)

    /** Libère ce qui n'est pas détruit avec le contexte (rien par défaut). */
    protected open fun cleanup() {}

    override fun run() {
        try {
            egl()
            setup()
            var last = SystemClock.uptimeMillis()
            var hot = false
            var hotCheck = 0L
            while (running) {
                if (paused) { sleep(120); last = SystemClock.uptimeMillis(); continue }
                val now = SystemClock.uptimeMillis()
                if (now - hotCheck > 5000) { hotCheck = now; hot = (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE }
                val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.1f)
                last = now
                frame(dt, hot)
                EGL14.eglSwapBuffers(display, surface)
                val spent = SystemClock.uptimeMillis() - now
                val budget = if (hot) 66L else 1000L / fps
                if (spent < budget) sleep(budget - spent)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            onError(t)
        } finally {
            try { cleanup() } catch (_: Throwable) {}
            release()
        }
    }

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

    // ------------------------------------------------------------ aides GL partagées

    protected fun program(vs: String, fs: String): Int {
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

    protected fun uMat(p: Int, n: String, m: FloatArray) = GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, n), 1, false, m, 0)
    protected fun u1(p: Int, n: String, v: Float) = GLES30.glUniform1f(GLES30.glGetUniformLocation(p, n), v)
    protected fun u1i(p: Int, n: String, v: Int) = GLES30.glUniform1i(GLES30.glGetUniformLocation(p, n), v)
    protected fun u2(p: Int, n: String, a: Float, b: Float) = GLES30.glUniform2f(GLES30.glGetUniformLocation(p, n), a, b)
    protected fun u3(p: Int, n: String, a: Float, b: Float, c: Float) = GLES30.glUniform3f(GLES30.glGetUniformLocation(p, n), a, b, c)
    protected fun u4(p: Int, n: String, a: Float, b: Float, c: Float, d: Float) = GLES30.glUniform4f(GLES30.glGetUniformLocation(p, n), a, b, c, d)

    protected fun attrib(buffer: Int, loc: Int, size: Int = 3) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, size * 4, 0)
    }

    protected fun floats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    protected fun floats(a: FloatArray): FloatBuffer = floats(a.size).apply { put(a); flip() }
    protected fun shorts(a: ShortArray): ShortBuffer = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); flip() }

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
