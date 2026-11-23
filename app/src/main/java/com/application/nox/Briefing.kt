package com.application.nox

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.application.nox.Ui.dp
import com.application.nox.Ui.field
import com.application.nox.Ui.text
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Point du matin (« Bonjour Nox ») : agenda du jour, rappels, liste de courses, météo (Open-Meteo, gratuit,
 * sans clé) et trois titres d'actualité (GNews si la clé est enregistrée). Texte prêt à lire à voix haute.
 */
object Briefing {

    fun build(name: String): String {
        val p = Nox.prefs
        val c = NoxLog.chrono()
        val now = Calendar.getInstance()
        val day = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now.time)
        val hello = when (now.get(Calendar.HOUR_OF_DAY)) { in 5..11 -> "Bonjour"; in 12..17 -> "Bon après-midi"; else -> "Bonsoir" }
        val parts = ArrayList<String>()
        parts += "$hello${if (name.isNotBlank()) " $name" else ""} ! Nous sommes $day."
        if (p.briefWeather) safe("météo") { weather(p.briefCity) }?.let { parts += it }
        if (p.briefAgenda) safe("agenda") { agenda() }?.let { parts += it }
        if (p.briefReminders) safe("rappels") { reminders() }?.let { parts += it }
        if (p.briefList) safe("liste") { shopping() }?.let { parts += it }
        if (p.briefNews) safe("actualités") { news() }?.let { parts += it }
        if (parts.size == 1) parts += "Rien de particulier au programme. Belle journée !"
        else parts += "Belle journée !"
        NoxLog.p("MATIN", "Point du matin prêt en ${c.ms()} ms (${parts.size - 2} rubrique(s))")
        return parts.joinToString("\n\n")
    }

    private fun safe(what: String, f: () -> String?): String? = try { f() } catch (t: Throwable) {
        NoxLog.w("MATIN", "Rubrique $what indisponible : ${t.message}"); null
    }

    // ---------------------------------------------------------------- rubriques

    private fun weather(city: String): String? {
        val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name=" + URLEncoder.encode(city.ifBlank { "Pau" }, "UTF-8")))
        val place = geo.optJSONArray("results")?.optJSONObject(0) ?: return "Météo : je ne trouve pas la ville « $city »."
        val lat = place.getDouble("latitude"); val lon = place.getDouble("longitude")
        val w = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,weather_code" +
            "&timezone=auto&forecast_days=1"))
        val cur = w.optJSONObject("current")
        val d = w.optJSONObject("daily") ?: return null
        val min = d.getJSONArray("temperature_2m_min").optDouble(0).toInt()
        val max = d.getJSONArray("temperature_2m_max").optDouble(0).toInt()
        val rain = d.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
        val sky = sky(d.getJSONArray("weather_code").optInt(0))
        val now = cur?.optDouble("temperature_2m")?.takeIf { !it.isNaN() }?.let { " Il fait ${it.toInt()} degrés en ce moment." } ?: ""
        val umbrella = when { rain >= 60 -> " Prends un parapluie : $rain % de risque de pluie."; rain in 30..59 -> " Risque de pluie : $rain %."; else -> "" }
        return "Météo à ${place.optString("name", city)} : $sky, de $min à $max degrés.$now$umbrella"
    }

    private fun sky(code: Int) = when (code) {
        0 -> "grand soleil"; 1 -> "plutôt ensoleillé"; 2 -> "partiellement nuageux"; 3 -> "couvert"
        45, 48 -> "brouillard"; 51, 53, 55, 56, 57 -> "bruine"; 61, 63 -> "pluie"; 65 -> "forte pluie"; 66, 67 -> "pluie verglaçante"
        71, 73, 75, 77 -> "neige"; 80, 81 -> "averses"; 82 -> "fortes averses"; 85, 86 -> "averses de neige"
        95 -> "orages"; 96, 99 -> "orages avec grêle"; else -> "temps variable"
    }

    private fun agenda(): String? {
        if (Nox.app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val r = Nox.skills.all.first { it.name == "agenda" }.run(JSONObject().put("action", "lire").put("jours", 1))
        if (r.startsWith("Rien")) return "Agenda : rien de prévu aujourd'hui."
        val lines = r.lines().filter { it.isNotBlank() }.map { it.substringAfter(" : ").let { t -> it.substringBefore(" : ").substringAfterLast(' ') + " : " + t } }
        return "Agenda du jour : " + lines.joinToString(" ; ") + "."
    }

    private fun reminders(): String? {
        val end = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }.timeInMillis
        val today = ReminderReceiver.upcoming().filter { it.first <= end }
        if (today.isEmpty()) return null
        val f = SimpleDateFormat("HH'h'mm", Locale.FRANCE)
        return "Rappels : " + today.joinToString(" ; ") { "${f.format(Date(it.first))}, ${it.second}" } + "."
    }

    private fun shopping(): String? {
        val r = Nox.skills.all.first { it.name == "notes" }.run(JSONObject().put("action", "lire").put("liste", "courses"))
        return if (r.contains("vide")) null else "Liste de courses : " + r.substringAfter(" : ").trim() + "."
    }

    private fun news(): String? {
        val keys = CloudKeys(Nox.app)
        if (!keys.has("gnews")) return null
        val key = URLEncoder.encode(keys.key("gnews"), "UTF-8")
        val arts = JSONObject(get("https://gnews.io/api/v4/top-headlines?category=general&lang=fr&country=fr&max=3&apikey=$key")).optJSONArray("articles") ?: return null
        val titles = (0 until arts.length()).mapNotNull { arts.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotBlank() } }.take(3)
        if (titles.isEmpty()) return null
        return "Les titres du jour : " + titles.joinToString(" ; ") { it.trimEnd('.') } + "."
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "Nox/2 (Android)")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toString("UTF-8") }
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- réglages

    fun showDialog(a: MainActivity) {
        val p = Nox.prefs
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(4), 0, a.dp(4), a.dp(8)) }
        body.addView(a.text("Dis « Bonjour Nox » le matin (ou « le point du matin » à toute heure) : Nox lit ce que tu coches ici, " +
            "sans passer par le modèle.", 13f, Ui.MUTED).apply { setPadding(0, 0, 0, a.dp(10)) })
        fun sw(label: String, on: Boolean, set: (Boolean) -> Unit): View = Switch(a).apply {
            text = label; setTextColor(Ui.TEXT); textSize = 14.5f; isChecked = on
            setPadding(0, a.dp(6), 0, a.dp(6))
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        body.addView(sw("🌤 Météo", p.briefWeather) { p.briefWeather = it })
        val city: EditText = a.field("Ville de la météo").apply { setText(p.briefCity) }
        body.addView(city, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(6) })
        body.addView(sw("📅 Agenda du jour", p.briefAgenda) { p.briefAgenda = it })
        body.addView(sw("🔔 Rappels du jour", p.briefReminders) { p.briefReminders = it })
        body.addView(sw("🛒 Liste de courses", p.briefList) { p.briefList = it })
        body.addView(sw("📰 3 titres d'actualité (clé GNews de la page en ligne)", p.briefNews) { p.briefNews = it })
        NoxDialog.sheet(a).setTitle("Point du matin").setIcon("☀")
            .setView(ScrollView(a).apply { addView(body) })
            .setNegativeButton("Essayer") { _, _ ->
                p.briefCity = city.text.toString().trim().ifBlank { "Pau" }
                a.runText("Le point du matin")
            }
            .setPositiveButton("Enregistrer") { _, _ -> p.briefCity = city.text.toString().trim().ifBlank { "Pau" } }
            .show()
    }
}
