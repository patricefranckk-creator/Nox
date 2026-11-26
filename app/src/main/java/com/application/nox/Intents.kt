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
    const val REMEMBER = "memoire_retiens"
    const val FORGET = "memoire_oublie"
    const val ABOUT_ME = "memoire_moi"
    const val ASK_ONLINE = "en_ligne"

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

    private val REMEMBER_RX = Regex("^(?:retiens|retenir|souviens-toi|souviens toi|rappelle-toi|n'oublie pas|memorise|garde en memoire|enregistre dans ta memoire|mets dans ta memoire)(?: bien)?(?: que | qu'|\\s*:\\s*)(.+)$")
    private val FORGET_RX = Regex("^(?:oublie|efface de ta memoire|retire de ta memoire)(?: que | qu'| ce que je t'ai dit (?:sur|a propos de|au sujet de) |\\s*:\\s*)(.+)$")
    private val ABOUT_ME_RX = Regex("^(?:qu'est-ce que tu sais (?:de|sur) moi|que sais-tu (?:de|sur) moi|tu sais quoi (?:de|sur) moi|qu'as-tu retenu(?: (?:sur|de) moi)?|qu'est-ce que tu as retenu(?: (?:sur|de) moi)?|montre(?:-moi)? (?:ta memoire|tes souvenirs)|tes souvenirs(?: sur moi)?|ta memoire)$")
    private val ONLINE_RX = Regex("^(?:demande|pose la question|pose-la)(?: (?:en ligne|a l'ia en ligne|a groq|a gemini|a openrouter|au cloud|sur le cloud))\\s*[:,]?\\s*(.+)$|^en ligne\\s*[:,]\\s*(.+)$")

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
        if (ABOUT_ME_RX.matches(f)) return Hit(ABOUT_ME)
        REMEMBER_RX.matchEntire(f)?.let { m -> return Hit(REMEMBER, original(raw, full, p + lead, m.groups[1]!!.range.first, m.groupValues[1])) }
        FORGET_RX.matchEntire(f)?.let { m -> return Hit(FORGET, original(raw, full, p + lead, m.groups[1]!!.range.first, m.groupValues[1])) }
        ONLINE_RX.matchEntire(f)?.let { m ->
            val g = m.groups[1] ?: m.groups[2]!!
            return Hit(ASK_ONLINE, original(raw, full, p + lead, g.range.first, g.value))
        }
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

    private val UNSURE = Regex("\\b(?:je ne sais pas|je n'en sais rien|je ne connais pas|je n'ai pas (?:acces|d'acces|l'information|d'information|de donnees|les informations|cette information)|" +
        "je ne (?:peux|suis) pas (?:en mesure de |capable de )?(?:acceder|savoir|verifier|naviguer|consulter|te donner (?:l'|les |des )?(?:info|actu|donnees))|" +
        "mes connaissances (?:s'arretent|sont limitees|ne vont pas)|pas en mesure de (?:te )?(?:dire|repondre|savoir)|aucune information (?:sur|a ce sujet)|" +
        "je n'ai pas de (?:moyen|connexion)|je ne dispose pas)")

    /** La réponse du modèle local avoue ne pas savoir : on peut passer la main à une IA en ligne. */
    fun uncertain(answer: String): Boolean = answer.length < 900 && UNSURE.containsMatchIn(Text.fold(answer.replace('’', '\'')))

    private val HINTS = listOf(
        "agenda" to Regex("\\b(?:rendez-vous|rdv|agenda|calendrier|planning|evenement)s?\\b"),
        "contacter" to Regex("\\b(?:appelle|telephone a|ecris a|envoie (?:un )?(?:message|sms|texto)|sms|texto)\\b"),
        "itineraire" to Regex("\\b(?:itineraire|comment aller|trajet|emmene-moi|route (?:pour|vers)|guide-moi)\\b"),
        "rappel" to Regex("\\b(?:rappelle-moi|rappel)\\b"),
        "horloge" to Regex("\\b(?:minuteur|reveil|alarme|chrono|compte a rebours)\\b"),
        "notes" to Regex("\\b(?:ma liste|liste de|listes)\\b"),
        "musique" to Regex("\\b(?:musique|chanson|morceau|playlist|album)\\b"),
        "notifications" to Regex("\\b(?:notifications?|qui m'a ecrit|messages? recus?|nouveaux messages)\\b"),
        "ecran" to Regex("\\b(?:sur l'ecran|a l'ecran|clique|appuie sur|fais defiler|tape sur)\\b"),
        "partager" to Regex("\\b(?:partage|partager)\\b"),
    )

    /** Outil le plus probable pour un message (indice glissé au petit modèle), ou null. */
    fun toolHint(text: String): String? {
        val f = Text.fold(text.replace('’', '\''))
        val found = HINTS.filter { it.second.containsMatchIn(f) }.map { it.first }
        return found.singleOrNull()
    }

    private val LIBRARY = Regex("\\b(?:(?:regarde|cherche|recherche|trouve|verifie|consulte|lis)\\s+)?(?:dans|d'apres|selon|avec|sur)\\s+(?:mes|ma|la|les|tes|ta)\\s+(?:fiches?|bibliotheque|documents?|base de connaissances|connaissances|memoire|base)\\b[ ,:]*")
    private val LIBRARY_VERB = Regex("^(?:nox[ ,]+)?(?:est-ce que tu peux |tu peux |peux-tu )?(?:regarde|cherche|recherche|trouve|verifie|consulte|lis)\\s+(?:moi\\s+)?")

    /**
     * Demande explicite de consulter les fiches (« regarde dans mes fiches … », « d'après ma bibliothèque, … »).
     * Renvoie la question nettoyée à chercher, ou null.
     */
    fun libraryQuery(text: String): String? {
        val f = Text.fold(text.replace('’', '\'')).trim().trimEnd('.', '!', '?', '…')
        if (!LIBRARY.containsMatchIn(f)) return null
        val q = LIBRARY_VERB.replace(LIBRARY.replace(f, " ").trim(), "").trim()
            .replace(Regex("^(?:ce que|ce qu'|si |des infos sur |des informations sur |a propos de |au sujet de |sur )"), "")
            .replace(Regex("\\s+"), " ").trim(' ', ',', ':', '-')
        return q.ifBlank { null }
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
