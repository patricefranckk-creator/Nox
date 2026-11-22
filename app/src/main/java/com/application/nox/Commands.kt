package com.application.nox

import org.json.JSONObject

/**
 * Commandes courantes reconnues sans le modèle : minuteur, alarme, rappel, lampe, volume, état du
 * téléphone, ouvrir une appli, listes, calcul, musique. Exécution immédiate et fiable par les mêmes
 * outils que Gemma ; tout ce qui est ambigu ou en plusieurs étapes (« ouvre WhatsApp et écris à… »)
 * reste pour le modèle.
 */
object Commands {

    data class Call(val skill: String, val args: JSONObject, val label: String)

    // ------------------------------------------------------------------ nombres et durées

    private val WORDS = mapOf(
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7,
        "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14,
        "quinze" to 15, "seize" to 16, "vingt" to 20, "vingt-cinq" to 25, "trente" to 30, "quarante" to 40,
        "quarante-cinq" to 45, "cinquante" to 50, "soixante" to 60, "quatre-vingt-dix" to 90,
    )
    private const val NUM = "(\\d+(?:[.,]\\d+)?|vingt-cinq|quarante-cinq|quatre-vingt-dix|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente|quarante|cinquante|soixante)"
    private const val UNIT = "(heures?|h|minutes?|mins?|mn|secondes?|secs?|s)"
    private val DURATION = Regex("$NUM\\s*$UNIT(?![a-z])(?:\\s*(?:et\\s*)?$NUM\\s*$UNIT?(?![a-z]))?(?:\\s*et\\s+demie?)?|(?:une\\s+)?demi[- ]heure|(?:un\\s+)?quart\\s+d'heure|trois\\s+quarts?\\s+d'heure")

    private fun num(s: String): Double? = s.replace(',', '.').toDoubleOrNull() ?: WORDS[s]?.toDouble()

    private fun unitSeconds(u: String?): Int = when {
        u == null -> 60
        u.startsWith("h") -> 3600
        u.startsWith("m") -> 60
        else -> 1
    }

    /** « 1 h 30 », « 5 minutes », « 2 minutes et 30 secondes », « une demi-heure »… → secondes. */
    fun seconds(text: String): Int? {
        val m = DURATION.find(text) ?: return null
        val s = m.value
        when {
            s.contains("demi") && !s.contains("et demi") -> return 1800
            s.startsWith("trois") && s.contains("quart") -> return 2700
            s.contains("quart") -> return 900
        }
        val (n1, u1, n2, u2) = m.destructured
        val first = num(n1) ?: return null
        val s1 = unitSeconds(u1)
        var total = first * s1
        if (n2.isNotEmpty()) {
            val second = num(n2) ?: 0.0
            total += second * if (u2.isNotEmpty()) unitSeconds(u2) else (s1 / 60).coerceAtLeast(1)
        }
        if (s.contains("et demi")) total += s1 / 2.0
        return total.toInt().takeIf { it in 1..86_400 }
    }

    private val CLOCK = Regex("\\b(\\d{1,2})\\s*(?:h|heures?|:)\\s*(\\d{2})?\\b|\\b(midi|minuit)\\b")

