package com.selfmod.agent.llm

/**
 * Maps OpenAI-style chat history onto the (role, content) pairs the on-device
 * engine accepts (M9).
 *
 * The on-device path previously filtered messages down to system/user/assistant,
 * silently discarding `tool` results — after M8 made SessionStore round-trip
 * them, a native-tools conversation restored onto the on-device engine would
 * lose every tool outcome. Instead we keep all turns:
 *  - `tool` results become user turns prefixed with a marker, and adjacent
 *    results from the same tool round are merged into one turn;
 *  - an assistant turn that only carries tool_calls gets a compact text
 *    summary so the model still sees what happened.
 */
object OnDevicePrompts {

    /**
     * Cache token for [foldCached]. The on-device agent loop ([LlmClient.chatOnDevice])
     * is called once per agent step with the *same* growing [history] list, so
     * re-folding the whole history every step wastes work on the decode-critical
     * IO thread (the H9/H10 "don't steal CPU from the decode thread" concern).
     * This cache lets a step reuse the previously folded prefix and only fold the
     * newly appended tail.
     *
     * Correctness rests on two cheap guards checked in [foldCached]:
     *  1. reference equality of the history list, and
     *  2. a [tailSentinel] — the last message object of the cached prefix. A list
     *     that was cleared in place and refilled reuses the same instance but
     *     replaces every element with a fresh [ChatMessage], so the sentinel no
     *     longer matches and we fall back to a full fold. The cache is therefore
     *     exact without ever re-scanning the prefix.
     */
    data class Cache(
        val history: List<ChatMessage>,
        val prefixLen: Int,
        val prefixPairs: List<Pair<String, String>>,
        val prevWasTool: Boolean,
        val tailSentinel: ChatMessage?,
    )

    fun fold(messages: List<ChatMessage>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val fromTool = ArrayList<Boolean>()
        messages.forEach { m ->
            when (m.role) {
                "system" -> {
                    out.add("system" to m.content)
                    fromTool.add(false)
                }
                "assistant" -> {
                    out.add("assistant" to assistantText(m))
                    fromTool.add(false)
                }
                "tool" -> {
                    val text = toolResultText(m)
                    if (fromTool.isNotEmpty() && fromTool.last()) {
                        val i = out.size - 1
                        out[i] = out[i].first to (out[i].second + "\n\n" + text)
                    } else {
                        out.add("user" to text)
                        fromTool.add(true)
                    }
                }
                else -> {
                    out.add("user" to m.content)
                    fromTool.add(false)
                }
            }
        }
        return out
    }

    /**
     * Incremental variant of [fold] for the on-device agent loop. When [cache]
     * is still valid for [messages] (same list instance, only appended to since
     * the cache was built — proven by [Cache.tailSentinel]), only the newly
     * added tail is folded and merged onto the cached prefix pairs; otherwise it
     * falls back to a full [fold]. The returned [Cache] must be threaded into
     * the next call. The result is always identical to `fold(messages)`.
     */
    fun foldCached(
        messages: List<ChatMessage>,
        cache: Cache?,
    ): Pair<List<Pair<String, String>>, Cache> {
        val valid = cache != null && cache.history === messages && messages.size >= cache.prefixLen
        // Guard 2: the message at the prefix boundary must be the exact object we
        // cached, so an in-place cleared-and-refilled list (same instance, new
        // elements) is detected and discarded instead of served stale.
        val useCache = valid && (
            cache!!.prefixLen == 0 ||
                (cache.prefixLen - 1 < messages.size &&
                    messages[cache.prefixLen - 1] === cache.tailSentinel)
            )
        val out = if (useCache) ArrayList(cache!!.prefixPairs) else ArrayList()
        var prevWasTool = useCache && cache!!.prevWasTool
        val start = if (useCache) cache!!.prefixLen else 0
        for (i in start until messages.size) {
            val m = messages[i]
            when (m.role) {
                "system" -> {
                    out.add("system" to m.content)
                    prevWasTool = false
                }
                "assistant" -> {
                    out.add("assistant" to assistantText(m))
                    prevWasTool = false
                }
                "tool" -> {
                    val text = toolResultText(m)
                    if (prevWasTool) {
                        val idx = out.size - 1
                        out[idx] = out[idx].first to (out[idx].second + "\n\n" + text)
                    } else {
                        out.add("user" to text)
                    }
                    prevWasTool = true
                }
                else -> {
                    out.add("user" to m.content)
                    prevWasTool = false
                }
            }
        }
        val result = out.toList()
        val sentinel = if (messages.isEmpty()) null else messages[messages.size - 1]
        return result to Cache(messages, messages.size, result, prevWasTool, sentinel)
    }

    private fun assistantText(m: ChatMessage): String = m.content.ifBlank {
        m.toolCalls.joinToString("\n") { "[调用工具 ${it.function.name}]" }
    }

    private fun toolResultText(m: ChatMessage): String = buildString {
        if (!m.name.isNullOrBlank()) append("[工具结果 ").append(m.name).append("]\n")
        append(m.content)
    }
}
