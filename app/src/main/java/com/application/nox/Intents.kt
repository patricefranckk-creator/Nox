package com.application.nox

/**
 * Phrases spéciales reconnues avant le modèle : point du matin, écran, interprète, notes vocales,
 * « retiens cette page ». Pur Kotlin (testé à chaque build).
 */
object Intents {

    data class Hit(val kind: String, val arg: String = "")

    const val BRIEFING = "briefing"
    const val SCREEN_SUMMARY = "ecran_resume"
    const val SCREEN_REPLY = "ecran_reponse"
    const val INTERPRETER = "interprete"
    const val INTERPRETER_END = "interprete_fin"
    const val NOTE = "note"
    const val NOTES_FIND = "notes_cherche"
    const val KEEP_PAGE = "retiens_page"

    /** Langue de l'interprète : nom affiché, code de reconnaissance vocale, nom anglais pour la traduction. */
    data class Lang(val name: String, val tag: String, val english: String)

    val LANGS = listOf(
        Lang("anglais", "en-GB", "English"), Lang("espagnol", "es-ES", "Spanish"), Lang("allemand", "de-DE", "German"),
        Lang("italien", "it-IT", "Italian"), Lang("portugais", "pt-PT", "Portuguese"), Lang("neerlandais", "nl-NL", "Dutch"),
        Lang("arabe", "ar-MA", "Arabic"), Lang("chinois", "zh-CN", "Chinese (Mandarin)"), Lang("japonais", "ja-JP", "Japanese"),
        Lang("russe", "ru-RU", "Russian"), Lang("polonais", "pl-PL", "Polish"), Lang("turc", "tr-TR", "Turkish"),
        Lang("roumain", "ro-RO", "Romanian"), Lang("catalan", "ca-ES", "Catalan"), Lang("basque", "eu-ES", "Basque"),
        Lang("ukrainien", "uk-UA", "Ukrainian"), Lang("grec", "el-GR", "Greek"), Lang("coreen", "ko-KR", "Korean"),
    )

    private val PREFIX = Regex("^(?:(?:ok|dis|hey|he|eh) )?(?:nox[ ,:]+)?(?:(?:est-ce que )?(?:tu peux|peux-tu|pourrais-tu|tu pourrais) |(?:s'il te plait|stp),? )?")
    private val GREETING = Regex("^(?:bonjour|salut|coucou|hello|bon matin|bien le bonjour)(?: (?:a toi|mon ami))?(?: nox)?$")
    private val BRIEF = Regex("\\b(?:point du matin|point de la journee|mon briefing|le briefing|briefing du jour|programme (?:du jour|de la journee|d'aujourd'hui)|qu'est-ce qui m'attend aujourd'hui|ma journee d'aujourd'hui)\\b")
    private val SCREEN_SUM = Regex("^(?:resume|resumer|lis|lire|explique|expliquer|decris|decrire|dis-moi ce qu'il y a sur)(?:[- ]moi)?(?: ce qu'il y a (?:a|sur))? (?:mon |l'|cet |ce que (?:montre|dit) (?:mon |l')?)?ecran\\b.*$")
    private val SCREEN_REP = Regex("^(?:propose|proposer|suggere|suggerer|ecris|ecrire|redige|rediger|trouve|aide-moi a (?:ecrire|trouver))(?:[- ]moi)? (?:une )?reponse\\b.*$|^qu'est-ce que (?:je (?:peux|dois) )?repond(?:re|s)\\b.*$|^que (?:dois-je|puis-je|je peux) repondre\\b.*$")
    private val INTERP = Regex("^(?:(?:active|activer|lance|lancer|demarre|demarrer|passe|passer|mets|mettre)(?:[- ]toi)?(?: en| le)? )?(?:le )?(?:mode )?(?:interprete|traducteur|traduction simultanee|mode traduction)\\b(.*)$")
    private val INTERP_END = Regex("^(?:fin|stop|stoppe|arrete|arreter|quitte|quitter|termine|terminer|sors|sortir|coupe|couper|desactive|desactiver)(?: (?:du|le|de|la))? (?:mode )?(?:interprete|traducteur|traduction)\\b.*$")
    private val NOTE_RX = Regex("^(?:note|notes|nouvelle note|prends? (?:une |en )?note|prendre (?:une |en )?note|ecris une note|fais une note|note bien|note ca|note-moi)(?: que| ceci| bien)?\\s*[:,\\-]?\\s+(.+)$")
    private val NOTES_FIND_RX = Regex("^(?:qu'est-ce que |qu'est ce que |qu'ai-je |quoi )?(?:j'ai note|ai-je note|j'avais note|mes notes|ma note|les notes)(?: (?:sur|a propos de|a propos d'|au sujet de|au sujet d'|concernant|pour|de|du|d'|des))? ?(.*)$")
    private val KEEP = Regex("\\b(?:retiens|retenir|garde|garder|memorise|memoriser|enregistre|enregistrer|sauvegarde|sauvegarder|ajoute|ajouter|mets|mettre)(?:[- ]moi| bien)? (?:cette|ces|la|les|ce) (?:page|pages|resultat|resultats|recherche|site|sites|article|articles)\\b(?: (?:web|internet))?(?: (?:dans|a) (?:la|ma) bibliotheque)?$")

