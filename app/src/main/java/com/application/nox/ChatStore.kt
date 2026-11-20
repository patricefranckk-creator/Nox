package com.application.nox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMsg(val fromUser: Boolean, var text: String, val time: Long = System.currentTimeMillis()) {
    /** Dit / lu à voix haute. */
    var voice = false
    /** Extraits de bibliothèque relus avant de répondre (titres). */
    var sources: List<String> = emptyList()
    /** Actions faites pendant la réponse. */
    val tools = mutableListOf<String>()
    /** Relances proposées. */
    var suggestions: List<String> = emptyList()
}

/** Historique sauvegardé sur le téléphone : Nox se souvient de la conversation entre deux lancements. */
class ChatStore(ctx: Context) {
    private val file = File(ctx.filesDir, "conversation.json")

    fun load(): MutableList<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        if (!file.exists()) return out
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += ChatMsg(o.getBoolean("u"), o.getString("t"), o.optLong("d")).apply {
                    voice = o.optBoolean("v")
                    sources = o.optJSONArray("s")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                    o.optJSONArray("o")?.let { a -> (0 until a.length()).forEach { tools += a.getString(it) } }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(msgs: List<ChatMsg>) {
        try {
            val arr = JSONArray()
            msgs.takeLast(MAX_SAVED).filter { it.text.isNotBlank() }.forEach {
                val o = JSONObject().put("u", it.fromUser).put("t", it.text).put("d", it.time)
                if (it.voice) o.put("v", true)
                if (it.sources.isNotEmpty()) o.put("s", JSONArray(it.sources))
                if (it.tools.isNotEmpty()) o.put("o", JSONArray(it.tools))
                arr.put(o)
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val MAX_SAVED = 200
    }
}
