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

    /** "native" = l'audio va directement dans Gemma (modèles E) · "android" = reconnaissance vocale du téléphone. */
    var listenMode: String
        get() = sp.getString("listen_mode", "native") ?: "native"
        set(v) = sp.edit().putString("listen_mode", v).apply()

    /** Outils locaux (minuteur, notes, mémoire…) proposés au modèle. */
    var skillsEnabled: Boolean
        get() = sp.getBoolean("skills", true)
        set(v) = sp.edit().putBoolean("skills", v).apply()

    /** Vue 📷 : charge l'encodeur d'images de Gemma E (un peu plus de mémoire). */
    var vision: Boolean
        get() = sp.getBoolean("vision", true)
        set(v) = sp.edit().putBoolean("vision", v).apply()

    /** Bibliothèque : injecter automatiquement les extraits pertinents avant chaque réponse. */
    var ragAuto: Boolean
        get() = sp.getBoolean("rag_auto", true)
        set(v) = sp.edit().putBoolean("rag_auto", v).apply()

    /** Mémoire épisodique : chaque échange devient cherchable dans les conversations futures. */
    var rememberChats: Boolean
        get() = sp.getBoolean("remember_chats", true)
        set(v) = sp.edit().putBoolean("remember_chats", v).apply()

    /** Journal en mode détaillé. */
    var verboseLog: Boolean
        get() = sp.getBoolean("verbose_log", false)
        set(v) = sp.edit().putBoolean("verbose_log", v).apply()

    var voiceName: String
        get() = sp.getString("voice", "") ?: ""
        set(v) = sp.edit().putString("voice", v).apply()

    /** Vrai pendant un chargement : s'il reste vrai au lancement suivant, c'est que l'appli a planté. */
    var loadInProgress: Boolean
        get() = sp.getBoolean("load_in_progress", false)
        set(v) { sp.edit().putBoolean("load_in_progress", v).commit() }

    companion object {
        const val DEFAULT_PERSONA =
            "Tu es Nox, l'assistant personnel qui vit dans ce téléphone, sans internet. " +
            "Tu parles comme une vraie personne : chaleureux, direct, curieux, avec une pointe d'humour quand ça s'y prête. Tu tutoies. " +
            "On te parle souvent à la voix ou en écrivant vite : comprends l'intention même si le message est approximatif ou mal orthographié, " +
            "et ne demande une précision que si c'est vraiment ambigu. " +
            "Adapte la longueur : une phrase ou deux pour une question simple ou une action, plus si on te demande d'expliquer. " +
            "Tes réponses sont lues à voix haute : phrases naturelles, pas de markdown, pas de listes ni d'émojis, sauf si on demande du code, un tableau ou une liste. " +
            "Ne dis jamais « en tant qu'IA ». Si tu ne sais pas, dis-le simplement plutôt que d'inventer."
    }
}
