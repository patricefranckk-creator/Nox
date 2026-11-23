package com.application.nox

/** Vérifié à chaque build (CI) : chaque phrase doit déclencher l'outil attendu, ou rester pour le modèle (null). */
private val TEXTS = mapOf(
    "Ajoute des tomates et du pain sur ma liste de courses" to "tomates, pain",
    "Retire le lait de la liste de courses" to "lait",
    "Rappelle-moi d'appeler Maman à 18h" to "appeler Maman",
)

fun main() {
    val cases = listOf(
        "Mets un minuteur de 5 minutes" to "horloge", "Nox, minuteur 1 h 30" to "horloge", "minuteur 1h30" to "horloge",
        "lance un minuteur d'une demi-heure" to "horloge", "Mets un minuteur de 2 minutes et 30 secondes" to "horloge",
        "Mets un minuteur" to null, "Combien de temps reste-t-il sur le minuteur ?" to null,
        "Réveille-moi à 7h30" to "horloge", "Mets une alarme à 6 h" to "horloge", "mets une alarme à 7h ce soir" to "horloge",
        "Mets une alarme dans 10 minutes" to null,
        "Rappelle-moi dans 20 minutes de sortir le linge" to "rappel", "Rappelle-moi d'appeler Maman à 18h" to "rappel",
        "Rappelle-moi demain à 9h d'aller chez le médecin" to null,
        "Allume la lampe torche" to "telephone", "Éteins la lampe" to "telephone", "Mets le volume à 50" to "telephone",
        "Monte le son à 80 %" to "telephone", "Baisse le son" to null, "Niveau de batterie" to "telephone",
        "Il me reste combien de batterie ?" to "telephone",
        "Ajoute du lait à la liste de courses" to "notes", "Ajoute des tomates et du pain sur ma liste de courses" to "notes",
        "Qu'est-ce qu'il y a sur ma liste de courses ?" to "notes", "Retire le lait de la liste de courses" to "notes",
        "Vide la liste de courses" to "notes",
        "Combien font 12 fois 7 ?" to "calculer", "Calcule 20% de 150" to "calculer", "Combien font les impôts ?" to null,
        "Pause" to "musique", "Mets la musique en pause" to "musique", "Chanson suivante" to "musique",
        "Joue Daft Punk" to "musique", "Lance la musique" to "musique", "Joue-moi l'album Discovery" to "musique",
        "Ouvre WhatsApp" to "ouvrir", "Lance YouTube" to "ouvrir", "Ouvre WhatsApp et envoie un message à Paul" to null,
        "Ouvre les paramètres wifi" to "ouvrir", "Tu peux ouvrir Chrome s'il te plaît ?" to "ouvrir",
        "Peux-tu ouvrir Chrome s'il te plaît ?" to "ouvrir", "Tu peux mettre un minuteur de 10 minutes" to "horloge", "Ouvre Chrome s'il te plaît" to "ouvrir",
        "Quelle est la capitale de la France ?" to null, "Raconte-moi une blague" to null,
        "Mon fils a son match à 15h" to null, "Je dois appeler le médecin" to null,
    )
    var bad = 0
    for ((text, want) in cases) {
        val got = Commands.match(text)
        val ok = got?.skill == want && (text !in TEXTS || got?.args?.optString("texte") == TEXTS[text])
        if (!ok) bad++
        println("${if (ok) "✓" else "✗"} %-58s → %s".format(text, got?.let { "${it.skill} ${it.args}" } ?: "modèle"))
    }
    println("${cases.size - bad}/${cases.size} corrects")
    bad += testIntents()
    if (bad > 0) kotlin.system.exitProcess(1)
}
