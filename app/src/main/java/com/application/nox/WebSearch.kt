package com.application.nox

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * « Cherche sur internet… » : le seul moment où Nox sort sur le réseau.
 * DuckDuckGo (pas de compte, pas de clé, pas de pistage) donne les résultats ; le texte des meilleures
 * pages est récupéré et nettoyé, puis Nox écrit sa réponse à partir de ces extraits. Wikipédia sert
 * de secours si DuckDuckGo ne répond pas.
 */
object WebSearch {

    data class Result(val title: String, val url: String, val snippet: String, var text: String = "") {
        val domain: String get() = try { URL(url).host.removePrefix("www.") } catch (_: Exception) { url }
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val MARKERS = Regex("\\b(sur|dans|via|avec) (internet|le net|la toile|le web|google|duckduckgo|wikipedia|wikipédia)\\b|\\ben ligne\\b", RegexOption.IGNORE_CASE)
    private val VERBS = Regex("^(?:nox[ ,]+)?(?:est-ce que tu peux |tu peux |peux-tu |pourrais-tu |stp |s'il te plaît )?(?:me )?(?:cherche[rsz]?|recherche[rsz]?|trouve[rsz]?|regarde[rsz]?|google[rsz]?|fais une recherche)\\b\\s*(?:moi\\s*)?", RegexOption.IGNORE_CASE)

    /** La requête si le message demande une recherche sur internet, sinon null. */
    fun queryOf(message: String): String? {
        val m = message.trim().trimEnd('.', '!', '?', '…').trim()
        if (!MARKERS.containsMatchIn(m)) return null
        val f = Text.fold(m)
        val asks = Regex("^(nox[ ,]+)?(est-ce que tu peux |tu peux |peux-tu |pourrais-tu )?(me )?(cherche|recherche|trouve|regarde|google|fais une recherche|va voir|que dit|qu'est-ce que dit)").containsMatchIn(f) ||
            Regex("(cherche|recherche|regarde|trouve)").containsMatchIn(f)
        if (!asks) return null
        var q = MARKERS.replace(m, " ")
        q = VERBS.replace(q.trim(), "")
        q = q.replace(Regex("^(?:pour moi|moi|des infos sur|des informations sur|ce qu'on dit sur|ce que dit|les infos sur|l'info sur|à propos de|au sujet de)\\s+", RegexOption.IGNORE_CASE), "")
        q = q.replace(Regex("\\s+"), " ").trim(' ', ':', ',', '.', '-')
        return q.ifBlank { null }
    }

    /** Résultats + texte des meilleures pages. Appelé hors du fil principal. */
    fun search(query: String, pages: Int = 3): List<Result> {
        val clock = NoxLog.chrono()
        var results = try { duckDuckGo(query) } catch (t: Throwable) { NoxLog.w("WEB", "DuckDuckGo indisponible : ${t.message}"); emptyList() }
        if (results.isEmpty()) results = try { wikipedia(query) } catch (t: Throwable) { NoxLog.w("WEB", "Wikipédia indisponible : ${t.message}"); emptyList() }
        results.take(pages).forEach { r ->
            if (r.text.isBlank()) r.text = try { pageText(r.url, query) } catch (_: Throwable) { "" }
        }
        NoxLog.p("WEB", "« $query » : ${results.size} résultats en ${clock.ms()} ms")
        return results
    }

    /** Bloc de contexte donné au modèle. */
    fun contextBlock(query: String, results: List<Result>, maxChars: Int = 3600): String {
        val sb = StringBuilder("[Résultats d'une recherche sur internet pour « $query ». Réponds à la question en t'appuyant uniquement sur ces extraits, " +
            "en quelques phrases naturelles, sans citer d'URL ni de numéros ; si les extraits ne répondent pas, dis-le simplement.]\n")
        for (r in results.take(5)) {
            val body = (r.text.ifBlank { r.snippet }).replace('\n', ' ').trim()
            if (body.isBlank()) continue
            val line = "• ${r.title} (${r.domain}) : ${body}\n"
            if (sb.length + line.length > maxChars) { if (sb.length < maxChars - 300) sb.append(line.take(maxChars - sb.length - 2)).append("…\n"); break }
            sb.append(line)
        }
        return sb.toString()
    }

