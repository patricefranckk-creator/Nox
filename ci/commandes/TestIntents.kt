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
        Triple("Retiens que je travaille à l'IME de Bizanos", 10, Intents.REMEMBER), Triple("Souviens-toi que ma voiture est grise", 10, Intents.REMEMBER),
        Triple("Oublie que ma voiture est grise", 10, Intents.FORGET), Triple("Qu'est-ce que tu sais sur moi ?", 10, Intents.ABOUT_ME),
        Triple("Demande en ligne : qui a gagné le match hier ?", 10, Intents.ASK_ONLINE), Triple("En ligne : explique la blockchain", 10, Intents.ASK_ONLINE),
        Triple("Rappelle-moi dans 10 minutes d'appeler Paul", 10, null),
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
    val r = Intents.detect("Retiens que je travaille à l'IME de Bizanos", 10)?.arg
    if (r != "je travaille à l'IME de Bizanos") { bad++; println("✗ souvenir : « $r »") }
    val unsure = listOf("Je ne sais pas qui a gagné ce match." to true, "Désolé, je n'ai pas accès à internet pour vérifier." to true,
        "La capitale de la France est Paris." to false, "Mes connaissances s'arrêtent en 2024." to true)
    for ((t, want) in unsure) if (Intents.uncertain(t) != want) { bad++; println("✗ incertitude « $t » → ${!want}") }
    val hints = listOf("Qu'est-ce que j'ai comme rendez-vous jeudi ?" to "agenda", "Envoie un message à Julie pour lui dire que j'arrive" to "contacter",
        "Raconte-moi une histoire" to null, "Comment aller à Lourdes ?" to "itineraire")
    for ((t, want) in hints) if (Intents.toolHint(t) != want) { bad++; println("✗ indice d'outil « $t » → ${Intents.toolHint(t)} (attendu $want)") }
    val chains = listOf("Mets un minuteur de 5 minutes et allume la lampe" to 2, "Ajoute du pain et du lait à la liste de courses" to 0,
        "Ouvre WhatsApp et écris à Paul" to 0, "Allume la lampe, puis mets le volume à 50" to 2)
    for ((t, want) in chains) {
        val n = Commands.matchAll(t)?.size ?: 0
        if (n != want) { bad++; println("✗ enchaînement « $t » → $n commande(s) (attendu $want)") } else println("✓ enchaînement « $t » → $n")
    }
    val emos = listOf("Bonne nouvelle : il fera beau demain !" to Emotion.JOIE, "Je suis désolé que ta journée ait été difficile." to Emotion.EMPATHIE,
        "Attention : appelle le 15 si la brûlure est grave." to Emotion.SERIEUX, "Ah bon ? Je ne savais pas !" to Emotion.SURPRISE,
        "Tu veux que je l'ajoute à l'agenda ?" to Emotion.QUESTION, "Le rendez-vous est noté pour jeudi." to Emotion.NEUTRE,
        "Ce n'est pas super, mais ça ira." to Emotion.NEUTRE)
    for ((t, want) in emos) if (Emotion.of(t) != want) { bad++; println("✗ émotion « $t » → ${Emotion.of(t)} (attendu $want)") } else println("✓ émotion « $t » → $want")
    val libs = listOf("Regarde dans mes fiches comment faire la position latérale de sécurité" to "comment faire la position laterale de securite",
        "D'après ma bibliothèque, c'est quoi le TDAH ?" to "c'est quoi le tdah", "Cherche dans mes notes le code du portail" to null,
        "Raconte-moi une blague" to null, "Cherche dans ta mémoire des infos sur l'univers" to "l'univers", "Qu'est-ce qu'il y a sur mes documents à propos du solaire" to "qu'est-ce qu'il y a a propos du solaire")
    for ((t, want) in libs) { val got = Intents.libraryQuery(t)
        if (got != want) { bad++; println("✗ fiches « $t » → $got (attendu $want)") } else println("✓ fiches « $t » → $got") }
    for ((t, want) in listOf("Juste une bonne description de la photo" to true, "Dis-m'en plus sur l'étiquette" to true,
        "Qu'est-ce qu'il y a écrit dessus ?" to true, "Quelle heure est-il ?" to false, "Mets un minuteur de 5 minutes" to false)) {
        val got = Intents.aboutPhoto(t)
        if (got != want) { bad++; println("✗ photo « $t » → $got") } else println("✓ photo « $t » → $got")
    }
    println("Phrases spéciales : ${cases.size + 4 - bad}/${cases.size + 4} correctes (+ souvenirs, incertitudes, indices, enchaînements)")
    return bad
}
