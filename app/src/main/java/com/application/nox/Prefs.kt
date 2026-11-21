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
        set(v) { sp.edit().putString("backend", v).commit() }

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
        get() = (sp.getString("persona", DEFAULT_PERSONA) ?: DEFAULT_PERSONA).replace("dans ce téléphone, sans internet.", "dans ce téléphone.")
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

    /** Écoute : silence (ms) avant de considérer que tu as fini de parler. */
    var patienceMs: Int
        get() = sp.getInt("patience_ms", 2000)
        set(v) = sp.edit().putInt("patience_ms", v).apply()

    /** Durée maximale d'un message vocal (s). */
    var maxListenSec: Int
        get() = sp.getInt("max_listen", 90)
        set(v) = sp.edit().putInt("max_listen", v).apply()

    /** Relances proposées sous chaque réponse : retirées du chat épuré. */
    val suggestions: Boolean get() = false

    /** Répondre d'abord avec les passages de la bibliothèque, sans solliciter le modèle. */
    var ragFirst: Boolean
        get() = sp.getBoolean("rag_first", true)
        set(v) = sp.edit().putBoolean("rag_first", v).apply()

    /** « cherche sur internet… » : recherche web puis réponse écrite par Nox. */
    var webSearch: Boolean
        get() = sp.getBoolean("web_search", true)
        set(v) = sp.edit().putBoolean("web_search", v).apply()

    var voiceName: String
        get() = sp.getString("voice", "") ?: ""
        set(v) = sp.edit().putString("voice", v).apply()

    /** Vrai pendant un chargement : s'il reste vrai au lancement suivant, c'est que l'appli a planté. */
    var loadInProgress: Boolean
        get() = sp.getBoolean("load_in_progress", false)
        set(v) { sp.edit().putBoolean("load_in_progress", v).commit() }

    /** Heure du début du dernier chargement : pour relier un plantage à ce chargement. */
    var loadStartedAt: Long
        get() = sp.getLong("load_started_at", 0L)
        set(v) { sp.edit().putLong("load_started_at", v).commit() }

    /** Configuration GPU en cours de démarrage (ex. « GPU+MTP+A+V/4096 ») : si l'appli meurt, c'est elle. */
    var gpuPending: String
        get() = sp.getString("gpu_pending", "") ?: ""
        set(v) { sp.edit().putString("gpu_pending", v).commit() }

    /** Configurations GPU qui ont fait planter le pilote : la cascade les saute. */
    var gpuBad: Set<String>
        get() = sp.getStringSet("gpu_bad", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("gpu_bad", v).commit() }

    /** Nox reste éveillé en arrière-plan (service de premier plan, notification permanente). */
    var background: Boolean
        get() = sp.getBoolean("background", true)
        set(v) = sp.edit().putBoolean("background", v).apply()

    /** Le centre des autorisations a déjà été présenté au premier lancement. */
    var permsShown: Boolean
        get() = sp.getBoolean("perms_shown", false)
        set(v) = sp.edit().putBoolean("perms_shown", v).apply()

    /** Pilotage de l'écran : demander confirmation avant d'envoyer, payer, supprimer… */
    var confirmSensitive: Boolean
        get() = sp.getBoolean("confirm_sensitive", true)
        set(v) = sp.edit().putBoolean("confirm_sensitive", v).apply()

    /** Les trois tuiles « Mes favoris » du menu (identifiants séparés par des virgules). */
    var favorites: List<String>
        get() = (sp.getString("favorites", "vocal,bibliotheque,listes") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("favorites", v.joinToString(",")).apply()

    companion object {
        const val DEFAULT_PERSONA =
            "Tu es Nox, l'assistant personnel qui vit dans ce téléphone. " +
            "Tu parles comme une vraie personne : chaleureux, direct, curieux, avec une pointe d'humour quand ça s'y prête. Tu tutoies. " +
            "On te parle souvent à la voix ou en écrivant vite : comprends l'intention même si le message est approximatif ou mal orthographié, " +
            "et ne demande une précision que si c'est vraiment ambigu. " +
            "Adapte la longueur : une phrase ou deux pour une question simple ou une action, plus si on te demande d'expliquer. " +
            "Tes réponses sont lues à voix haute : phrases naturelles, pas de markdown, pas de listes ni d'émojis, sauf si on demande du code, un tableau ou une liste. " +
            "Ne dis jamais « en tant qu'IA ». Si tu ne sais pas, dis-le simplement plutôt que d'inventer."
    }
}
