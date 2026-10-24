package com.selfmod.agent.memory

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Long-term, cross-session memory for the agent.
 *
 * The original `set_memory` KV store let the agent *write* facts but never
 * *retrieve* them — so experience never compounded. This layer adds the missing
 * half: memories are stored with tokens and a kind, and [recall] returns the
 * most relevant ones for a query so they can be injected into the system prompt.
 *
 * Retrieval is a transparent token-overlap score (Jaccard of the shared token
 * set). It needs no embeddings, so it runs fully offline on-device. The agent
 * can also call the `remember` tool to persist a learning explicitly, and
 * [summarizeAndStore] folds a finished conversation into a few durable facts.
 */
class MemoryService internal constructor(private val prefs: SharedPreferences) {

    /**
     * Context-based constructor used by [com.selfmod.agent.App]. Delegates to the
     * internal [SharedPreferences]-backed primary so unit tests can also drive the
     * store with an in-memory backend — no Robolectric / Android context needed.
     */
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    /** A single remembered fact. [tokens] is the precomputed token set for scoring. */
    data class Memory(
        val id: String,
        val text: String,
        val kind: String,
        val ts: Long,
        val tokens: List<String>,
    )

    enum class Kind(val value: String) {
        GENERAL("general"),
        USER("user"),
        PREFERENCE("preference"),
        LESSON("lesson"),
    }

    @Synchronized
    fun record(text: String, kind: Kind = Kind.GENERAL): Memory {
        val clean = text.trim()
        if (clean.isEmpty()) throw IllegalArgumentException("memory text empty")
        val tokens = tokenize(clean)
        val mem = Memory(
            id = UUID.randomUUID().toString(),
            text = clean,
            kind = kind.value,
            ts = System.currentTimeMillis(),
            tokens = tokens,
        )
        val all = load().toMutableList()
        // Drop an identical earlier fact to avoid duplicates piling up.
        all.removeAll { it.text == clean }
        all.add(0, mem)
        // Cap the store; oldest first out.
        val trimmed = if (all.size > MAX_MEMORIES) all.take(MAX_MEMORIES) else all
        save(trimmed)
        return mem
    }

    /** Top-[k] memories most relevant to [query], scored by token overlap. */
    @Synchronized
    fun recall(query: String, k: Int = DEFAULT_K, kinds: Set<Kind>? = null): List<Memory> {
        val qTokens = tokenize(query).toSet()
        if (qTokens.isEmpty()) return emptyList()
        val all = load()
        return all.mapNotNull { m ->
            val keep = kinds == null || kinds.any { it.value == m.kind }
            if (!keep) return@mapNotNull null
            val score = jaccard(qTokens, m.tokens.toSet())
            if (score <= 0.0) null else m to score
        }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    /** All remembered user-profile facts, newest first (P2-8). */
    fun userProfile(): List<Memory> =
        load().filter { it.kind == Kind.USER.value || it.kind == Kind.PREFERENCE.value }

    fun all(): List<Memory> = load()

    fun clear() = prefs.edit().remove(KEY).apply()

    // ---- storage ----

    private fun load(): MutableList<Memory> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<Memory>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tk = o.optJSONArray("tokens")
            val tokens = ArrayList<String>()
            if (tk != null) for (j in 0 until tk.length()) tokens.add(tk.optString(j))
            out += Memory(
                id = o.optString("id"),
                text = o.optString("text"),
                kind = o.optString("kind", Kind.GENERAL.value),
                ts = o.optLong("ts"),
                tokens = tokens,
            )
        }
        return out
    }

    private fun save(list: List<Memory>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("text", m.text)
                put("kind", m.kind)
                put("ts", m.ts)
                put("tokens", JSONArray(m.tokens))
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "acfcn_memory_v1"
        private const val KEY = "memories"
        private const val MAX_MEMORIES = 500
        const val DEFAULT_K = 6

        /** Split into scoring tokens: latin/digit words + individual CJK chars. */
        fun tokenize(text: String): List<String> {
            val out = ArrayList<String>()
            val re = Regex("[A-Za-z0-9]+|[一-鿿]")
            for (m in re.findAll(text.lowercase())) {
                val t = m.value
                if (t.length == 1 && STOPWORDS.contains(t)) continue
                if (t.length >= 2 && STOPWORDS.contains(t)) continue
                out.add(t)
            }
            return out
        }

        private fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val inter = a.intersect(b).size
            if (inter == 0) return 0.0
            val union = a.size + b.size - inter
            return inter.toDouble() / union.toDouble()
        }

        private val STOPWORDS = setOf(
            "的", "了", "是", "我", "你", "他", "她", "它", "我们", "你们", "他们",
            "the", "a", "an", "and", "or", "to", "of", "in", "on", "is", "are",
            "i", "you", "we", "they", "this", "that", "with", "for", "at", "be",
        )
    }
}
