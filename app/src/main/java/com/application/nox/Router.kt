package com.application.nox

/**
 * Aiguillage d'un message écrit, avant le modèle :
 *  - « cherche sur internet… » → recherche web, puis Nox écrit la réponse ;
 *  - « plus de détails », « développe »… → le modèle développe la dernière réponse de la bibliothèque ;
 *  - question de connaissances avec un passage très pertinent → réponse directe depuis la bibliothèque,
 *    sans solliciter le modèle ;
 *  - tout le reste (conversation, commandes, pilotage d'applis) → le modèle, comme avant.
 */
object Router {

    /** Seuils plus exigeants que l'injection automatique : on répond sans le modèle seulement si c'est net. */
    const val COS_DIRECT = 0.82
    private const val BM25_DIRECT = 8.0
    private const val COS_WITH_BM25 = 0.76

    private val DETAILS = Regex("^(?:et )?(?:(?:donne|dis)[- ]moi )?(?:plus de details?|plus d'infos?|plus d'informations|developpe|detaille|approfondis|precise|" +
        "explique (?:mieux|davantage|plus|en detail)|dis[- ]m'en plus|en savoir plus|vas[- ]y developpe|continue|resume|fais[- ]moi un resume|et apres|la suite)\\b")

    private val ACTIONS = Regex("^(?:nox[ ,]+)?(?:peux-tu |tu peux |pourrais-tu )?(?:ouvre|ouvrir|lance|ferme|appelle|telephone|envoie|ecris|repond|mets|met |regle|active|desactive|allume|eteins|" +
        "rappelle|programme|ajoute|retire|supprime|efface|note|joue|lis |pause|reprends|suivant|minuteur|alarme|reveil|monte|baisse|coupe|prends|photographie|partage|copie|" +
        "va |aller|emmene|itineraire|navigue|cherche|recherche|trouve|lis-moi|calcule|combien font|traduis)")

    private val CHAT = Regex("\\b(tu|toi|ton|ta|tes|t'|te )\\b|^(salut|bonjour|bonsoir|coucou|hello|merci|ok|d'accord|oui|non|super|cool|bravo|bonne nuit)\\b")

    private val QUESTION = Regex("^(?:qu'est[- ]ce|qu'est ce|c'est quoi|qui |quoi |quand |ou |pourquoi|comment|combien|quel|quelle|quels|quelles|est-ce|explique|definis|definition|" +
        "parle[- ]moi|dis[- ]moi|que faire|que dois|que sais|a quoi|en quoi|y a-t-il|faut-il|peut-on|doit-on|les symptomes|les causes|la difference)")

    private val TIME = Regex("^(?:nox[ ,]+)?(?:(?:cherche|regarde|donne|dis)(?:[- ]moi)? (?:sur (?:le net|internet|la toile|le web) )?)?" +
        "(?:quelle heure(?: est[- ]il| il est)?|l'heure(?: qu'il est)?|tu as l'heure|il est quelle heure|on est quel jour|quel jour (?:on est|sommes[- ]nous|est[- ]on|est[- ]il|il est)|" +
        "quelle est la date(?: d'aujourd'hui)?|la date(?: d'aujourd'hui)?|on est le combien|quel jour sommes[- ]nous)$")

    /** Heure ou date demandée : réponse exacte depuis l'horloge du téléphone, sans le modèle (il l'inventait). */
    fun timeAnswer(message: String): String? {
        val f = Text.fold(message.trim()).trimEnd('.', '!', '?', ' ', '…').replace(Regex("\\s+"), " ")
        if (!TIME.matches(f)) return null
        val now = java.util.Date()
        val day = java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.FRANCE).format(now)
        val hour = java.text.SimpleDateFormat("H'h'mm", java.util.Locale.FRANCE).format(now)
        return if (f.contains("heure")) "Il est $hour." else "Nous sommes le $day."
    }

    fun isMoreDetails(message: String): Boolean {
        val f = Text.fold(message.trim()).trimEnd('.', '!', '?', ' ')
        return f.length <= 60 && DETAILS.containsMatchIn(f)
    }

    fun isKnowledgeQuestion(message: String): Boolean {
        val f = Text.fold(message.trim())
        if (f.length < 8 || ACTIONS.containsMatchIn(f) || CHAT.containsMatchIn(f)) return false
        return f.endsWith("?") || QUESTION.containsMatchIn(f)
    }

    /** Passage assez sûr pour répondre sans le modèle (bibliothèque, pas les conversations passées). */
    fun confident(h: Library.Hit): Boolean =
        !h.chunk.source.startsWith("chat:") &&
            ((h.cos >= COS_DIRECT && h.bm25 > 0.0) || (h.bm25 >= BM25_DIRECT && (h.cos == 0.0 || h.cos >= COS_WITH_BM25)))

    /**
     * La réponse rapide : le texte même du meilleur passage (le titre de section en tête retiré),
     * complété par le passage suivant de la même fiche s'il est court. Pas de reformulation.
     */
    fun directAnswer(hits: List<Library.Hit>): String? {
        val best = hits.firstOrNull { confident(it) } ?: return null
        val parts = ArrayList<String>()
        parts += clean(best.chunk)
        if (parts.first().length < 320) hits.firstOrNull { it !== best && it.chunk.source == best.chunk.source }?.let { parts += clean(it.chunk) }
        var text = parts.joinToString("\n\n").trim()
        if (text.length > 1100) {
            val cut = text.lastIndexOf(". ", 1100)
            text = if (cut > 400) text.substring(0, cut + 1) else text.take(1100).trimEnd() + "…"
        }
        return text.ifBlank { null }
    }

    private fun clean(c: Library.Chunk): String {
        var t = c.text.trim()
        val head = t.substringBefore(" : ", "")
        if (head.isNotEmpty() && head.length <= 140 && !head.contains('.')) t = t.substringAfter(" : ").trim()
        return t.replace(Regex("\\n{3,}"), "\n\n")
    }
}