    private fun clockOf(text: String): Pair<Int, Int>? {
        val m = CLOCK.find(text) ?: return null
        return when (m.groupValues[3]) {
            "midi" -> 12 to 0
            "minuit" -> 0 to 0
            else -> {
                val h = m.groupValues[1].toIntOrNull() ?: return null
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h in 0..23 && min in 0..59) h to min else null
            }
        }
    }

    // ------------------------------------------------------------------ reconnaissance

    private val POLITE = Regex("^(?:(?:hey |ok |dis )?nox[ ,]+)?(?:est-ce que tu peux |est-ce que tu pourrais |tu peux |tu pourrais |peux-tu |pourrais-tu |je veux que tu |j'aimerais que tu |s'il te plait,? )?")
    private val TAIL = Regex("[ ,]*(?:s'il te plait|stp|merci)?[ .!?…]*$")
    private val MULTI = Regex("\\b(?:et|puis|ensuite|apres|avant|pour lui|pour elle|a qui|a quelqu'un)\\b")

    /** Commande directe reconnue, ou null pour laisser le modèle répondre. */
    fun match(message: String): Call? {
        val original = message.trim()
        val f0 = Text.fold(original).replace('’', '\'').replace(Regex("\\s+"), " ")
        val pre = POLITE.find(f0)?.value?.length ?: 0
        val f = TAIL.replace(f0.substring(pre), "").trim()
        if (f.isEmpty() || f.length > 120) return null
        // Texte d'origine (accents, majuscules) aligné sur la version normalisée
        val orig = original.replace('’', '\'').replace(Regex("\\s+"), " ").let { o ->
            if (o.length == f0.length) o.substring(pre, (pre + f.length).coerceAtMost(o.length)) else f
        }
        fun keep(range: IntRange) = orig.substring(range.first, (range.last + 1).coerceAtMost(orig.length)).trim()

        timer(f)?.let { return it }
        alarm(f)?.let { return it }
        reminder(f, ::keep)?.let { return it }
        lamp(f)?.let { return it }
        volume(f)?.let { return it }
        battery(f)?.let { return it }
        lists(f, ::keep)?.let { return it }
        calc(f)?.let { return it }
        music(f, ::keep)?.let { return it }
        open(f, ::keep)?.let { return it }
        return null
    }

    private fun timer(f: String): Call? {
        if (!Regex("\\b(?:minuteur|timer|chrono|compte a rebours)\\b").containsMatchIn(f)) return null
        if (Regex("\\b(?:annule|arrete|stoppe|supprime|combien|reste)\\b").containsMatchIn(f)) return null
        val s = seconds(f) ?: return null
        return Call("horloge", JSONObject().put("action", "minuteur").put("secondes", s), "minuteur")
    }

    private fun alarm(f: String): Call? {
        if (!Regex("\\b(?:alarme|reveil|reveille[- ]moi|reveiller)\\b").containsMatchIn(f)) return null
        if (Regex("\\b(?:annule|supprime|desactive|enleve)\\b").containsMatchIn(f)) return null
        val (h, m) = clockOf(f) ?: return null
        val hour = if (Regex("\\b(?:du soir|de l'apres-midi|cet apres-midi|ce soir)\\b").containsMatchIn(f) && h in 1..11) h + 12 else h
        return Call("horloge", JSONObject().put("action", "alarme").put("heure", hour).put("minute", m), "alarme")
    }

    private val REMIND = Regex("^(?:rappelle[- ]moi|fais[- ]moi penser|pense a me rappeler)\\b\\s*(.*)$")

    private fun reminder(f: String, keep: (IntRange) -> String): Call? {
        val m = REMIND.find(f) ?: return null
        val rest = m.groups[1] ?: return null
        val body = rest.value
        val off = rest.range.first
        val inM = Regex("\\bdans\\s+(?:$DURATION)").find(body)
        val atM = Regex("\\b(?:a|vers|pour)\\s+(?:\\d{1,2}\\s*(?:h|heures?|:)\\s*(?:\\d{2})?|midi|minuit)\\b").find(body)
        if (Regex("\\b(?:demain|apres-demain|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|semaine|mois|le \\d{1,2}\\b)").containsMatchIn(body)) return null
        val whenM = inM ?: atM ?: return null
        // Le texte du rappel = ce qui reste une fois l'échéance retirée
        val what = (keep(off until off + whenM.range.first) + " " + keep(off + whenM.range.last + 1 until off + body.length))
            .trim().replace(Regex("^(?:de |d'|qu'|que |a |pour )", RegexOption.IGNORE_CASE), "").trim().trimEnd('.', ',')
        if (what.length < 2) return null
        val args = JSONObject().put("texte", what)
        if (inM != null) {
            val s = seconds(inM.value) ?: return null
            args.put("dans_minutes", ((s + 59) / 60).coerceAtLeast(1))
        } else {
            val (h, min) = clockOf(atM!!.value) ?: return null
            val hour = if (Regex("\\b(?:du soir|ce soir|de l'apres-midi)\\b").containsMatchIn(f) && h in 1..11) h + 12 else h
            args.put("heure", hour).put("minute", min)
        }
        return Call("rappel", args, "rappel")
    }

    private fun lamp(f: String): Call? {
        val m = Regex("^(allume|allumer|active|activer|mets|mettre|eteins|eteint|eteindre|coupe|couper|desactive|desactiver|arrete|arreter)\\b.*\\b(?:lampe|torche|flash)\\b").find(f) ?: return null
        val on = m.groupValues[1] in setOf("allume", "allumer", "active", "activer", "mets", "mettre")
        return Call("telephone", JSONObject().put("action", if (on) "lampe_allumer" else "lampe_eteindre"), "lampe")
    }

    private fun volume(f: String): Call? {
        if (!Regex("^(?:(?:mets|met|regle|monte|baisse|passe|coupe)\\s+)?(?:le |du )?(?:volume|son)\\b").containsMatchIn(f) || Regex("\\b(?:sonnerie|alarme|notification)\\b").containsMatchIn(f)) return null
        val v = when {
            Regex("\\b(?:au max|au maximum|a fond|a 100)\\b").containsMatchIn(f) -> 100
            Regex("\\b(?:coupe le son|mets le son a zero|volume a zero|silence|mute)\\b").containsMatchIn(f) -> 0
            else -> Regex("\\b(?:volume|son)\\b\\D{0,12}(\\d{1,3})\\s*(?:%|pour ?cent)?").find(f)?.groupValues?.get(1)?.toIntOrNull()
        } ?: return null
        return Call("telephone", JSONObject().put("action", "volume").put("valeur", v.coerceIn(0, 100)), "volume")
    }

    private fun battery(f: String): Call? {
        if (!Regex("^(?:(?:quel est |c'est quoi |donne[- ]moi )?(?:le )?niveau de (?:la )?batterie|combien (?:de|il me reste de|reste-t-il de) batterie|(?:ma |la )?batterie|il me reste combien de batterie|etat du telephone)$").matches(f)) return null
        return Call("telephone", JSONObject().put("action", "etat"), "état")
    }

    private val LIST_NAME = "(?:la |ma |sur la |sur ma |dans la |dans ma )?liste(?: (?:de |des |d'|du ))?([\\w' -]{2,30})?"

    private fun lists(f: String, keep: (IntRange) -> String): Call? {
        Regex("^(?:ajoute|ajouter|rajoute|rajouter|mets|mettre|note|noter)\\s+(.+?)\\s+(?:a|sur|dans)\\s+$LIST_NAME$").find(f)?.let { m ->
            val item = m.groups[1]!!.range
            val list = m.groups[2]?.value?.trim().orEmpty().ifEmpty { "courses" }
            return Call("notes", JSONObject().put("action", "ajouter").put("liste", list).put("texte", items(keep(item))), "liste")
        }
        Regex("^(?:retire|retirer|enleve|enlever|supprime|supprimer|efface|effacer|barre|barrer)\\s+(.+?)\\s+(?:de|du|des)\\s+$LIST_NAME$").find(f)?.let { m ->
            val list = m.groups[2]?.value?.trim().orEmpty().ifEmpty { "courses" }
            return Call("notes", JSONObject().put("action", "retirer").put("liste", list).put("texte", items(keep(m.groups[1]!!.range))), "liste")
        }
        Regex("^(?:vide|efface|supprime)\\s+$LIST_NAME$").find(f)?.let { m ->
            val list = m.groups[1]?.value?.trim() ?: return null
            return Call("notes", JSONObject().put("action", "vider").put("liste", list), "liste")
        }
        Regex("^(?:lis|lis-moi|relis|donne-moi|montre-moi|montre|dis-moi ce qu'il y a (?:sur|dans)|qu'est-ce qu'il y a (?:sur|dans)|c'est quoi)\\s+$LIST_NAME$").find(f)?.let { m ->
            val list = m.groups[1]?.value?.trim().orEmpty().ifEmpty { "courses" }
            return Call("notes", JSONObject().put("action", "lire").put("liste", list), "liste")
        }
        return null
    }

    /** « des tomates et du pain » → « tomates, pain » : sans articles, un élément par virgule. */
    private fun items(raw: String): String = raw.split(Regex("\\s*[,;]\\s*|\\s+et\\s+"))
        .map { it.trim().replace(Regex("^(?:du |de la |de l'|des |un |une |le |la |les |l'|d')", RegexOption.IGNORE_CASE), "").trim() }
        .filter { it.isNotEmpty() }.joinToString(", ")

    private fun calc(f: String): Call? {
        val m = Regex("^(?:combien (?:font|fait|ca fait)|calcule|c'est combien|ca fait combien)\\s+(.+)$").find(f) ?: return null
        val expr = m.groupValues[1]
            .replace(Regex("\\bdivise(?:e)? par\\b"), "/").replace(Regex("\\bfois\\b"), "*").replace(Regex("\\bmultiplie(?:e)? par\\b"), "*")
            .replace(Regex("\\bplus\\b"), "+").replace(Regex("\\bmoins\\b"), "-").replace(Regex("\\bau carre\\b"), "^2")
            .replace(Regex("\\bracine (?:carree )?de\\s+(\\d+(?:[.,]\\d+)?)"), "sqrt($1)")
        if (!expr.any { it.isDigit() } || expr.any { it.isLetter() && it !in "sqrtx%de" }) return null
        val ok = try { !MathEval.eval(expr).isNaN() } catch (_: Throwable) { false }
        return if (ok) Call("calculer", JSONObject().put("expression", expr), "calcul") else null
    }

    private fun music(f: String, keep: (IntRange) -> String): Call? {
        val action = when {
            Regex("^(?:mets |met )?(?:la musique |le son )?(?:en )?pause(?: la musique)?$").matches(f) -> "pause"
            Regex("^(?:remets|reprends|relance) (?:la musique|la lecture|le son)$").matches(f) -> "reprendre"
            Regex("^(?:musique|chanson|morceau|titre|piste) suivante?$|^(?:passe a la |mets la )?(?:chanson|musique|piste) suivante$|^suivant$").matches(f) -> "suivant"
            Regex("^(?:musique|chanson|morceau|titre|piste) precedente?$|^(?:reviens a la |mets la )?(?:chanson|musique|piste) precedente$|^precedent$").matches(f) -> "precedent"
            else -> null
        }
        if (action != null) return Call("musique", JSONObject().put("action", action), "musique")
        val play = Regex("^(?:joue|jouer|mets|mettre|lance|lancer|ecoute|fais-moi ecouter|me faire ecouter)(?:-moi)?\\s+(?:moi\\s+)?(?:de la musique|la musique|du son)?\\s*(?:de |d')?(.*)$").find(f) ?: return null
        if (!Regex("^(?:joue|jouer|ecoute|fais-moi ecouter|me faire ecouter)\\b|\\bmusique\\b|\\bchanson\\b|\\balbum\\b|\\bplaylist\\b").containsMatchIn(f)) return null
        val g = play.groups[1]
        val q = if (g == null || g.value.isBlank()) "" else keep(g.range).replace(Regex("^(?:la chanson|l'album|la playlist|le titre)\\s+", RegexOption.IGNORE_CASE), "")
        return Call("musique", JSONObject().put("action", "jouer").put("recherche", q), "musique")
    }

    private fun open(f: String, keep: (IntRange) -> String): Call? {
        val m = Regex("^(?:ouvre|ouvrir|lance|lancer|demarre|demarrer|affiche|afficher)(?:-moi)?\\s+(?:moi\\s+)?(?:l'appli(?:cation)?\\s+|l'|la |le |les |mes |mon |ma )?(.+)$").find(f) ?: return null
        val target = m.groups[1]!!
        if (Regex("^(?:un|une|des|du)\\b|\\b(?:minuteur|alarme|rappel|recherche)\\b").containsMatchIn(target.value)) return null
        if (MULTI.containsMatchIn(target.value) || target.value.split(' ').size > 3) return null
        return Call("ouvrir", JSONObject().put("cible", keep(target.range)), "ouvrir")
    }
}
