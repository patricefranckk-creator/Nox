package com.application.nox

/**
 * Émotion d'une phrase dite par Nox, pour le visage (étape 1 : règles simples, sans modèle).
 * Pur Kotlin, testé à chaque build. L'étape 2 affinera avec EmbeddingGemma.
 */
object Emotion {

    const val NEUTRE = "neutre"
    const val JOIE = "joie"
    const val EMPATHIE = "empathie"
    const val SURPRISE = "surprise"
    const val QUESTION = "question"
    const val SERIEUX = "serieux"

    private val SERIOUS = Regex("\\b(?:attention|danger|dangereux|urgence|urgent|important|appelle le (?:15|112|18|17)|samu|pompiers|interdit|prudence|prudent|risque|avertissement|ne jamais|surtout pas|evite)\\b")
    private val EMPATHY = Regex("\\b(?:desole|desolee|navre|courage|je comprends|dommage|triste|tristesse|difficile|pas facile|dur pour toi|je suis la|prends soin|condoleances|pense a toi|ca va aller|ne t'inquiete)\\b")
    private val SURPRISED = Regex("\\b(?:ah bon|incroyable|waouh|wow|etonnant|surprenant|dingue|quelle surprise|pas possible|oh la la|ca alors|figure-toi|tu le crois)\\b")
    private val HAPPY = Regex("\\b(?:bonne nouvelle|super|genial|bravo|felicitations|chouette|parfait|avec plaisir|ravi|ravie|content|contente|excellent|excellente|top|magnifique|formidable|haha|ahah|youpi|cool|bonne journee|belle journee|grand soleil)\\b")
    private val NEGATED = Regex("\\b(?:pas|plus|jamais|rien de) (?:tres |si |vraiment )?$")
    private val SMILEY = Regex("[😀😃😄😁😆😊🙂😉🥳🎉👍✨❤]")

    fun of(sentence: String): String {
        val raw = sentence.trim()
        if (raw.isEmpty()) return NEUTRE
        val f = Text.fold(raw.lowercase().replace('’', '\''))
        fun hit(rx: Regex) = rx.findAll(f).any { m -> !NEGATED.containsMatchIn(f.substring(0, m.range.first)) }
        return when {
            hit(SERIOUS) -> SERIEUX
            hit(EMPATHY) -> EMPATHIE
            hit(SURPRISED) -> SURPRISE
            hit(HAPPY) || SMILEY.containsMatchIn(raw) -> JOIE
            raw.trimEnd().endsWith("?") -> QUESTION
            else -> NEUTRE
        }
    }
}
