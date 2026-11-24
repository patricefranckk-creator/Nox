package com.application.nox

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Les compétences de Nox : de vrais outils appelés par Gemma (function calling), 100 % locaux.
 * Peu d'outils mais polyvalents (paramètre « action ») : moins de texte à pré-calculer au démarrage,
 * et un petit modèle choisit mieux parmi 16 outils clairs que parmi 30.
 */
class Skills(private val ctx: Context, private val library: Library) {

    /** Puce affichée dans le chat quand une action est faite. */
    var onUsed: ((label: String) -> Unit)? = null
    /** Sources d'une réponse (titre + séparateur + lien), affichées sous la réponse en cours. */
    var onSources: ((List<String>) -> Unit)? = null
    /** Demande d'autorisation Android (contacts…) déléguée à l'activité. */
    var askPermission: ((String) -> Unit)? = null
    /** Les échanges plus récents que ça sont déjà dans le contexte : inutile de les rechercher. */
    @Volatile var conversationStart = System.currentTimeMillis()
    /** Nombre d'outils appelés pendant le tour en cours (journal). */
    val callsThisTurn = java.util.concurrent.atomic.AtomicInteger(0)

    /** Banc d'essai : les outils sont notés mais pas exécutés (sauf ceux sans effet : calcul, heure). */
    @Volatile var dryRun = false
    val dryCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val SAFE_IN_TEST = setOf("calculer", "heure_date")

    private val main = Handler(Looper.getMainLooper())
    val memory = LongMemory(ctx)
    private val notesFile = File(ctx.filesDir, "notes.json")

    data class Skill(val name: String, val title: String, val description: String, val params: JSONObject, val run: (JSONObject) -> String)

    // ================================================================ catalogue

    val all: List<Skill> = listOf(
        Skill("heure_date", "Heure et date", "Heure et date.", obj()) {
            used("🕒 heure")
            SimpleDateFormat("EEEE d MMMM yyyy, HH'h'mm", Locale.FRANCE).format(Date())
        },

        Skill("calculer", "Calculatrice", "Calcul exact (+ - * / ^ % sqrt).",
            obj("expression" to str(""))) { p ->
            val e = p.optString("expression")
            used("🧮 $e")
            MathEval.format(MathEval.eval(e))
        },

        Skill("horloge", "Minuteur & réveil", "Minuteur ou alarme.",
            obj("action" to enum("minuteur", "alarme"), "secondes" to int(""),
                "heure" to int("0-23"), "minute" to int("0-59"), "nom" to str(""),
                required = listOf("action"))) { p ->
            if (p.optString("action") == "minuteur") {
                val s = p.optInt("secondes", 0)
                require(s in 1..86_400) { "durée invalide" }
                start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, p.optString("nom", "Nox")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                used("⏱ ${human(s)}")
                "Minuteur de ${human(s)} lancé."
            } else {
                val h = p.optInt("heure", -1); val m = p.optInt("minute", 0)
                require(h in 0..23 && m in 0..59) { "heure invalide" }
                start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, p.optString("nom", "Nox")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                used(hm("⏰ ", h, m))
                hm("Alarme réglée à ", h, m) + "."
            }
        },

        Skill("rappel", "Rappels", "Rappel à une heure ou dans X minutes.",
            obj("texte" to str(""), "dans_minutes" to int(""),
                "heure" to int("0-23"), "minute" to int("minute 0-59"), required = listOf("texte"))) { p ->
            val txt = p.optString("texte").trim()
            require(txt.isNotEmpty()) { "texte vide" }
            val at: Long = if (p.has("heure")) {
                val c = Calendar.getInstance()
                c.set(Calendar.HOUR_OF_DAY, p.optInt("heure")); c.set(Calendar.MINUTE, p.optInt("minute", 0)); c.set(Calendar.SECOND, 0)
                if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
                c.timeInMillis
            } else System.currentTimeMillis() + p.optInt("dans_minutes", 0).coerceAtLeast(1) * 60_000L
            ReminderReceiver.schedule(ctx, at, txt)
            val label = SimpleDateFormat("EEEE HH'h'mm", Locale.FRANCE).format(Date(at))
            used("🔔 $label")
            "Rappel programmé pour $label : $txt"
        },

        Skill("notes", "Notes & listes", "Listes (courses, à faire…).",
            obj("action" to enum("ajouter", "lire", "retirer", "vider"), "liste" to str("ex: courses"),
                "texte" to str(""), required = listOf("action"))) { p ->
            val list = p.optString("liste").lowercase(Locale.FRANCE).trim().ifEmpty { "notes" }
            val all = loadNotes()
            val arr = all.optJSONArray(list) ?: JSONArray()
            val items = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
            val txt = p.optString("texte").trim()
            val res = when (p.optString("action")) {
                "ajouter" -> {
                    require(txt.isNotEmpty()) { "texte vide" }
                    txt.split(Regex("\\s*[,;]\\s*|\\s+et\\s+")).filter { it.isNotBlank() }
                        .forEach { if (items.none { i -> i.equals(it, true) }) items += it }
                    used("📝 $list"); "Ajouté à « $list » (${items.size} élément(s))."
                }
                "retirer" -> {
                    val before = items.size
                    items.removeAll { Text.fold(it).contains(Text.fold(txt)) }
                    used("📝 $list"); if (items.size < before) "Retiré de « $list »." else "Rien de tel dans « $list »."
                }
                "vider" -> { items.clear(); used("📝 $list vidée"); "Liste « $list » vidée." }
                else -> {
                    used("📝 lecture")
                    if (p.optString("liste").isBlank() && all.length() > 0)
                        all.keys().asSequence().joinToString(" | ") { k ->
                            "$k : " + all.getJSONArray(k).let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }
                        }
                    else if (items.isEmpty()) "La liste « $list » est vide." else "« $list » : " + items.joinToString(", ")
                }
            }
            if (items.isEmpty()) all.remove(list) else all.put(list, JSONArray(items))
            notesFile.writeText(all.toString())
            library.syncNotes(list, items)
            res
        },

