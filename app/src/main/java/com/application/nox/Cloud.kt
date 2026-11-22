package com.application.nox

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Les API en ligne de Patrice et l'aiguillage automatique :
 *  · actualités → GNews (titres récents) puis résumé par un modèle de langage ;
 *  · « montre-moi une photo de… » → Unsplash (images dans le chat, crédit du photographe) ;
 *  · questions → Groq d'abord (le plus rapide), Gemini Flash pour les demandes longues ou complexes,
 *    puis OpenRouter en secours si un service échoue ;
 *  · « crée / dessine / génère une image de… » → NVIDIA (FLUX), uniquement pour la création d'images.
 * Tout passe par des requêtes HTTPS directes ; les clés restent chiffrées sur le téléphone.
 */
object Cloud {

    enum class Api(val id: String, val title: String, val llm: Boolean, val site: String, val hint: String) {
        GROQ("groq", "Groq", true, "console.groq.com/keys", "Très rapide · questions courantes"),
        GEMINI("gemini", "Gemini Flash", true, "aistudio.google.com/apikey", "Demandes longues ou complexes"),
        OPENROUTER("openrouter", "OpenRouter", true, "openrouter.ai/keys", "Secours · des centaines de modèles"),
        NVIDIA("nvidia", "NVIDIA", false, "build.nvidia.com", "Création d'images uniquement (FLUX)"),
        GNEWS("gnews", "GNews", false, "gnews.io", "Actualités récentes"),
        UNSPLASH("unsplash", "Unsplash", false, "unsplash.com/developers", "Photos (clé « Access Key »)"),
    }

    data class Msg(val role: String, val text: String)
    data class Photo(val url: String, val author: String, val link: String, val description: String)
    data class Answer(val text: String, val via: String, val ms: Long, val sources: List<String> = emptyList(),
                      val photos: List<Photo> = emptyList(), val created: List<ByteArray> = emptyList())

    class CloudException(msg: String) : Exception(msg)

    // ---------------------------------------------------------------- aiguillage

    private val NEWS = Regex("\\b(?:actu|actus|actualites?|infos?|nouvelles|news|journal|quoi de neuf|derniere heure|a la une|gros titres)\\b")
    private val PHOTO = Regex("^(?:(?:montre|affiche|trouve|cherche)(?:[- ]moi)?|je veux voir)\\s+(?:une |des |la |les )?(?:photos?|images?|illustrations?)\\s+(?:de |d'|du |des |sur )?(.+)$")
    private val CREATE = Regex("^(?:(?:cree|creer|genere|generer|dessine|dessiner|fais|faire|imagine|realise|produis|peins)(?:[- ]moi)?|je veux)\\s+(?:une |un |des |l'|la |le )?(?:image|images|illustration|dessin|photo|logo|affiche|peinture|tableau|portrait|visuel|fond d'ecran)s?\\b\\s*(?:de |d'|du |des |avec |representant |qui represente |montrant |sur )?(.*)$")
    private val COMPLEX = Regex("\\b(?:explique en detail|en detail|analyse|compare|redige|ecris(?:-moi)? (?:un|une)|code|programme|plan|strategie|pourquoi exactement|resume ce|traduis)\\b")

    /** Choisit et appelle le bon service ; essaie le suivant si l'un échoue. */
    fun ask(text: String, history: List<Msg>, keys: CloudKeys, userName: String): Answer {
        val f = Text.fold(text.trim()).trimEnd('?', '!', '.', ' ')
        CREATE.find(f)?.let { m -> if (keys.has(Api.NVIDIA.id)) return create(text, m.groupValues[1], history, keys) }
        PHOTO.find(f)?.let { m -> if (keys.has(Api.UNSPLASH.id)) return photos(m.groupValues[1], keys) }
        if (NEWS.containsMatchIn(f) && keys.has(Api.GNEWS.id)) return news(text, f, history, keys, userName)
        return llm(text, history, keys, userName, complex = text.length > 220 || COMPLEX.containsMatchIn(f))
    }

    private fun llmOrder(keys: CloudKeys, complex: Boolean): List<Api> {
        val order = if (complex) listOf(Api.GEMINI, Api.GROQ, Api.OPENROUTER) else listOf(Api.GROQ, Api.GEMINI, Api.OPENROUTER)
        return order.filter { keys.has(it.id) }
    }

    private fun system(userName: String) =
        "Tu es Nox, l'assistant de ${userName.ifBlank { "l'utilisateur" }}. Réponds en français, avec chaleur et précision, " +
            "en tutoyant. Va à l'essentiel ; développe seulement si on te le demande. Pas de tableaux. " +
            "Si tu n'es pas sûr d'une information récente, dis-le."

