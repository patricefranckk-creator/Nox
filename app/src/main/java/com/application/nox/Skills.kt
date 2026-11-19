package com.application.nox

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.app.ActivityManager
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
 * Les compétences de Nox : de vrais outils que le modèle peut appeler tout seul (function calling),
 * 100 % locaux. Chaque compétence = une description JSON courte + une action Android.
 */
class Skills(private val ctx: Context) {

    /** Prévient l'interface qu'une action a été faite (affichée sous forme de puce). */
    var onUsed: ((label: String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    val memory = LongMemory(ctx)
    private val notesFile = File(ctx.filesDir, "notes.json")
    private var torchOn = false

    data class Skill(val name: String, val title: String, val description: String, val params: JSONObject, val run: (JSONObject) -> String)

    // ------------------------------------------------------------------ catalogue

    val all: List<Skill> = listOf(
        Skill("heure_date", "Heure et date", "Donne l'heure, la date et le jour actuels.", schema()) {
            val now = Date()
            used("🕒 heure")
            SimpleDateFormat("EEEE d MMMM yyyy, HH'h'mm", Locale.FRANCE).format(now)
        },

        Skill("calculer", "Calculatrice", "Calcule une expression mathématique exacte (+ - * / ^ % parenthèses, sqrt, pourcentages).",
            schema("expression" to "expression, ex: (12.5*3)+sqrt(16)")) { p ->
            val expr = p.optString("expression")
            val r = MathEval.eval(expr)
            used("🧮 $expr")
            MathEval.format(r)
        },

        Skill("minuteur", "Minuteur", "Lance un minuteur dans l'application Horloge.",
            schema("secondes" to "durée totale en secondes", "nom" to "libellé facultatif", required = listOf("secondes"))) { p ->
            val s = p.optInt("secondes", 0)
            require(s in 1..86_400) { "durée invalide" }
            start(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, s)
                .putExtra(AlarmClock.EXTRA_MESSAGE, p.optString("nom", "Nox"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            used("⏱ minuteur ${human(s)}")
            "Minuteur de ${human(s)} lancé."
        },

        Skill("alarme", "Réveil", "Programme une alarme dans l'application Horloge.",
            schema("heure" to "heure 0-23", "minute" to "minute 0-59", "nom" to "libellé facultatif", required = listOf("heure", "minute"))) { p ->
            val h = p.optInt("heure", -1); val m = p.optInt("minute", 0)
            require(h in 0..23 && m in 0..59) { "heure invalide" }
            start(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_MESSAGE, p.optString("nom", "Nox"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            used(String.format(Locale.FRANCE, "⏰ alarme %02dh%02d", h, m))
            String.format(Locale.FRANCE, "Alarme réglée à %02dh%02d.", h, m)
        },

        Skill("memoriser", "Mémoire longue", "Retient durablement un fait important sur l'utilisateur (préférence, proche, projet). À utiliser quand il demande de retenir quelque chose.",
            schema("fait" to "le fait, en une phrase courte")) { p ->
            val f = p.optString("fait").trim()
            require(f.isNotEmpty()) { "fait vide" }
            memory.add(f)
            used("🧠 retenu")
            "Retenu : $f"
        },

        Skill("oublier", "Oubli", "Supprime de la mémoire longue les faits qui contiennent ce mot-clé.",
            schema("mot_cle" to "mot-clé du fait à oublier")) { p ->
            val n = memory.forget(p.optString("mot_cle"))
            used("🧠 oubli ×$n")
            if (n == 0) "Aucun souvenir ne correspond." else "$n souvenir(s) effacé(s)."
        },

        Skill("note", "Notes", "Ajoute une note ou un élément à une liste (courses, idées, à faire…).",
            schema("liste" to "nom de la liste, ex: courses", "texte" to "contenu à ajouter")) { p ->
            val list = p.optString("liste", "notes").lowercase(Locale.FRANCE).trim().ifEmpty { "notes" }
            val text = p.optString("texte").trim()
            require(text.isNotEmpty()) { "texte vide" }
            val all = loadNotes()
            val arr = all.optJSONArray(list) ?: JSONArray().also { all.put(list, it) }
            arr.put(text)
            notesFile.writeText(all.toString())
            used("📝 $list")
            "Ajouté à la liste « $list » (${arr.length()} élément(s))."
        },

        Skill("lire_notes", "Lecture des notes", "Lit le contenu d'une liste de notes, ou toutes les listes si aucun nom.",
            schema("liste" to "nom de la liste (facultatif)", required = emptyList())) { p ->
            val all = loadNotes()
            val wanted = p.optString("liste").lowercase(Locale.FRANCE).trim()
            used("📝 lecture")
            if (wanted.isNotEmpty()) {
                val arr = all.optJSONArray(wanted) ?: return@Skill "La liste « $wanted » est vide."
                (0 until arr.length()).joinToString("; ") { arr.getString(it) }
            } else if (all.length() == 0) "Aucune note." else
                all.keys().asSequence().joinToString(" | ") { k -> "$k : " + all.getJSONArray(k).let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } } }
        },

        Skill("vider_liste", "Vider une liste", "Efface entièrement une liste de notes.",
            schema("liste" to "nom de la liste")) { p ->
            val all = loadNotes()
            val l = p.optString("liste").lowercase(Locale.FRANCE).trim()
            all.remove(l); notesFile.writeText(all.toString())
            used("📝 $l vidée")
            "Liste « $l » effacée."
        },

        Skill("lampe", "Lampe torche", "Allume ou éteint la lampe torche.",
            schema("allumer" to "true pour allumer, false pour éteindre")) { p ->
            val on = p.optBoolean("allumer", !torchOn)
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return@Skill "Pas de flash sur ce téléphone."
            cm.setTorchMode(id, on); torchOn = on
            used(if (on) "🔦 allumée" else "🔦 éteinte")
            if (on) "Lampe allumée." else "Lampe éteinte."
        },

        Skill("volume", "Volume", "Règle le volume multimédia en pourcentage.",
            schema("pourcent" to "0 à 100")) { p ->
            val am = ctx.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val pc = p.optInt("pourcent", 50).coerceIn(0, 100)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pc + 50) / 100, 0)
            used("🔊 $pc %")
            "Volume réglé à $pc %."
        },

        Skill("etat_telephone", "État du téléphone", "Donne la batterie, la charge, la mémoire vive et le stockage libre.", schema()) {
            val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1
            val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val free = ctx.filesDir.usableSpace
            used("📱 état")
            "Batterie $lvl %${if (plugged) " (en charge)" else ""}. RAM libre ${"%.1f".format(Locale.FRANCE, mi.availMem / 1e9)} Go sur ${"%.1f".format(Locale.FRANCE, mi.totalMem / 1e9)}. Stockage libre ${"%.0f".format(Locale.FRANCE, free / 1e9)} Go."
        },

        Skill("ouvrir_appli", "Ouvrir une appli", "Ouvre une application installée à partir de son nom.",
            schema("nom" to "nom de l'application, ex: Deezer")) { p ->
            val q = norm(p.optString("nom"))
            val pm = ctx.packageManager
            val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val best = launchers.map { it to norm(it.loadLabel(pm).toString()) }
                .filter { (_, label) -> label.contains(q) || q.contains(label) }
                .minByOrNull { (_, label) -> label.length }?.first
                ?: return@Skill "Aucune application « ${p.optString("nom")} » trouvée."
            val label = best.loadLabel(pm).toString()
            start(pm.getLaunchIntentForPackage(best.activityInfo.packageName) ?: return@Skill "Impossible d'ouvrir $label.")
            used("📲 $label")
            "$label ouverte."
        },

        Skill("evenement_agenda", "Agenda", "Prépare un événement dans l'agenda (l'utilisateur valide à l'écran).",
            schema("titre" to "titre", "date" to "date AAAA-MM-JJ", "heure" to "heure HH:MM (facultatif)",
                "duree_minutes" to "durée (facultatif, 60 par défaut)", required = listOf("titre", "date"))) { p ->
            val cal = Calendar.getInstance()
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).parse(p.optString("date")) ?: error("date invalide")
            cal.time = d
            val time = p.optString("heure")
            val allDay = time.isBlank()
            if (!allDay) {
                val (h, m) = time.split(":", "h").map { it.trim().toIntOrNull() ?: 0 } + listOf(0, 0)
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m)
            }
            val start = cal.timeInMillis
            val end = start + p.optInt("duree_minutes", 60) * 60_000L
            start(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, p.optString("titre"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay))
            used("📅 ${p.optString("titre")}")
            "L'agenda est ouvert avec l'événement prêt : il reste à valider."
        },
    )