        Skill("memoire", "Mémoire longue", "Retenir ou oublier un fait durable sur l'utilisateur.",
            obj("action" to enum("retenir", "oublier"), "texte" to str(""))) { p ->
            val t = p.optString("texte").trim()
            require(t.isNotEmpty()) { "texte vide" }
            if (p.optString("action") == "oublier") {
                val n = memory.forget(t); used("🧠 oubli")
                if (n == 0) "Aucun souvenir ne correspond." else "$n souvenir(s) effacé(s)."
            } else { memory.add(t); used("🧠 retenu"); "Retenu : $t" }
        },

        Skill("bibliotheque", "Bibliothèque", "Cherche dans ses documents, fiches et anciennes conversations.",
            obj("requete" to str(""))) { p ->
            val hits = library.search(p.optString("requete"), k = 4, excludeChatsAfter = conversationStart)
            used("📚 ${hits.size} extrait(s)")
            if (hits.isEmpty()) "Rien trouvé dans la bibliothèque." else hits.joinToString("\n") { "• ${it.chunk.title} : ${it.chunk.text}" }.take(2400)
        },

        Skill("musique", "Musique", "Musique.",
            obj("action" to enum("jouer", "pause", "reprendre", "suivant", "precedent"), "recherche" to str(""),
                required = listOf("action"))) { p ->
            when (p.optString("action")) {
                "jouer" -> {
                    val q = p.optString("recherche").trim()
                    if (q.isEmpty()) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); used("▶ lecture"); "Lecture." } else {
                        start(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                            .putExtra(android.app.SearchManager.QUERY, q))
                        used("🎵 $q"); "Je lance « $q »."
                    }
                }
                "pause" -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); used("⏸ pause"); "Pause." }
                "reprendre" -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); used("▶ lecture"); "C'est reparti." }
                "suivant" -> { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); used("⏭ suivant"); "Morceau suivant." }
                else -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); used("⏮ précédent"); "Morceau précédent." }
            }
        },

        Skill("contacter", "Appels & SMS", "Appel ou SMS.",
            obj("mode" to enum("appel", "sms"), "destinataire" to str(""), "message" to str(""),
                required = listOf("mode", "destinataire"))) { p ->
            val who = p.optString("destinataire").trim()
            val number = if (who.count { it.isDigit() } >= 6) who else lookupNumber(who) ?: return@Skill missingContact(who)
            if (p.optString("mode") == "sms") {
                start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", p.optString("message")))
                used("💬 $who"); "Le SMS pour $who est prêt, il ne reste qu'à l'envoyer."
            } else {
                start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
                used("📞 $who"); "Le numéro de $who est composé, il reste à appuyer sur appeler."
            }
        },

        Skill("itineraire", "Itinéraire", "Navigation GPS.",
            obj("destination" to str(""), "mode" to enum("voiture", "pied", "velo", "transports"), required = listOf("destination"))) { p ->
            val dest = p.optString("destination")
            val m = when (p.optString("mode")) { "pied" -> "w"; "velo" -> "b"; "transports" -> "r"; else -> "d" }
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$m"))
            if (nav.resolveActivity(ctx.packageManager) != null) start(nav)
            else start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}")))
            used("🧭 $dest"); "Itinéraire vers $dest ouvert."
        },

        Skill("ouvrir", "Ouvrir", "Ouvre une appli ou un réglage (wifi, bluetooth, son…).",
            obj("cible" to str(""))) { p ->
            val raw = p.optString("cible")
            val q = norm(raw)
            val sys = when {
                q.contains("wifi") -> Settings.Panel.ACTION_WIFI
                q.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
                q.contains("internet") || q.contains("donnees") -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                q == "son" || q.contains("volume") || q.contains("sonnerie") -> Settings.Panel.ACTION_VOLUME
                q.contains("affichage") || q.contains("luminosite") || q.contains("ecran") -> Settings.ACTION_DISPLAY_SETTINGS
                q.contains("batterie") -> Intent.ACTION_POWER_USAGE_SUMMARY
                q.contains("localisation") || q.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                q.contains("photo") || q.contains("camera") -> MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                q == "reglages" || q == "parametres" -> Settings.ACTION_SETTINGS
                else -> null
            }
            if (sys != null) { start(Intent(sys)); used("⚙ $raw"); return@Skill "$raw ouvert." }
            val pm = ctx.packageManager
            val best = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it to norm(it.loadLabel(pm).toString()) }
                .filter { (_, l) -> l.isNotEmpty() && (l.contains(q) || q.contains(l)) }
                .minByOrNull { (_, l) -> l.length }?.first ?: return@Skill "Aucune application « $raw » trouvée."
            val label = best.loadLabel(pm).toString()
            start(pm.getLaunchIntentForPackage(best.activityInfo.packageName) ?: return@Skill "Impossible d'ouvrir $label.")
            used("📲 $label"); "$label ouverte."
        },

        Skill("telephone", "Téléphone", "Lampe, volume, état du téléphone.",
            obj("action" to enum("lampe_allumer", "lampe_eteindre", "volume", "etat"), "valeur" to int("0-100"),
                required = listOf("action"))) { p ->
            when (p.optString("action")) {
                "lampe_allumer", "lampe_eteindre" -> {
                    val on = p.optString("action") == "lampe_allumer"
                    val cm = ctx.getSystemService(CameraManager::class.java)
                    val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                        ?: return@Skill "Pas de flash sur ce téléphone."
                    cm.setTorchMode(id, on)
                    used(if (on) "🔦 allumée" else "🔦 éteinte"); if (on) "Lampe allumée." else "Lampe éteinte."
                }
                "volume" -> {
                    val am = ctx.getSystemService(AudioManager::class.java)
                    val pc = p.optInt("valeur", 50).coerceIn(0, 100)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * pc + 50) / 100, 0)
                    used("🔊 $pc %"); "Volume à $pc %."
                }
                else -> {
                    val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val lvl = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1
                    val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                    val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                    used("📱 état")
                    String.format(Locale.FRANCE, "Batterie %d %%%s. RAM libre %.1f Go sur %.1f. Stockage libre %.0f Go.",
                        lvl, if (plugged) " (en charge)" else "", mi.availMem / 1e9, mi.totalMem / 1e9, ctx.filesDir.usableSpace / 1e9)
                }
            }
        },

        Skill("agenda", "Agenda", "Agenda : lire ou ajouter.",
            obj("action" to enum("lire", "ajouter"), "jours" to int("1 = aujourd'hui"),
                "titre" to str(""), "date" to str("AAAA-MM-JJ"), "heure" to str("HH:MM"),
                "duree_minutes" to int(""), required = listOf("action"))) { p ->
            if (p.optString("action") == "lire") readAgenda(p.optInt("jours", 1).coerceIn(1, 31)) else {
                val cal = Calendar.getInstance()
                cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).parse(p.optString("date")) ?: error("date invalide")
                val time = p.optString("heure")
                val allDay = time.isBlank()
                if (!allDay) {
                    val hm = time.split(":", "h").map { it.trim().toIntOrNull() ?: 0 } + listOf(0, 0)
                    cal.set(Calendar.HOUR_OF_DAY, hm[0]); cal.set(Calendar.MINUTE, hm[1])
                }
                val start = cal.timeInMillis
                start(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, p.optString("titre"))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + p.optInt("duree_minutes", 60) * 60_000L)
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay))
                used("📅 ${p.optString("titre")}"); "L'événement est prêt dans l'agenda, il reste à valider."
            }
        },

        Skill("ecran", "Pilotage des applis", "Pilote l'écran d'une appli : « lire » d'abord, puis agir.",
            obj("action" to enum("lire", "toucher", "appui_long", "ecrire", "defiler_bas", "defiler_haut", "retour", "accueil", "recents", "notifications", "reglages_rapides", "verrouiller", "capture"),
                "cible" to str("texte visé"), "numero" to int("donné par lire"),
                "texte" to str(""), "confirme" to bool("true si l'utilisateur a confirmé"),
                required = listOf("action"))) { p ->
            val acc = NoxAccess.instance
            if (acc == null) {
                start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                used("🖐 accès écran à activer")
                return@Skill "Le pilotage de l'écran n'est pas activé. J'ai ouvert les réglages d'accessibilité : il faut activer « Nox ». Dis-le à l'utilisateur."
            }
            val cible = p.optString("cible").takeIf { it.isNotBlank() }
            val num = if (p.has("numero")) p.optInt("numero") else null
            when (val a = p.optString("action")) {
                "lire" -> { used("👁 lecture de l'écran"); acc.readScreen() }
                "toucher", "appui_long" -> {
                    val label = cible ?: acc.labelOf(num) ?: ""
                    if (Nox.prefs.confirmSensitive && NoxAccess.isSensitive(label) && !p.optBoolean("confirme"))
                        "Action sensible (« $label ») : demande d'abord à l'utilisateur de confirmer, puis rappelle avec confirme=true."
                    else { used("👆 $label".trim()); acc.click(cible, num, a == "appui_long") }
                }
                "ecrire" -> { used("⌨ saisie"); acc.type(p.optString("texte"), cible, num) }
                "defiler_bas" -> { used("↓ défilement"); acc.scroll(true) }
                "defiler_haut" -> { used("↑ défilement"); acc.scroll(false) }
                else -> { used("🖐 ${a.replace('_', ' ')}"); acc.global(a) }
            }
        },

        Skill("notifications", "Notifications", "Notifications : lire, répondre, ouvrir, effacer.",
            obj("action" to enum("lire", "repondre", "ouvrir", "effacer", "tout_effacer"), "appli" to str("ex: WhatsApp"),
                "numero" to int("donné par lire"), "texte" to str(""), required = listOf("action"))) { p ->
            val n = NoxNotifs.instance
            if (n == null) {
                start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                used("🔔 accès à activer")
                return@Skill "L'accès aux notifications n'est pas activé. J'ai ouvert le réglage : il faut autoriser « Nox ». Dis-le à l'utilisateur."
            }
            val num = p.optInt("numero", 0)
            when (p.optString("action")) {
                "repondre" -> {
                    val t = p.optString("texte").trim()
                    require(t.isNotEmpty()) { "texte vide" }
                    used("💬 réponse"); n.reply(num, t)
                }
                "ouvrir" -> { used("🔔 ouverture"); n.open(num) }
                "effacer" -> { used("🔕 effacée"); n.dismiss(num) }
                "tout_effacer" -> { used("🔕 tout effacé"); n.dismiss(null) }
                else -> { used("🔔 lecture"); n.read(p.optString("appli").takeIf { it.isNotBlank() }) }
            }
        },

        Skill("internet", "Recherche sur internet",
            "Recherche sur internet (quand on le demande, ou info récente).",
            obj("requete" to str(""), required = listOf("requete"))) { p ->
            val q = p.optString("requete").trim()
            require(q.isNotEmpty()) { "requête vide" }
            if (!Nox.prefs.webSearch) return@Skill "La recherche sur internet est désactivée dans les réglages de Nox. Dis-le à l'utilisateur."
            val results = WebSearch.search(q)
            if (results.isEmpty()) return@Skill "Aucun résultat sur internet (connexion absente ?). Dis-le simplement."
            val list = results.take(5).map { it.title + TurnView.SEP + it.url }
            main.post { onSources?.invoke(list) }
            used("🌐 recherche sur internet")
            WebSearch.contextBlock(q, results, Nox.engine.contextBudget(0).coerceAtMost(5000))
        },

        Skill("partager", "Partager & copier", "Partager ou copier un texte.",
            obj("action" to enum("partager", "copier", "recherche_web"), "texte" to str(""))) { p ->
            val t = p.optString("texte")
            when (p.optString("action")) {
                "copier" -> {
                    main.post { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nox", t)) }
                    used("📋 copié"); "Copié dans le presse-papier."
                }
                "recherche_web" -> {
                    start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, t))
                    used("🌐 $t"); "Recherche lancée dans le navigateur."
                }
                else -> {
                    start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Partager"))
                    used("↗ partage"); "Choisis l'appli pour partager."
                }
            }
        },
    )

    fun providers(): List<ToolProvider> = all.map { s ->
        tool(object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String =
                JSONObject().put("name", s.name).put("description", s.description).put("parameters", s.params).toString()

            override fun execute(paramsJsonString: String): String {
                callsThisTurn.incrementAndGet()
                if (dryRun) {
                    dryCalls += "${s.name}($paramsJsonString)"
                    NoxLog.i("BANC", "Outil simulé : ${s.name}($paramsJsonString)")
                    if (s.name !in SAFE_IN_TEST) return "C'est fait (simulation du banc d'essai)."
                }
                val c = NoxLog.chrono()
                return try {
                    val r = s.run(JSONObject(paramsJsonString.ifBlank { "{}" }))
                    NoxLog.i("OUTIL", "${s.name}($paramsJsonString) → ${r.replace('\n', ' ').take(160)} · ${c.ms()} ms")
                    if (FAILED.containsMatchIn(r)) retryAdvice(s.name, r) else r
                } catch (t: Throwable) {
                    NoxLog.e("OUTIL", "${s.name}($paramsJsonString) a échoué en ${c.ms()} ms", t)
                    retryAdvice(s.name, "Échec : ${t.message ?: t.javaClass.simpleName}")
                }
            }
        })
    }

    // ================================================================ aides

    private val FAILED = Regex("^(?:Échec|Erreur|Impossible|Je ne trouve|Aucun contact|Ce champ refuse)|introuvable", RegexOption.IGNORE_CASE)
    private val retried = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Outil raté : une seule deuxième chance par outil et par tour, avec une consigne claire au modèle. */
    private fun retryAdvice(name: String, result: String): String {
        val now = System.currentTimeMillis()
        val last = retried[name] ?: 0L
        return if (now - last > 60_000 && callsThisTurn.get() <= 3) {
            retried[name] = now
            NoxLog.i("OUTIL", "$name : deuxième chance proposée au modèle")
            "$result\nVérifie les paramètres (nom exact, date, heure, orthographe) et rappelle l'outil une seule fois en les corrigeant. Si ce n'est pas possible, explique le problème en une phrase."
        } else "$result\nN'essaie plus : explique simplement le problème en une phrase."
    }

    private fun used(label: String) { main.post { onUsed?.invoke(label) } }

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun mediaKey(code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun lookupNumber(name: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            main.post { askPermission?.invoke(Manifest.permission.READ_CONTACTS) }
            return null
        }
        val q = norm(name)
        if (q.isEmpty()) return null
        var best: String? = null
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val nn = norm(c.getString(0) ?: continue)
                if (nn == q) return c.getString(1)
                if (best == null && (nn.contains(q) || (q.length >= 3 && q.contains(nn) && nn.length >= 3))) best = c.getString(1)
            }
        }
        return best
    }

    private fun readAgenda(days: Int): String {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            main.post { askPermission?.invoke(Manifest.permission.READ_CALENDAR) }
            return "J'ai besoin d'accéder à l'agenda : accepte la demande qui vient de s'afficher, puis redemande-moi."
        }
        val from = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val to = from + days * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to)
        }.build()
        val fmt = SimpleDateFormat("EEE d MMM HH'h'mm", Locale.FRANCE)
        val day = SimpleDateFormat("EEE d MMM", Locale.FRANCE)
        val out = ArrayList<String>()
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION), null, null,
            CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 25) {
                val begin = c.getLong(1)
                val whenTxt = if (c.getInt(2) == 1) day.format(Date(begin)) + " (journée)" else fmt.format(Date(begin))
                val loc = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                out += "$whenTxt : ${c.getString(0) ?: "(sans titre)"}$loc"
            }
        }
        used("📅 agenda")
        return if (out.isEmpty()) "Rien dans l'agenda sur cette période." else out.joinToString("\n")
    }

    private fun missingContact(who: String): String =
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            "J'ai besoin d'accéder aux contacts : accepte la demande qui vient de s'afficher, puis redemande-moi."
        else "Je ne trouve pas « $who » dans les contacts."

    private fun loadNotes(): JSONObject = try {
        if (notesFile.exists()) JSONObject(notesFile.readText()) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    private fun norm(s: String) = Text.fold(s).replace(Regex("[^a-z0-9]"), "")

    private fun hm(prefix: String, h: Int, m: Int) = String.format(Locale.FRANCE, "%s%02dh%02d", prefix, h, m)

    private fun human(s: Int): String {
        val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return listOfNotNull(if (h > 0) "$h h" else null, if (m > 0) "$m min" else null, if (sec > 0) "$sec s" else null).joinToString(" ")
    }

    companion object {
        fun str(d: String): JSONObject = JSONObject().put("type", "string").apply { if (d.isNotEmpty()) put("description", d) }
        fun int(d: String): JSONObject = JSONObject().put("type", "integer").apply { if (d.isNotEmpty()) put("description", d) }
        fun bool(d: String): JSONObject = JSONObject().put("type", "boolean").apply { if (d.isNotEmpty()) put("description", d) }
        fun enum(vararg v: String): JSONObject = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))

        fun obj(vararg props: Pair<String, JSONObject>, required: List<String>? = null): JSONObject {
            val p = JSONObject()
            props.forEach { (k, v) -> p.put(k, v) }
            return JSONObject().put("type", "object").put("properties", p)
                .put("required", JSONArray(required ?: props.map { it.first }))
        }
    }
}