    fun llm(text: String, history: List<Msg>, keys: CloudKeys, userName: String, complex: Boolean = false, context: String? = null): Answer {
        val order = llmOrder(keys, complex)
        if (order.isEmpty()) throw CloudException("Aucune clé d'IA en ligne : ajoute Groq, Gemini ou OpenRouter dans la page API.")
        val errors = ArrayList<String>()
        for (api in order) {
            val t0 = System.currentTimeMillis()
            try {
                val model = modelFor(api, keys)
                val out = chat(api, model, keys.key(api.id), system(userName), history.takeLast(12), if (context == null) text else "$context\n\nQuestion : $text")
                val ms = System.currentTimeMillis() - t0
                keys.record(api.id, ms, null)
                NoxLog.p("CLOUD", "${api.title} · $model · ${out.length} car. en $ms ms")
                return Answer(out.trim(), "${api.title} · ${model.substringAfterLast('/')}", ms)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                keys.record(api.id, 0, msg)
                errors += "${api.title} : $msg"
                NoxLog.w("CLOUD", "${api.title} en échec, service suivant", t)
            }
        }
        throw CloudException("Aucun service n'a répondu.\n" + errors.joinToString("\n"))
    }

    // ---------------------------------------------------------------- modèles de langage (format OpenAI)

    private fun base(api: Api) = when (api) {
        Api.GROQ -> "https://api.groq.com/openai/v1"
        Api.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai"
        Api.OPENROUTER -> "https://openrouter.ai/api/v1"
        else -> error("pas un modèle de langage")
    }

    private fun headers(api: Api, key: String) = buildMap {
        put("Authorization", "Bearer $key")
        if (api == Api.OPENROUTER) { put("HTTP-Referer", "https://github.com/patricefranckk-creator/Nox"); put("X-Title", "Nox") }
    }

