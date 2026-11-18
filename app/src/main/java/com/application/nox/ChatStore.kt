package com.application.nox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMsg(val fromUser: Boolean, var text: String, val time: Long = System.currentTimeMillis())

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
                out += ChatMsg(o.getBoolean("u"), o.getString("t"), o.optLong("d"))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(msgs: List<ChatMsg>) {
        try {
            val arr = JSONArray()
            msgs.takeLast(MAX_SAVED).filter { it.text.isNotBlank() }.forEach {
                arr.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("d", it.time))
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