    /**
     * @param hour heure actuelle (0-23) : un simple « bonjour » ne lance le point du matin qu'avant midi.
     * @param interpreting vrai si le mode interprète est actif (les phrases d'arrêt courtes suffisent alors).
     */
    fun detect(text: String, hour: Int, interpreting: Boolean = false): Hit? {
        val raw = text.replace('’', '\'').trim().trimEnd('?', '!', '.', ' ', '…')
        val full = Text.fold(raw)
        val p = PREFIX.find(full)?.value?.length ?: 0
        val lead = full.substring(p).let { it.length - it.trimStart().length }
        val f = full.substring(p).trim()
        if (f.isEmpty()) return null
        if (interpreting) {
            if (INTERP_END.matches(f) || f in SHORT_STOP) return Hit(INTERPRETER_END)
            return null
        }
        if (INTERP_END.matches(f)) return Hit(INTERPRETER_END)
        if (GREETING.matches(f)) return if (hour < 12) Hit(BRIEFING) else null
        if (f.length < 70 && BRIEF.containsMatchIn(f)) return Hit(BRIEFING)
        if (SCREEN_SUM.matches(f)) return Hit(SCREEN_SUMMARY)
        if (SCREEN_REP.matches(f)) return Hit(SCREEN_REPLY)
        INTERP.matchEntire(f)?.let { m ->
            val rest = m.groupValues[1]
            val lang = LANGS.firstOrNull { Regex("\\b${it.name}\\b").containsMatchIn(rest) } ?: LANGS.first()
            if (rest.isBlank() || LANGS.any { Regex("\\b${it.name}\\b").containsMatchIn(rest) }) return Hit(INTERPRETER, lang.name)
        }
        if (f.length < 90 && KEEP.containsMatchIn(f)) return Hit(KEEP_PAGE)
        NOTES_FIND_RX.matchEntire(f)?.let { m -> return Hit(NOTES_FIND, original(raw, full, p + lead, m.groups[1]!!.range.first, m.groupValues[1])) }
        NOTE_RX.matchEntire(f)?.let { m -> return Hit(NOTE, original(raw, full, p + lead, m.groups[1]!!.range.first, m.groupValues[1])) }
        return null
    }

    private val SHORT_STOP = setOf("stop", "fin", "arrete", "termine", "c'est fini", "on arrete", "stop interprete", "fin interprete")

    /** Retrouve le passage d'origine (avec accents et majuscules) quand le repliement n'a pas changé la longueur. */
    private fun original(raw: String, folded: String, offset: Int, start: Int, fallback: String): String {
        if (raw.length != folded.length) return fallback.trim()
        val from = offset + start
        return if (from in 0..raw.length) raw.substring(from).trim() else fallback.trim()
    }

    fun lang(name: String): Lang = LANGS.firstOrNull { it.name == name } ?: LANGS.first()

    /** Nettoie une note dictée : hésitations retirées, majuscule, point final. */
    fun cleanNote(s: String): String {
        val t = s.replace(Regex("(?i)\\b(?:euh+|heu+|bah|ben|hum+|hmm+|du coup|en fait|genre)\\b[,]?\\s*"), "")
            .replace(Regex("\\s+"), " ").trim().trim(',', ';', ' ')
        if (t.isEmpty()) return t
        val c = t.replaceFirstChar { it.uppercase() }
        return if (c.last() in ".!?…") c else "$c."
    }
}
