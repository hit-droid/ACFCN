package com.selfmod.agent.store

import android.content.Context
import com.selfmod.agent.llm.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<ChatMessage> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role")
            if (role == "system") continue
            out += ChatMessage(
                role = role,
                content = o.optString("content"),
                name = o.optString("name").ifBlank { null },
                toolCallId = o.optString("toolCallId").ifBlank { null },
            )
        }
        return out
    }

    fun save(messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.filter { it.role != "system" }.takeLast(80).forEach { m ->
            arr.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content.take(8000))
                if (!m.name.isNullOrBlank()) put("name", m.name)
                if (!m.toolCallId.isNullOrBlank()) put("toolCallId", m.toolCallId)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS = "selfmod_session"
        private const val KEY = "history_v1"
    }
}
