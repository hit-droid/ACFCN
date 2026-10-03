package com.selfmod.agent.store

import android.content.Context
import com.selfmod.agent.llm.ChatMessage

class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<ChatMessage> = SessionCodec.decode(prefs.getString(KEY, "[]") ?: "[]")

    fun save(messages: List<ChatMessage>) {
        prefs.edit().putString(KEY, SessionCodec.encode(messages)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS = "selfmod_session"
        private const val KEY = "history_v1"
    }
}