    private fun chat(api: Api, model: String, key: String, system: String, history: List<Msg>, user: String): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text.take(4000))) }
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.6).put("max_tokens", 1200)
        val res = JSONObject(http("${base(api)}/chat/completions", headers(api, key), body.toString(), 60_000))
        val choice = res.optJSONArray("choices")?.optJSONObject(0) ?: throw CloudException("réponse vide")
        return choice.optJSONObject("message")?.optString("content").orEmpty()
            .replace(Regex("(?s)<think>.*?</think>"), "").trim()
            .ifEmpty { throw CloudException("réponse vide") }
    }

    /** Liste des modèles proposés par un service (pour le choix dans la page API). */
    fun models(api: Api, key: String): List<String> {
        val raw = JSONObject(http("${base(api)}/models", headers(api, key), null, 20_000))
        val arr = raw.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }
            .map { it.removePrefix("models/") }
            .filter { id -> listOf("whisper", "tts", "embed", "guard", "image", "vision-preview", "audio", "rerank", "moderation").none { id.contains(it, true) } }
            .sorted()
    }

    /** Modèle choisi, ou à défaut un bon modèle pris automatiquement dans la liste du service. */
    fun modelFor(api: Api, keys: CloudKeys): String {
        keys.model(api.id).takeIf { it.isNotBlank() }?.let { return it }
        val pick = when (api) {
            Api.OPENROUTER -> "openrouter/auto"
            Api.NVIDIA -> IMAGE_MODELS.first()
            else -> {
                val list = models(api, keys.key(api.id))
                val prefs = when (api) {
                    Api.GROQ -> listOf("llama-3.3-70b", "gpt-oss-120b", "llama-4", "qwen3", "70b")
                    else -> listOf("flash")
                }
                prefs.firstNotNullOfOrNull { p ->
                    list.filter { it.contains(p, true) && !it.contains("lite", true) && !it.contains("preview", true) }.maxOrNull()
                        ?: list.filter { it.contains(p, true) }.maxOrNull()
                } ?: list.firstOrNull() ?: throw CloudException("aucun modèle disponible")
            }
        }
        keys.setModel(api.id, pick)
        NoxLog.i("CLOUD", "${api.title} : modèle choisi automatiquement $pick")
        return pick
    }

    // ---------------------------------------------------------------- NVIDIA : création d'images

    /** Modèles d'image NVIDIA (adresse ai.api.nvidia.com/v1/genai/<modèle>). */
    val IMAGE_MODELS = listOf("black-forest-labs/flux.1-dev", "black-forest-labs/flux.1-schnell", "black-forest-labs/flux-3-image-klein",
        "stabilityai/stable-diffusion-3-medium")

    private fun create(original: String, subject: String, history: List<Msg>, keys: CloudKeys): Answer {
        val t0 = System.currentTimeMillis()
        // FLUX comprend mieux l'anglais : on fait traduire et enrichir la demande si un modèle de langage est là
        val prompt = try {
            llm("Transforme cette demande en un prompt anglais détaillé pour un générateur d'images (sujet, style, lumière, cadrage). " +
                "Réponds uniquement par le prompt, sans guillemets.\nDemande : $original", emptyList(), keys, "").text
                .lines().first { it.isNotBlank() }.trim().trim('"').take(900)
        } catch (_: Throwable) { subject.ifBlank { original } }
        val img = try { image(prompt, keys) } catch (t: Throwable) {
            keys.record(Api.NVIDIA.id, 0, t.message); throw CloudException("NVIDIA : ${t.message}")
        }
        val ms = System.currentTimeMillis() - t0
        keys.record(Api.NVIDIA.id, ms, null)
        NoxLog.p("CLOUD", "Image NVIDIA ${modelFor(Api.NVIDIA, keys)} en $ms ms · « ${prompt.take(80)} »")
        return Answer("Voici ton image. Touche-la pour l'enregistrer dans ta galerie.", "NVIDIA · ${modelFor(Api.NVIDIA, keys).substringAfterLast('/')}", ms,
            created = listOf(img))
    }

    private fun image(prompt: String, keys: CloudKeys): ByteArray {
        val model = modelFor(Api.NVIDIA, keys)
        val url = "https://ai.api.nvidia.com/v1/genai/$model"
        val auth = mapOf("Authorization" to "Bearer ${keys.key(Api.NVIDIA.id)}")
        val full = JSONObject().put("prompt", prompt).put("seed", 0)
        when {
            model.contains("flux.1-dev") -> full.put("mode", "base").put("cfg_scale", 3.5).put("width", 1024).put("height", 1024).put("steps", 30)
            model.contains("schnell") -> full.put("width", 1024).put("height", 1024).put("steps", 4)
        }
        val raw = try { http(url, auth, full.toString(), 120_000) } catch (e: CloudException) {
            // Chaque modèle a son propre format : en cas de refus du corps, on retente avec le strict minimum
            if (e.message?.startsWith("HTTP 4") == true) http(url, auth, JSONObject().put("prompt", prompt).toString(), 120_000) else throw e
        }
        val art = JSONObject(raw).optJSONArray("artifacts")?.optJSONObject(0) ?: throw CloudException("pas d'image dans la réponse")
        when (art.optString("finishReason")) {
            "CONTENT_FILTERED" -> throw CloudException("image refusée par le filtre de contenu")
            "ERROR" -> throw CloudException("le générateur a échoué")
        }
        val b64 = art.optString("base64").ifBlank { throw CloudException("image vide") }
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }

    // ---------------------------------------------------------------- GNews

    private val NEWS_WORDS = Regex("\\b(?:donne(?:-moi)?|dis(?:-moi)?|quelles? sont|c'est quoi|les|des|derniere?s?|dernieres|actu|actus|actualites?|infos?|nouvelles|news|du jour|aujourd'hui|sur|de|du|la|le|quoi de neuf|a la une|gros titres|en ce moment|recentes?)\\b")

    private fun news(original: String, f: String, history: List<Msg>, keys: CloudKeys, userName: String): Answer {
        val t0 = System.currentTimeMillis()
        val topic = NEWS_WORDS.replace(f, " ").replace(Regex("[^a-z0-9' -]"), " ").replace(Regex("\\s+"), " ").trim()
        val key = URLEncoder.encode(keys.key(Api.GNEWS.id), "UTF-8")
        val url = if (topic.length >= 3) "https://gnews.io/api/v4/search?q=${URLEncoder.encode(topic, "UTF-8")}&lang=fr&max=6&sortby=publishedAt&apikey=$key"
        else "https://gnews.io/api/v4/top-headlines?category=general&lang=fr&country=fr&max=8&apikey=$key"
        val arts = try {
            JSONObject(http(url, emptyMap(), null, 15_000)).optJSONArray("articles") ?: JSONArray()
        } catch (t: Throwable) {
            keys.record(Api.GNEWS.id, 0, t.message); throw CloudException("GNews : ${t.message}")
        }
        keys.record(Api.GNEWS.id, System.currentTimeMillis() - t0, null)
        if (arts.length() == 0) return Answer("Je ne trouve pas d'actualité récente${if (topic.isNotEmpty()) " sur « $topic »" else ""}.", "GNews", System.currentTimeMillis() - t0)
        val list = (0 until arts.length()).mapNotNull { arts.optJSONObject(it) }
        val block = list.joinToString("\n") { a ->
            "- ${a.optString("title")} (${a.optJSONObject("source")?.optString("name")}, ${a.optString("publishedAt").take(10)}) : ${a.optString("description")}"
        }
        val sources = list.map { it.optString("title") + TurnView.SEP + it.optString("url") }
        return try {
            val a = llm(original, history, keys, userName, context = "Voici des actualités récentes (GNews) :\n$block\n\nRésume-les clairement pour répondre, sans inventer, en citant le média entre parenthèses.")
            a.copy(via = "GNews + ${a.via}", ms = System.currentTimeMillis() - t0, sources = sources)
        } catch (_: CloudException) {
            Answer(list.joinToString("\n\n") { "• ${it.optString("title")}\n${it.optString("description")}" }, "GNews", System.currentTimeMillis() - t0, sources)
        }
    }

    // ---------------------------------------------------------------- Unsplash

    private fun photos(subject: String, keys: CloudKeys): Answer {
        val t0 = System.currentTimeMillis()
        val q = subject.trim().ifEmpty { "nature" }
        val res = try {
            JSONObject(http("https://api.unsplash.com/search/photos?query=${URLEncoder.encode(q, "UTF-8")}&per_page=6&lang=fr&content_filter=high",
                mapOf("Authorization" to "Client-ID ${keys.key(Api.UNSPLASH.id)}", "Accept-Version" to "v1"), null, 15_000))
        } catch (t: Throwable) {
            keys.record(Api.UNSPLASH.id, 0, t.message); throw CloudException("Unsplash : ${t.message}")
        }
        keys.record(Api.UNSPLASH.id, System.currentTimeMillis() - t0, null)
        val arr = res.optJSONArray("results") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { p ->
            Photo(p.optJSONObject("urls")?.optString("small").orEmpty(),
                p.optJSONObject("user")?.optString("name").orEmpty(),
                p.optJSONObject("links")?.optString("html").orEmpty() + "?utm_source=nox&utm_medium=referral",
                p.optString("alt_description").ifBlank { p.optString("description") })
        }.filter { it.url.isNotBlank() }
        val text = if (list.isEmpty()) "Pas de photo trouvée pour « $q »." else "Voici des photos de « $q » :"
        return Answer(text, "Unsplash", System.currentTimeMillis() - t0, photos = list)
    }

    // ---------------------------------------------------------------- test d'une clé

    fun test(api: Api, keys: CloudKeys): String {
        val key = keys.key(api.id).ifBlank { throw CloudException("pas de clé") }
        val t0 = System.currentTimeMillis()
        val r = when (api) {
            Api.GNEWS -> {
                val n = JSONObject(http("https://gnews.io/api/v4/top-headlines?lang=fr&country=fr&max=1&apikey=${URLEncoder.encode(key, "UTF-8")}", emptyMap(), null, 15_000))
                    .optInt("totalArticles", 0)
                "OK · $n articles disponibles"
            }
            Api.NVIDIA -> {
                val img = image("a small red apple on a wooden table, soft daylight", keys)
                "OK · image de ${img.size / 1024} Ko générée avec ${modelFor(Api.NVIDIA, keys).substringAfterLast('/')}"
            }
            Api.UNSPLASH -> {
                http("https://api.unsplash.com/photos/random?count=1", mapOf("Authorization" to "Client-ID $key", "Accept-Version" to "v1"), null, 15_000)
                "OK · photos accessibles"
            }
            else -> {
                val model = modelFor(api, keys)
                val out = chat(api, model, key, "Réponds en un mot.", emptyList(), "Dis bonjour en français.")
                "OK · $model · « ${out.take(30)} »"
            }
        }
        keys.record(api.id, System.currentTimeMillis() - t0, null)
        return "$r · ${System.currentTimeMillis() - t0} ms"
    }

    // ---------------------------------------------------------------- HTTP

    private fun http(url: String, headers: Map<String, String>, body: String?, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "Nox/2 (Android)")
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toString("UTF-8") }.orEmpty()
            if (code !in 200..299) {
                val why = try {
                    val j = JSONObject(text)
                    j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: j.optJSONArray("errors")?.optString(0) ?: j.optString("message")
                } catch (_: Throwable) { text.take(120) }
                throw CloudException(when (code) {
                    401, 403 -> "clé refusée ($code)"
                    429 -> "quota atteint (429)"
                    else -> "HTTP $code"
                } + if (why.isNullOrBlank()) "" else " · ${why.take(140)}")
            }
            return text
        } finally {
            c.disconnect()
        }
    }
}