    private fun duckDuckGo(query: String): List<Result> {
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8") + "&kl=fr-fr", 9000)
        val out = ArrayList<Result>()
        val link = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snip = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snippets = snip.findAll(html).map { clean(it.groupValues[1]) }.toList()
        link.findAll(html).forEachIndexed { i, m ->
            val url = realUrl(m.groupValues[1]) ?: return@forEachIndexed
            if (url.contains("duckduckgo.com/y.js") || out.any { it.url == url }) return@forEachIndexed
            out += Result(clean(m.groupValues[2]), url, snippets.getOrElse(i) { "" })
        }
        return out.take(8)
    }

    private fun realUrl(href: String): String? {
        val h = href.replace("&amp;", "&")
        val u = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
            ?: if (h.startsWith("http")) h else if (h.startsWith("//")) "https:$h" else null
        return u?.takeIf { it.startsWith("http") }
    }

    private fun wikipedia(query: String): List<Result> {
        val json = org.json.JSONObject(get("https://fr.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&list=search&srlimit=4&srsearch=" +
            URLEncoder.encode(query, "UTF-8"), 9000))
        val arr = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        val out = ArrayList<Result>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("title")
            out += Result(title, "https://fr.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"), clean(o.optString("snippet")))
        }
        out.take(2).forEach { r ->
            r.text = try {
                val e = org.json.JSONObject(get("https://fr.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&prop=extracts&explaintext=1&exintro=1&titles=" +
                    URLEncoder.encode(r.title, "UTF-8"), 9000))
                e.optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0)?.optString("extract").orEmpty().take(1500)
            } catch (_: Throwable) { "" }
        }
        return out
    }

    /** Texte lisible d'une page, recentré sur les mots de la question. */
    private fun pageText(url: String, query: String): String {
        var html = get(url, 7000, maxBytes = 600_000)
        html = html.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form|aside)[^>]*>.*?</\\1>"), " ")
        val paragraphs = Regex("(?is)<(p|li|h[1-4]|td)[^>]*>(.*?)</\\1>").findAll(html)
            .map { clean(it.groupValues[2]) }.filter { it.length >= 60 }.toList()
        val all = if (paragraphs.isNotEmpty()) paragraphs else listOf(clean(html))
        val terms = Text.fold(query).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
        val ranked = all.withIndex().sortedByDescending { (i, p) ->
            val f = Text.fold(p)
            terms.count { f.contains(it) } * 10 - i * 0.05
        }
        val sb = StringBuilder()
        for ((_, p) in ranked) {
            if (sb.length + p.length > 1400) break
            sb.append(p).append(' ')
        }
        return sb.toString().trim().ifBlank { all.first().take(1400) }
    }

    private fun clean(s: String): String = android.text.Html.fromHtml(s.replace(Regex("<[^>]+>"), " "), android.text.Html.FROM_HTML_MODE_LEGACY)
        .toString().replace(Regex("\\s+"), " ").trim()

    private fun get(address: String, timeout: Int, maxBytes: Int = 2_000_000): String {
        var url = address
        repeat(5) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeout; readTimeout = timeout
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9")
                setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) { url = URL(URL(url), c.getHeaderField("Location") ?: error("redirection vide")).toString(); return@repeat }
                if (code !in 200..299) error("HTTP $code")
                val type = c.contentType.orEmpty()
                if (type.isNotEmpty() && !type.contains("html") && !type.contains("json") && !type.contains("text")) error("contenu non lisible ($type)")
                val cs = try { java.nio.charset.Charset.forName(Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1) ?: "UTF-8") } catch (_: Exception) { Charsets.UTF_8 }
                c.inputStream.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val tmp = ByteArray(16384)
                    while (buf.size() < maxBytes) { val n = input.read(tmp); if (n < 0) break; buf.write(tmp, 0, n) }
                    return String(buf.toByteArray(), cs)
                }
            } finally { c.disconnect() }
        }
        error("trop de redirections")
    }
}
