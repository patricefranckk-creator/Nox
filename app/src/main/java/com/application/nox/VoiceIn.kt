package com.application.nox

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Oreille de Nox : reconnaissance vocale SUR L'APPAREIL (API 31+), sans envoi au cloud.
 * Si le pack français hors-ligne manque, on lance son téléchargement et on se rabat sur le
 * moteur standard en mode "préférer hors-ligne".
 */
class VoiceIn(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onListening()
        fun onLevel(level: Float)          // 0..1, pour animer le neurone
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onNothing()                    // silence / rien compris
        fun onVoiceError(msg: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var triedFallback = false
    var active = false; private set
    /** Silence à attendre avant de considérer la phrase finie. */
    var patienceMs = 2000
    private var startedAt = 0L

    val isOnDevice get() = onDevice

    init {
        build(preferOnDevice = true)
    }

    private fun build(preferOnDevice: Boolean) {
        recognizer?.destroy()
        onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer?.setRecognitionListener(rl)
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, language == FRENCH)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, patienceMs.toLong())
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, (patienceMs - 300).coerceAtLeast(800).toLong())
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000L)
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx) && !onDevice) {
            listener.onVoiceError("Aucun service de reconnaissance vocale sur ce téléphone")
            return
        }
        active = true
        startedAt = System.currentTimeMillis()
        try {
            recognizer?.startListening(intent())
        } catch (t: Throwable) {
            active = false
            listener.onVoiceError("Micro indisponible : ${t.message}")
        }
    }

    fun stop() {
        if (!active) return
        active = false
        try { recognizer?.cancel() } catch (_: Throwable) {}
    }

    fun release() {
        active = false
        recognizer?.destroy()
        recognizer = null
    }

    private val rl = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListening()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() = listener.onLevel(0f)
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partial: Bundle?) {
            val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) listener.onPartial(t)
        }

        override fun onResults(results: Bundle?) {
            active = false
            NoxLog.i("VOIX", "Reconnaissance Android OK en ${System.currentTimeMillis() - startedAt} ms (sur l'appareil=$onDevice)")
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (t.isNullOrBlank()) listener.onNothing() else listener.onFinal(t.trim())
        }

        override fun onError(error: Int) {
            active = false
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_CLIENT)
                NoxLog.w("VOIX", "Reconnaissance Android : erreur $error (sur l'appareil=$onDevice)")
            else NoxLog.d("VOIX", "Reconnaissance : rien compris (code $error)")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.onNothing()

                SpeechRecognizer.ERROR_CLIENT -> listener.onNothing() // annulation volontaire

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    if (onDevice && !triedFallback) {
                        triedFallback = true
                        if (Build.VERSION.SDK_INT >= 33) {
                            try { recognizer?.triggerModelDownload(intent()) } catch (_: Throwable) {}
                        }
                        build(preferOnDevice = false)
                        start()
                    } else {
                        listener.onVoiceError("Pack vocal français hors-ligne absent (Réglages › Google › Voix)")
                    }
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.onVoiceError("Autorise le micro pour parler à Nox")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    build(preferOnDevice = onDevice); listener.onNothing()
                }

                else -> listener.onVoiceError("Reconnaissance vocale : erreur $error")
            }
        }
    }

    companion object {
        const val FRENCH = "fr-FR"
        /** Langue écoutée (le mode interprète alterne entre le français et l'autre langue). */
        @Volatile var language = FRENCH
    }
}
