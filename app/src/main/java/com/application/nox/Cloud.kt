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
                      val photos: List<Photo> = emptyList(), val created: List<ByteArray> = emptyList(), val imagePrompt: String = "")

    class CloudException(msg: String) : Exception(msg)

    /** Étape en cours, affichée sous la question (« NVIDIA dessine l'image… »). */
    @Volatile var onStage: ((String) -> Unit)? = null
    @Volatile private var cancelled = false
    @Volatile private var current: HttpURLConnection? = null

    /** Arrête la demande en cours (bouton ■) : la connexion est coupée tout de suite. */
    fun cancel() {
        cancelled = true
        try { current?.disconnect() } catch (_: Throwable) {}
    }

    private fun stage(s: String) { onStage?.invoke(s) }

    // ---------------------------------------------------------------- aiguillage

    private val NEWS = Regex("\\b(?:actu|actus|actualites?|infos?|nouvelles|news|journal|quoi de neuf|derniere heure|a la une|gros titres)\\b")
    private val PHOTO = Regex("^(?:(?:montre|affiche|trouve|cherche)(?:[- ]moi)?|je veux voir)\\s+(?:une |des |la |les )?(?:photos?|images?|illustrations?)\\s+(?:de |d'|du |des |sur )?(.+)$")
    /** Sujet d'une demande de création d'image (« crée une image de… »), ou null. */
    fun imageRequest(text: String): String? = CREATE.find(Text.fold(text.trim()).trimEnd('?', '!', '.', ' '))?.groupValues?.get(1)

    private val CREATE = Regex("^(?:(?:cree|creer|genere|generer|dessine|dessiner|fais|faire|imagine|realise|produis|peins)(?:[- ]moi)?|je veux)\\s+(?:une |un |des |l'|la |le )?(?:image|images|illustration|dessin|photo|logo|affiche|peinture|tableau|portrait|visuel|fond d'ecran)s?\\b\\s*(?:de |d'|du |des |avec |representant |qui represente |montrant |sur )?(.*)$")
    private val COMPLEX = Regex("\\b(?:explique en detail|en detail|analyse|compare|redige|ecris(?:-moi)? (?:un|une)|code|programme|plan|strategie|pourquoi exactement|resume ce|traduis)\\b")

    /** Choisit et appelle le bon service ; essaie le suivant si l'un échoue. */
    fun ask(text: String, history: List<Msg>, keys: CloudKeys, userName: String): Answer {
        cancelled = false
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
            if (cancelled) throw CloudException("arrêté")
            val t0 = System.currentTimeMillis()
            stage("${api.title} réfléchit")
            try {
                val model = modelFor(api, keys)
                val out = chat(api, model, keys.key(api.id), system(userName), history.takeLast(12), if (context == null) text else "$context\n\nQuestion : $text")
                val ms = System.currentTimeMillis() - t0
                keys.record(api.id, ms, null)
                NoxLog.p("CLOUD", "${api.title} · $model · ${out.length} car. en $ms ms")
                return Answer(out.trim(), "${api.title} · ${model.substringAfterLast('/')}", ms)
            } catch (t: Throwable) {
                if (cancelled) throw CloudException("arrêté")
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
    // Repris de Lia (NvidiaImageApi, validé sur téléphone) : corps minimal {prompt, width, height},
    // file d'attente NVCF (202 + NVCF-REQID → suivi toutes les secondes), délai de première réponse
    // borné à 150 s, 6 min au total, image dans artifacts[0].base64 (ou data[0].b64_json).

    /** Modèles d'image NVIDIA hébergés (ai.api.nvidia.com/v1/genai/<modèle>) ; schnell = rapide, dev = plus fin. */
    val IMAGE_MODELS = listOf("black-forest-labs/flux.1-dev", "black-forest-labs/flux.1-schnell")

    // Styles et netteté repris de Lia (NvidiaImageStyle, NvidiaImagePromptBuilder)
    private val STYLES = listOf(
        Regex("\\b(?:aquarelle)\\b") to "watercolor painting, soft washes of color",
        Regex("\\b(?:dessin|crayon|croquis|encre|esquisse)\\b") to "pencil or ink drawing, hand-drawn sketch style",
        Regex("\\b(?:manga|anime|bd|bande dessinee|cartoon|dessin anime)\\b") to "digital illustration, clean line art, vibrant colors",
        Regex("\\b(?:illustration|illustre)\\b") to "digital illustration",
        Regex("\\b(?:peinture|huile|tableau|toile)\\b") to "painting, visible brush strokes",
        Regex("\\b(?:affiche|poster|logo|flyer)\\b") to "poster design, bold graphic layout, clean typography-friendly composition",
        Regex("\\b(?:concept art|fantasy|science-fiction|sci-fi)\\b") to "concept art, professional visual development artwork",
        Regex("\\b(?:photo|photographie|realiste|portrait)\\b") to "photorealistic photograph, realistic lighting and textures",
    )
    private const val FLUX_SHARP = "crisp fine details, high resolution"
    private const val FLUX_DEEP_FOCUS = "deep focus photograph shot at f/11, the subject and the whole background in sharp focus"

    /** Description envoyée à FLUX : la demande d'abord, intacte, puis le style et la netteté (comme Lia). */
    fun imagePrompt(request: String): String {
        val f = Text.fold(request)
        val parts = mutableListOf(request.trim())
        if (Regex("\\b(?:enfant|enfants|fille|fillette|garcon|bebe|ado|adolescent|petite fille|petit garcon)\\b").containsMatchIn(f)) parts += "fully clothed in everyday clothes"
        val style = STYLES.firstOrNull { it.first.containsMatchIn(f) }
        style?.let { parts += it.second }
        val photoLike = style == null || style.second.startsWith("photorealistic")
        if (photoLike) parts += FLUX_DEEP_FOCUS
        parts += FLUX_SHARP
        return parts.joinToString(", ")
    }

    /** Format : portrait 768×1344, paysage 1344×768, sinon carré 1024 (tailles documentées de FLUX, comme Lia). */
    private fun dimensions(request: String): Pair<Int, Int> {
        val f = Text.fold(request)
        return when {
            Regex("\\b(?:portrait|vertical|verticale|fond d'ecran|story|telephone)\\b").containsMatchIn(f) -> 768 to 1344
            Regex("\\b(?:paysage|panoramique|horizontal|horizontale|banniere|16/9|16:9)\\b").containsMatchIn(f) -> 1344 to 768
            else -> 1024 to 1024
        }
    }

    private const val GENAI = "https://ai.api.nvidia.com/v1/genai"
    private const val NVCF_STATUS = "https://api.nvcf.nvidia.com/v2/nvcf/pexec/status"
    private const val FIRST_ANSWER_MS = 150_000L
    private const val MAX_TOTAL_MS = 360_000L

    private fun create(original: String, subject: String, history: List<Msg>, keys: CloudKeys): Answer {
        val t0 = System.currentTimeMillis()
        // Comme dans Lia : la demande telle quelle (FLUX lit les phrases entières), puis style et netteté
        val request = subject.trim().ifBlank { original.trim() }
        val prompt = imagePrompt(request)
        val (w, h) = dimensions(original)
        var model = modelFor(Api.NVIDIA, keys)
        val img = try { image(prompt, model, keys, w, h) } catch (t: Throwable) {
            val busy = t !is CloudException || Regex("ne répond|n'a pas terminé|HTTP 5|expirée").containsMatchIn(t.message.orEmpty())
            if (!cancelled && busy && model.contains("flux.1-dev")) {
                NoxLog.w("CLOUD", "FLUX.1-dev indisponible (${t.message}), nouvel essai avec FLUX.1-schnell")
                model = "black-forest-labs/flux.1-schnell"
                stage("FLUX.1-dev ne répond pas : j'essaie FLUX.1-schnell")
                try { image(prompt, model, keys, w, h) } catch (t2: Throwable) {
                    keys.record(Api.NVIDIA.id, 0, t2.message); throw if (t2 is CloudException) t2 else CloudException("NVIDIA : ${t2.message}")
                }
            } else {
                keys.record(Api.NVIDIA.id, 0, t.message); throw if (t is CloudException) t else CloudException("NVIDIA : ${t.message}")
            }
        }
        val ms = System.currentTimeMillis() - t0
        keys.record(Api.NVIDIA.id, ms, null)
        NoxLog.p("CLOUD", "Image NVIDIA $model en $ms ms")
        return Answer("Voici ton image ($w×$h).", "NVIDIA · ${model.substringAfterLast('/')}", ms,
            created = listOf(img), imagePrompt = request)
    }

    private class NvResponse(val code: Int, val body: String, val requestId: String?)

    private fun image(prompt: String, model: String, keys: CloudKeys, width: Int = 1024, height: Int = 1024): ByteArray {
        val key = keys.key(Api.NVIDIA.id)
        val body = JSONObject().put("prompt", prompt).put("width", width).put("height", height).toString()
        val t0 = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - t0
        stage("NVIDIA dessine l'image (schnell ≈ 5-20 s, dev ≈ 20-75 s)")
        var r = nvSend(key, "$GENAI/$model", body, FIRST_ANSWER_MS)
            ?: throw CloudException("NVIDIA ne répond pas depuis ${FIRST_ANSWER_MS / 1000} s (serveurs surchargés) : réessaie plus tard ou avec FLUX.1-schnell.")
        NoxLog.i("CLOUD", "NVIDIA image HTTP ${r.code} en ${elapsed()} ms · suivi ${if (r.requestId != null) "oui" else "non"}")
        // File d'attente NVCF : 202 (ou 504 avec identifiant) = travail en cours, on suit son état
        var polls = 0
        while (r.code == 202 || (r.code == 504 && r.requestId != null)) {
            val id = r.requestId ?: throw CloudException("NVIDIA a mis l'image en file d'attente sans identifiant de suivi.")
            if (elapsed() > MAX_TOTAL_MS) throw CloudException("NVIDIA n'a pas terminé l'image en ${MAX_TOTAL_MS / 60_000} minutes (file d'attente chargée) : réessaie plus tard.")
            if (cancelled) throw CloudException("arrêté")
            stage("NVIDIA : image en file d'attente, suivi n°${polls + 1}")
            Thread.sleep(1000)
            r = nvSend(key, "$NVCF_STATUS/$id", null, null)
                ?: throw CloudException("NVIDIA ne répond plus pendant le suivi de l'image : réessaie plus tard.")
            polls++
        }
        if (polls > 0 && r.code == 404) throw CloudException("NVIDIA n'a pas pu terminer l'image (demande expirée après ${elapsed() / 60_000} min) : réessaie plus tard.")
        if (r.code !in 200..299) {
            val why = try { JSONObject(r.body).let { j -> j.optString("detail").ifBlank { j.optString("title") }.ifBlank { j.optJSONObject("error")?.optString("message").orEmpty() } } } catch (_: Throwable) { "" }
            throw CloudException(when (r.code) {
                401 -> "clé NVIDIA refusée (401)"
                403 -> "accès refusé à ce modèle (403) : vérifie sur build.nvidia.com que la clé y a droit"
                404 -> "modèle NVIDIA introuvable (404)"
                422 -> "demande refusée par NVIDIA (422)"
                429 -> "quota NVIDIA atteint (429), réessaie dans un moment"
                else -> "NVIDIA HTTP ${r.code}"
            } + if (why.isBlank()) "" else " · ${why.take(140)}")
        }
        val json = try { JSONObject(r.body) } catch (_: Throwable) { throw CloudException("NVIDIA a répondu sans image exploitable") }
        val art = json.optJSONArray("artifacts")?.optJSONObject(0)
        if (art?.optString("finishReason") == "CONTENT_FILTERED") throw CloudException("image refusée par le filtre de contenu de NVIDIA")
        val b64 = art?.optString("base64")?.takeIf { it.isNotBlank() }
            ?: json.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json")?.takeIf { it.isNotBlank() }
            ?: throw CloudException("NVIDIA a répondu sans image exploitable")
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }

    /** POST (ou GET si [body] est nul). Null si [deadlineMs] passe avant la première réponse (connexion coupée). */
    private fun nvSend(key: String, url: String, body: String?, deadlineMs: Long?): NvResponse? {
        if (cancelled) throw CloudException("arrêté")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body != null) "POST" else "GET"
            connectTimeout = 20_000
            readTimeout = 310_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        current = c
        val hit = java.util.concurrent.atomic.AtomicBoolean(false)
        val timer = deadlineMs?.let { ms ->
            java.util.Timer("nox-nvidia", true).apply {
                schedule(object : java.util.TimerTask() { override fun run() { hit.set(true); c.disconnect() } }, ms)
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = try { c.responseCode } catch (t: Throwable) {
                if (cancelled) throw CloudException("arrêté")
                if (hit.get()) return null
                throw t
            } finally { timer?.cancel() }
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return NvResponse(code, text, c.getHeaderField("NVCF-REQID"))
        } catch (t: java.io.IOException) {
            if (cancelled) throw CloudException("arrêté")
            throw t
        } finally {
            if (current === c) current = null
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- GNews

    private val NEWS_WORDS = Regex("\\b(?:donne(?:-moi)?|dis(?:-moi)?|quelles? sont|c'est quoi|les|des|derniere?s?|dernieres|actu|actus|actualites?|infos?|nouvelles|news|du jour|aujourd'hui|sur|de|du|la|le|quoi de neuf|a la une|gros titres|en ce moment|recentes?)\\b")

    private fun news(original: String, f: String, history: List<Msg>, keys: CloudKeys, userName: String): Answer {
        val t0 = System.currentTimeMillis()
        stage("Je cherche les actualités")
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
        stage("Je résume ${list.size} articles")
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
        stage("Je cherche des photos")
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
                cancelled = false
                val model = modelFor(Api.NVIDIA, keys)
                val img = image("a single small red circle on a white background", model, keys)
                "OK · image de ${img.size / 1024} Ko générée avec ${model.substringAfterLast('/')}"
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
        if (cancelled) throw CloudException("arrêté")
        val c = URL(url).openConnection() as HttpURLConnection
        current = c
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
        } catch (t: java.io.IOException) {
            if (cancelled) throw CloudException("arrêté")
            throw t
        } finally {
            if (current === c) current = null
            c.disconnect()
        }
    }
}