    /** Outils au format attendu par LiteRT-LM. */
    fun providers(): List<ToolProvider> = all.map { s ->
        tool(object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = JSONObject()
                .put("name", s.name)
                .put("description", s.description)
                .put("parameters", s.params)
                .toString()

            override fun execute(paramsJsonString: String): String = try {
                s.run(JSONObject(paramsJsonString.ifBlank { "{}" }))
            } catch (t: Throwable) {
                "Échec : ${t.message ?: t.javaClass.simpleName}"
            }
        })
    }

    // ------------------------------------------------------------------ outils internes

    private fun used(label: String) = main.post { onUsed?.invoke(label) }

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun loadNotes(): JSONObject = try {
        if (notesFile.exists()) JSONObject(notesFile.readText()) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(Locale.FRANCE), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]"), "")

    private fun human(s: Int): String {
        val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return listOfNotNull(if (h > 0) "$h h" else null, if (m > 0) "$m min" else null, if (sec > 0) "$sec s" else null).joinToString(" ")
    }

    companion object {
        /** Schéma JSON compact : chaque paramètre est une chaîne/nombre décrit en une phrase. */
        fun schema(vararg props: Pair<String, String>, required: List<String>? = null): JSONObject {
            val p = JSONObject()
            props.forEach { (k, d) ->
                val type = when {
                    d.startsWith("true") -> "boolean"
                    k in setOf("secondes", "heure", "minute", "pourcent", "duree_minutes") && !d.contains("HH") -> "integer"
                    else -> "string"
                }
                p.put(k, JSONObject().put("type", type).put("description", d))
            }
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
        val k = keyword.trim().lowercase(Locale.FRANCE)
        if (k.isEmpty()) return 0
        val l = all()
        val kept = l.filterNot { it.lowercase(Locale.FRANCE).contains(k) }
        save(kept)
        return l.size - kept.size
    }

    @Synchronized fun forgetAll() = save(emptyList())

    @Synchronized fun removeAt(i: Int) { save(all().toMutableList().also { if (i in it.indices) it.removeAt(i) }) }

    private fun save(l: List<String>) { file.writeText(JSONArray(l).toString()) }

    /** Bloc à ajouter à la consigne système (borné pour ne pas manger le contexte). */
    fun promptBlock(): String {
        val facts = all()
        if (facts.isEmpty()) return ""
        val sb = StringBuilder("\n\nCe que tu sais déjà de ton interlocuteur :")
        for (f in facts.asReversed()) {
            if (sb.length + f.length > 1400) break
            sb.append("\n- ").append(f)
        }
        return sb.toString()
    }
}
