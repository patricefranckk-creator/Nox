package com.application.nox

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Voix de Nox : synthèse vocale hors-ligne, en STREAMING.
 * Nox commence à parler dès la première phrase générée, sans attendre la fin de la réponse.
 */
class VoiceOut(ctx: Context, private val prefs: Prefs, private val listener: Listener) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onSpeakingChanged(speaking: Boolean)
        /** Plus rien à dire ET la génération est terminée. */
        fun onAllSpoken()
    }

    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ok = false
    private val buffer = StringBuilder()
    private var inCodeBlock = false
    private var pending = 0
    /** Texte de chaque phrase en cours de lecture, pour le visage (émotion, mots prononcés). */
    private val texts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var seq = 0
    private var streamClosed = true
    private var streamActive = false
    var speaking = false; private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { NoxLog.e("VOIX", "Synthèse vocale indisponible (statut $status)"); return }
        ok = true
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.language = Locale.FRANCE
        applyVoice()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                val text = id?.let { texts[it] }
                main.post {
                    setSpeaking(true)
                    if (text != null) FaceView.current?.sentence(text)
                }
            }

            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                val text = id?.let { texts[it] } ?: return
                if (start < 0 || end > text.length || start >= end) return
                val w = text.substring(start, end)
                main.post { FaceView.current?.word(w, prefs.speechRate) }
            }

            override fun onDone(id: String?) {
                id?.let { texts.remove(it) }
                main.post { FaceView.current?.silent(); utteranceFinished() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                main.post { utteranceFinished() }
            }

            override fun onError(id: String?, code: Int) {
                NoxLog.w("VOIX", "Erreur de lecture TTS (code $code)")
                main.post { utteranceFinished() }
            }
        })
    }

    /** Voix françaises installées localement (aucune voix réseau). */
    fun frenchVoices(): List<Voice> = try {
        tts.voices.orEmpty()
            .filter { it.locale.language == "fr" && !it.isNetworkConnectionRequired &&
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
    } catch (_: Throwable) { emptyList() }

    fun applyVoice() {
        if (!ok) return
        val voices = frenchVoices()
        val chosen = voices.firstOrNull { it.name == prefs.voiceName } ?: voices.firstOrNull()
        if (chosen != null) tts.voice = chosen
        NoxLog.i("VOIX", "Voix : ${chosen?.name ?: "défaut"} (qualité ${chosen?.quality ?: "?"}) · ${voices.size} voix FR hors-ligne · débit ${prefs.speechRate} · hauteur ${prefs.pitch}")
        tts.setSpeechRate(prefs.speechRate)
        tts.setPitch(prefs.pitch)
    }

    // ------------------------------------------------------------ flux

    fun beginStream() {
        buffer.setLength(0)
        inCodeBlock = false
        streamClosed = false
        streamActive = true
    }

    fun feed(chunk: String) {
        if (!ok) return
        buffer.append(chunk)
        // On découpe aux fins de phrase pour parler au plus tôt.
        while (true) {
            val cut = findCut(buffer)
            if (cut < 0) break
            val sentence = buffer.substring(0, cut + 1)
            buffer.delete(0, cut + 1)
            speakClean(sentence)
        }
    }

    fun endStream() {
        streamClosed = true
        if (ok && buffer.isNotBlank()) speakClean(buffer.toString())
        buffer.setLength(0)
        checkAllDone()
    }

    /** Langue de lecture : la voix française choisie, ou la voix du téléphone pour une autre langue (interprète). */
    fun setLanguage(tag: String) {
        if (!ok) return
        if (tag.startsWith("fr")) { tts.language = Locale.FRANCE; applyVoice(); return }
        val r = tts.setLanguage(Locale.forLanguageTag(tag))
        NoxLog.i("VOIX", "Lecture en $tag (${if (r >= TextToSpeech.LANG_AVAILABLE) "voix disponible" else "voix absente, code $r"})")
    }

    /** Lit un texte complet (ex. relire un ancien message). */
    fun speakAll(text: String) {
        stop()
        beginStream(); feed(text); endStream()
    }

    fun stop() {
        streamClosed = true
        streamActive = false
        buffer.setLength(0)
        pending = 0
        texts.clear()
        FaceView.current?.silent()
        if (ok) tts.stop()
        setSpeaking(false)
    }

    fun release() {
        tts.stop(); tts.shutdown()
    }

    private fun findCut(sb: StringBuilder): Int {
        // Ne coupe pas une phrase trop courte ("M. Dupont", "3.5")
        for (i in sb.indices) {
            val c = sb[i]
            val isEnd = c == '\n' || ((c == '.' || c == '!' || c == '?' || c == '…' || c == ':' || c == ';') &&
                i + 1 < sb.length && sb[i + 1].isWhitespace())
            if (isEnd && (i >= 24 || c == '\n')) return i
        }
        // Phrase interminable : on coupe à une virgule pour garder la fluidité
        if (sb.length > 220) {
            val comma = sb.lastIndexOf(", ")
            if (comma > 60) return comma
        }
        return -1
    }

    private fun speakClean(raw: String) {
        var t = raw
        // Les blocs de code ne se lisent pas à voix haute
        if (t.contains("```")) {
            val sb = StringBuilder()
            t.split("```").forEachIndexed { idx, part ->
                if (idx > 0) {
                    inCodeBlock = !inCodeBlock
                    if (inCodeBlock) sb.append(" Voici le code, il est affiché à l'écran. ")
                }
                if (!inCodeBlock) sb.append(part)
            }
            t = sb.toString()
        } else if (inCodeBlock) return

        t = t.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1") // liens markdown
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("^\\s*#+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("[*_~>|]"), "")
            .replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FFFF}]"), "") // émojis
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.isEmpty() || t.none { it.isLetterOrDigit() }) return
        val id = "nox-${seq++}"
        texts[id] = t
        pending++
        tts.speak(t, TextToSpeech.QUEUE_ADD, Bundle(), id)
    }

    private fun utteranceFinished() {
        pending = (pending - 1).coerceAtLeast(0)
        checkAllDone()
    }

    private fun checkAllDone() {
        if (pending == 0 && streamClosed && streamActive) {
            streamActive = false
            setSpeaking(false)
            listener.onAllSpoken()
        }
    }

    private fun setSpeaking(s: Boolean) {
        if (speaking == s) return
        speaking = s
        listener.onSpeakingChanged(s)
    }
}
