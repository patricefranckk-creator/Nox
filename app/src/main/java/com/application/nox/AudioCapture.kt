package com.application.nox

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Micro brut pour l'écoute native de Gemma : 16 kHz mono PCM 16 bits, avec détection
 * automatique de la fin de parole (silence), suppression de bruit et limite de 30 s.
 * Le son ne quitte jamais le téléphone : il va directement dans le modèle.
 */
class AudioCapture(private val listener: Listener) {

    interface Listener {
        fun onCaptureStarted()
        fun onCaptureLevel(level: Float)
        /** wav = fichier WAV complet, durée en ms. */
        fun onCaptured(wav: ByteArray, durationMs: Long)
        fun onCaptureNothing()
        fun onCaptureError(msg: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var forceStop = false
    private var thread: Thread? = null

    val active get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        forceStop = false
        thread = Thread({ loop() }, "nox-mic").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** Arrêt manuel : ce qui a été dit est envoyé quand même. */
    fun finish() { forceStop = true }

    /** Annulation : rien n'est envoyé. */
    fun cancel() {
        running = false
        forceStop = true
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE))
        } catch (t: Throwable) {
            running = false
            main.post { listener.onCaptureError("Micro indisponible : ${t.message}") }
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); running = false
            main.post { listener.onCaptureError("Micro occupé par une autre appli") }
            return
        }
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
        val agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } else null

        val frame = ShortArray(FRAME)                 // 30 ms
        val pcm = ByteArrayOutputStream(RATE * 2 * 8)
        val preRoll = ArrayDeque<ByteArray>()          // 300 ms gardés avant le début de la parole
        var noise = 0.0
        var calib = 0
        var speaking = false
        var speechFrames = 0
        var silenceFrames = 0
        var totalFrames = 0
        val maxFrames = MAX_MS / FRAME_MS
        val waitFrames = WAIT_MS / FRAME_MS
        val endSilence = END_SILENCE_MS / FRAME_MS

        try {
            rec.startRecording()
            main.post { listener.onCaptureStarted() }
            while (running) {
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) continue
                totalFrames++
                var sum = 0.0
                for (i in 0 until n) { val v = frame[i].toDouble(); sum += v * v }
                val rms = sqrt(sum / n)
                val bytes = toBytes(frame, n)

                // Calibration du bruit ambiant sur les 10 premières trames
                if (calib < 10) { noise = (noise * calib + rms) / (calib + 1); calib++ }
                val threshold = max(noise * 2.8, 450.0)
                val voiced = rms > threshold
                main.post { listener.onCaptureLevel(((rms / (threshold * 4)).coerceIn(0.0, 1.0)).toFloat()) }

                if (!speaking) {
                    preRoll.addLast(bytes); if (preRoll.size > 10) preRoll.removeFirst()
                    if (voiced) speechFrames++ else speechFrames = 0
                    if (speechFrames >= 3) {              // 90 ms de voix = début de parole
                        speaking = true
                        preRoll.forEach { pcm.write(it) }; preRoll.clear()
                    } else {
                        // Bruit de fond lentement réappris tant que personne ne parle
                        if (!voiced) noise = noise * 0.95 + rms * 0.05
                        if (totalFrames > waitFrames || forceStop) break
                    }
                } else {
                    pcm.write(bytes)
                    silenceFrames = if (voiced) 0 else silenceFrames + 1
                    if (silenceFrames >= endSilence || forceStop || totalFrames > maxFrames) break
                }
            }
        } catch (t: Throwable) {
            main.post { listener.onCaptureError("Enregistrement interrompu : ${t.message}") }
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            ns?.release(); agc?.release(); rec.release()
        }

        val cancelled = !running
        running = false
        if (cancelled) return
        val data = pcm.toByteArray()
        val ms = data.size / 2 * 1000L / RATE
        main.post {
            if (!speaking || ms < 400) listener.onCaptureNothing()
            else listener.onCaptured(wav(data), ms)
        }
    }

    private fun toBytes(s: ShortArray, n: Int): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) { b[2 * i] = (s[i].toInt() and 0xff).toByte(); b[2 * i + 1] = (s[i].toInt() shr 8 and 0xff).toByte() }
        return b
    }

    companion object {
        const val RATE = 16_000
        private const val FRAME_MS = 30
        private const val FRAME = RATE * FRAME_MS / 1000
        private const val MAX_MS = 30_000
        private const val WAIT_MS = 7_000
        private const val END_SILENCE_MS = 1_100

        fun wav(pcm: ByteArray): ByteArray {
            val out = ByteArray(44 + pcm.size)
            fun i32(o: Int, v: Int) { for (k in 0..3) out[o + k] = (v shr (8 * k) and 0xff).toByte() }
            fun i16(o: Int, v: Int) { out[o] = (v and 0xff).toByte(); out[o + 1] = (v shr 8 and 0xff).toByte() }
            "RIFF".forEachIndexed { k, c -> out[k] = c.code.toByte() }
            i32(4, 36 + pcm.size)
            "WAVEfmt ".forEachIndexed { k, c -> out[8 + k] = c.code.toByte() }
            i32(16, 16); i16(20, 1); i16(22, 1); i32(24, RATE); i32(28, RATE * 2); i16(32, 2); i16(34, 16)
            "data".forEachIndexed { k, c -> out[36 + k] = c.code.toByte() }
            i32(40, pcm.size)
            System.arraycopy(pcm, 0, out, 44, pcm.size)
            return out
        }
    }
}