/** Mémoire longue : des faits courts, injectés dans la consigne système de chaque conversation. */
class LongMemory(ctx: Context) {
    private val file = File(ctx.filesDir, "memoire.json")

    fun all(): List<String> = try {
        if (!file.exists()) emptyList() else JSONArray(file.readText()).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { emptyList() }

    @Synchronized fun add(fact: String) {
        val l = all().toMutableList()
        if (l.none { it.equals(fact, ignoreCase = true) }) l += fact
        save(l.takeLast(60))
    }

    @Synchronized fun forget(keyword: String): Int {
        val k = Text.fold(keyword.trim())
        if (k.isEmpty()) return 0
        val l = all()
        val kept = l.filterNot { Text.fold(it).contains(k) }
        save(kept)
        return l.size - kept.size
    }

    @Synchronized fun forgetAll() = save(emptyList())

    @Synchronized fun removeAt(i: Int) { save(all().toMutableList().also { if (i in it.indices) it.removeAt(i) }) }

    private fun save(l: List<String>) { file.writeText(JSONArray(l).toString()) }

    fun promptBlock(): String {
        val facts = all()
        if (facts.isEmpty()) return ""
        val sb = StringBuilder("\n\nCe que tu sais de ton interlocuteur :")
        for (f in facts.asReversed()) {
            if (sb.length + f.length > 1400) break
            sb.append("\n- ").append(f)
        }
        return sb.toString()
    }
}
