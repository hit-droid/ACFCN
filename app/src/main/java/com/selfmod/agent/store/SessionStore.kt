package com.selfmod.agent.store

import android.content.Context
import com.selfmod.agent.llm.ChatMessage
import java.io.File
import java.util.UUID

/**
 * One row in the session list. Deliberately message-free — the list only needs
 * enough to render and sort, so switching sessions stays O(1) instead of
 * decoding every conversation on every open.
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/**
 * Multi-session chat history, one JSON file per session under
 * `filesDir/sessions/` plus an `index.json` summary array.
 *
 * Replaces the old single-slot SharedPreferences store (`selfmod_session` /
 * `history_v1`): the old layout kept exactly one conversation, so "清空" threw
 * the whole history away and there was no way back to an earlier task. The
 * legacy slot is migrated once, on first use, into a session titled after the
 * old history's first user message.
 *
 * Concurrency: every public method is synchronized on the store instance.
 * Callers hit it from the agent IO dispatcher and the main thread alike
 * (persist-on-run-finish vs. list refresh).
 */
class SessionStore(context: Context) {
    private val app = context.applicationContext
    private val legacyPrefs = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, DIR_NAME).apply { mkdirs() }
    private val indexFile = File(dir, INDEX_NAME)

    /** Declared before init so the migration guard is never read uninitialized. */
    private val migrated = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        migrateLegacyIfNeeded()
    }

    /** Newest first. */
    fun listSessions(): List<SessionSummary> = synchronized(this) {
        readIndex().sortedByDescending { it.updatedAt }
    }

    fun sessionFileExists(id: String): Boolean = synchronized(this) {
        safeId(id) != null && File(dir, "$id.json").exists()
    }

    /** Returns the decoded history, or an empty list for an unknown/corrupt id. */
    fun loadSession(id: String): List<ChatMessage> = synchronized(this) {
        val safe = safeId(id) ?: return emptyList()
        val file = File(dir, "$safe.json")
        if (!file.exists()) return emptyList()
        SessionCodec.decode(runCatching { file.readText() }.getOrDefault("[]"))
    }

    /**
     * Creates or replaces a session's messages and refreshes its index row.
     * [title] pins an explicit title (e.g. the first user message at save
     * time); when null the title is re-derived from the messages.
     */
    fun saveSession(id: String, messages: List<ChatMessage>, title: String? = null): Unit =
        synchronized(this) {
            val safe = safeId(id) ?: return
            val file = File(dir, "$safe.json")
            val tmp = File(dir, "$safe.json.tmp")
            // Write-then-rename: a kill mid-save must not leave a truncated
            // session that fails to decode on next launch.
            tmp.writeText(SessionCodec.encode(messages))
            if (!tmp.renameTo(file)) {
                file.writeText(SessionCodec.encode(messages))
                tmp.delete()
            }
            upsertIndex(
                SessionSummary(
                    id = safe,
                    title = title?.take(MAX_TITLE)?.ifBlank { null }
                        ?: deriveTitle(messages),
                    updatedAt = System.currentTimeMillis(),
                    messageCount = messages.count { it.role != "system" },
                ),
            )
        }

    fun deleteSession(id: String): Unit = synchronized(this) {
        val safe = safeId(id) ?: return
        File(dir, "$safe.json").delete()
        File(dir, "$safe.json.tmp").delete()
        writeIndex(readIndex().filter { it.id != safe })
    }

    /** Storage-panel "clear everything": drops all session files and the index. */
    fun deleteAll(): Unit = synchronized(this) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".tmp"))) f.delete()
        }
        indexFile.delete()
    }

    fun newSessionId(): String = UUID.randomUUID().toString()

    // ------------------------------------------------------------- internals

    /**
     * Old installs have exactly one history blob in SharedPreferences. Move it
     * into the first file-backed session (id kept stable as [LEGACY_SESSION_ID]
     * so a mid-migration crash can't fork the data), then clear the pref slot.
     * No-op once any session exists — exactly once, like the secret migration.
     */
    private fun migrateLegacyIfNeeded() {
        if (!migrated.compareAndSet(false, true)) return
        synchronized(this) {
            if (indexFile.exists() && readIndex().isNotEmpty()) return
            val raw = legacyPrefs.getString(LEGACY_KEY, null) ?: return
            val messages = SessionCodec.decode(raw)
            if (messages.isEmpty()) return
            val file = File(dir, "$LEGACY_SESSION_ID.json")
            file.writeText(SessionCodec.encode(messages))
            writeIndex(
                listOf(
                    SessionSummary(
                        id = LEGACY_SESSION_ID,
                        title = deriveTitle(messages),
                        updatedAt = System.currentTimeMillis(),
                        messageCount = messages.size,
                    ),
                ),
            )
            legacyPrefs.edit().remove(LEGACY_KEY).apply()
        }
    }

    private fun upsertIndex(entry: SessionSummary) {
        val all = readIndex().toMutableList()
        val idx = all.indexOfFirst { it.id == entry.id }
        if (idx >= 0) all[idx] = entry else all.add(entry)
        writeIndex(all)
    }

    private fun readIndex(): List<SessionSummary> {
        if (!indexFile.exists()) return emptyList()
        val arr = runCatching {
            org.json.JSONArray(runCatching { indexFile.readText() }.getOrDefault("[]"))
        }.getOrDefault(org.json.JSONArray())
        val out = ArrayList<SessionSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out += SessionSummary(
                id = id,
                title = o.optString("title").ifBlank { "新会话" },
                updatedAt = o.optLong("updatedAt"),
                messageCount = o.optInt("messageCount"),
            )
        }
        return out
    }

    private fun writeIndex(list: List<SessionSummary>) {
        val arr = org.json.JSONArray()
        list.forEach { s ->
            arr.put(
                org.json.JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("updatedAt", s.updatedAt)
                    put("messageCount", s.messageCount)
                },
            )
        }
        indexFile.writeText(arr.toString())
    }

    /** Ids become filenames — anything but a plain UUID would be a path hazard. */
    private fun safeId(id: String): String? =
        id.takeIf { it.isNotBlank() && it.length <= 64 && it.matches(Regex("[A-Za-z0-9_-]+")) }

    companion object {
        private const val DIR_NAME = "sessions"
        private const val INDEX_NAME = "index.json"
        private const val LEGACY_PREFS = "selfmod_session"
        private const val LEGACY_KEY = "history_v1"

        /**
         * Stable id for the migrated legacy history. A crash between writing the
         * session file and clearing the pref slot must not duplicate it, so the
         * id never changes.
         */
        const val LEGACY_SESSION_ID = "legacy-migrated"
        private const val MAX_TITLE = 40

        /** First user message, trimmed to one line; falls back for tool-only sessions. */
        fun deriveTitle(messages: List<ChatMessage>): String {
            val first = messages.firstOrNull { it.role == "user" }?.content
                ?.replace("\n", " ")
                ?.trim()
                ?.take(MAX_TITLE)
                .orEmpty()
            return first.ifBlank { "新会话" }
        }
    }
}
