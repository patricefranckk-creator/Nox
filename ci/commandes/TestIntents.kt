package com.application.nox

/** Phrases spéciales (point du matin, écran, interprète, notes, pages) : nombre d'erreurs. */
fun testIntents(): Int {
    val cases = listOf(
        Triple("Bonjour Nox", 8, Intents.BRIEFING), Triple("Bonjour", 9, Intents.BRIEFING), Triple("Bonjour", 15, null),
        Triple("Fais-moi le point du matin", 18, Intents.BRIEFING), Triple("Nox, mon briefing", 7, Intents.BRIEFING),
        Triple("Bonjour, quelle est la capitale du Pérou ?", 8, null),
        Triple("Résume mon écran", 10, Intents.SCREEN_SUMMARY), Triple("Nox, lis-moi l'écran", 10, Intents.SCREEN_SUMMARY),
        Triple("Propose-moi une réponse", 10, Intents.SCREEN_REPLY), Triple("Qu'est-ce que je peux répondre ?", 10, Intents.SCREEN_REPLY),
        Triple("Mode interprète anglais", 10, Intents.INTERPRETER), Triple("Active le mode interprète en espagnol", 10, Intents.INTERPRETER),
        Triple("Mode interprète", 10, Intents.INTERPRETER), Triple("Fin du mode interprète", 10, Intents.INTERPRETER_END),
        Triple("Note : acheter des piles pour la télécommande", 10, Intents.NOTE),
        Triple("Nox, prends une note : le code du portail est changé", 10, Intents.NOTE),
        Triple("Qu'est-ce que j'ai noté sur le portail ?", 10, Intents.NOTES_FIND), Triple("Mes notes", 10, Intents.NOTES_FIND),
        Triple("Retiens cette page", 10, Intents.KEEP_PAGE), Triple("Garde ces pages dans la bibliothèque", 10, Intents.KEEP_PAGE),
        Triple("Quelle note a eu mon fils ?", 10, null), Triple("Raconte-moi une blague", 10, null),
        Triple("Ajoute du lait à la liste de courses", 10, null),
    )
    var bad = 0
    for ((text, hour, want) in cases) {
        val got = Intents.detect(text, hour)
        val ok = got?.kind == want
        if (!ok) bad++
        println("${if (ok) "✓" else "✗"} %-58s → %s".format("$text (${hour}h)", got?.let { "${it.kind} « ${it.arg} »" } ?: "rien"))
    }
    val n = Intents.detect("Nox, prends une note : le code du portail est changé", 10)?.arg
    if (n != "le code du portail est changé") { bad++; println("✗ texte de note : « $n »") }
    val l = Intents.detect("Mode interprète espagnol", 10)?.arg
    if (l != "espagnol") { bad++; println("✗ langue : « $l »") }
    val i = Intents.detect("stop", 10, interpreting = true)?.kind
    if (i != Intents.INTERPRETER_END) { bad++; println("✗ arrêt interprète : $i") }
    if (Intents.cleanNote("euh acheter du pain du coup") != "Acheter du pain.") { bad++; println("✗ nettoyage : « ${Intents.cleanNote("euh acheter du pain du coup")} »") }
    println("Phrases spéciales : ${cases.size + 4 - bad}/${cases.size + 4} correctes")
    return bad
}
