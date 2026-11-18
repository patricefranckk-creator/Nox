package com.application.nox

import android.content.Context

/** Tous les réglages persistants de Nox. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nox", Context.MODE_PRIVATE)

    var modelPath: String
        get() = sp.getString("model_path", "") ?: ""
        set(v) = sp.edit().putString("model_path", v).apply()

    /** "gpu" (rapide, défaut) ou "cpu" (secours). */
    var backend: String
        get() = sp.getString("backend", "gpu") ?: "gpu"
        set(v) = sp.edit().putString("backend", v).apply()

    /** Décodage spéculatif MTP (jusqu'à ~1,7× plus rapide sur Gemma 4). */
    var turbo: Boolean
        get() = sp.getBoolean("turbo", true)
        set(v) = sp.edit().putBoolean("turbo", v).apply()

    /** Fenêtre de contexte (tokens). 4096 = bon compromis vitesse/mémoire avec 8 Go. */
    var contextTokens: Int
        get() = sp.getInt("ctx", 4096)
        set(v) = sp.edit().putInt("ctx", v).apply()

    var temperature: Float
        get() = sp.getFloat("temp", 0.8f)
        set(v) = sp.edit().putFloat("temp", v).apply()

    var persona: String
        get() = sp.getString("persona", DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        set(v) = sp.edit().putString("persona", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v).apply()

    /** Lire automatiquement les réponses à voix haute. */
    var autoSpeak: Boolean
        get() = sp.getBoolean("auto_speak", true)
        set(v) = sp.edit().putBoolean("auto_speak", v).apply()

    /** Mode mains libres : après chaque réponse parlée, Nox réécoute tout seul. */
    var handsFree: Boolean
        get() = sp.getBoolean("hands_free", false)
        set(v) = sp.edit().putBoolean("hands_free", v).apply()

    var speechRate: Float
        get() = sp.getFloat("rate", 1.05f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    var pitch: Float
        get() = sp.getFloat("pitch", 0.95f)
        set(v) = sp.edit().putFloat("pitch", v).apply()

    var voiceName: String
        get() = sp.getString("voice", "") ?: ""
        set(v) = sp.edit().putString("voice", v).apply()

    /** Vrai pendant un chargement : s'il reste vrai au lancement suivant, c'est que l'appli a planté. */
    var loadInProgress: Boolean
        get() = sp.getBoolean("load_in_progress", false)
        set(v) { sp.edit().putBoolean("load_in_progress", v).commit() }

    companion object {
        const val DEFAULT_PERSONA =
            "Tu es Nox, une intelligence artificielle qui tourne entièrement sur ce téléphone, " +
            "sans internet. Tu es vive, chaleureuse, directe, avec une pointe d'humour discret. " +
            "Tu réponds en français. Tes réponses sont souvent lues à voix haute : fais des phrases " +
            "naturelles et plutôt courtes, sans markdown, sans listes à puces ni émojis, sauf si " +
            "on te demande explicitement du code, un tableau ou une liste. Si tu ne sais pas, " +
            "dis-le franchement plutôt que d'inventer."
    }
}
