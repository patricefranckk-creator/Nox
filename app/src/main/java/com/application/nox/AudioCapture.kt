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
        /** Temps écoulé depuis le début de la parole (pour afficher un compteur). */
        fun onCaptureElapsed(speechMs: Long) {}
        /** Clips WAV de 30 s maximum chacun (limite de l'encodeur audio de Gemma), durée totale en ms. */
        fun onCaptured(wavs: List<ByteArray>, durationMs: Long)
        fun onCaptureNothing()
        fun onCaptureError(msg: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var forceStop = false
    private var thread: Thread? = null

    val active get() = running

    private var endSilenceMs = 2000
    private var maxMs = 90_000

    /**
     * @param patienceMs silence à attendre avant de considérer que tu as fini.
     * @param maxSeconds durée maximale d'un message vocal.
     */
    @SuppressLint("MissingPermission")
    fun start(patienceMs: Int = 2000, maxSeconds: Int = 90) {
        if (running) return
        endSilenceMs = patienceMs.coerceIn(600, 8000)
        maxMs = maxSeconds.coerceIn(10, 180) * 1000
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
        val maxFrames = maxMs / FRAME_MS
        val waitFrames = WAIT_MS / FRAME_MS
        val endSilence = endSilenceMs / FRAME_MS
        var lastTick = 0

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
                    val spoken = pcm.size() / 2 * 1000L / RATE
                    if (spoken / 500 != lastTick.toLong()) { lastTick = (spoken / 500).toInt(); main.post { listener.onCaptureElapsed(spoken) } }
                    if (silenceFrames >= endSilence || forceStop || totalFrames > maxFrames) break
                }
            }
        } catch (t: Throwable) {
            NoxLog.e("OREILLE", "Enregistrement interrompu", t)
            main.post { listener.onCaptureError("Enregistrement interrompu : ${t.message}") }
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            ns?.release(); agc?.release(); rec.release()
        }

        val cancelled = !running
        running = false
        if (cancelled) { NoxLog.d("OREILLE", "Écoute annulée"); return }
        val data = pcm.toByteArray()
        val ms = data.size / 2 * 1000L / RATE
        val why = when { forceStop -> "touche de fin"; !speaking -> "aucune voix détectée"; totalFrames > maxFrames -> "limite ${maxMs / 1000} s"; else -> "silence de ${endSilenceMs} ms" }
        NoxLog.i("OREILLE", "Capture : ${ms} ms de parole sur ${totalFrames * FRAME_MS} ms · bruit de fond ${noise.toInt()} · fin : $why · " +
            "NS=${ns != null} AGC=${agc != null}")
        main.post {
            if (!speaking || ms < 400) listener.onCaptureNothing()
            else listener.onCaptured(split(data), ms)
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
        private const val WAIT_MS = 8_000
        /** Un clip audio pour Gemma : 30 s maximum. */
        private const val CLIP_BYTES = RATE * 2 * 30

        /** Découpe la parole en clips de 30 s, en coupant de préférence sur un passage calme. */
        fun split(pcm: ByteArray): List<ByteArray> {
            if (pcm.size <= CLIP_BYTES) return listOf(wav(pcm))
            val out = ArrayList<ByteArray>()
            var start = 0
            while (start < pcm.size) {
                var end = minOf(start + CLIP_BYTES, pcm.size)
                if (end < pcm.size) {
                    // cherche dans les 3 dernières secondes la fenêtre de 100 ms la plus calme
                    var best = end; var bestE = Double.MAX_VALUE
                    var w = end - RATE * 2 * 3
                    while (w + 3200 <= end) {
                        var e = 0.0
                        var k = w
                        while (k < w + 3200) { val v = ((pcm[k + 1].toInt() shl 8) or (pcm[k].toInt() and 0xff)).toShort().toDouble(); e += v * v; k += 2 }
                        if (e < bestE) { bestE = e; best = w + 1600 }
                        w += 1600
                    }
                    end = best - (best - start) % 2
                }
                out += wav(pcm.copyOfRange(start, end))
                start = end
            }
            return out
        }

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
