package com.selfmod.agent.offline

/**
 * Keeps on-device chat history inside the model's context window.
 *
 * The native engine no longer needs to guess: we trim here first, keeping the
 * system prompt and the most recent turns, so llama.cpp never has to drop the
 * middle of a conversation behind our back.
 */
object ContextBudget {

    /** Rough token estimate. CJK is ~1 token/char, latin ~1 token/4 chars. */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code >= 0x2E80) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    /**
     * @param nCtx the model context window (tokens)
     * @param maxTokens reserved for the reply
     * @return how many tokens remain for the prompt, always >= 1
     */
    fun promptBudget(nCtx: Int, maxTokens: Int): Int {
        val reserve = maxTokens.coerceAtLeast(1)
        val left = nCtx - reserve
        return if (left >= 8) left else (nCtx / 2).coerceAtLeast(1)
    }

    /**
     * Drops the oldest non-system turns until the history fits [budgetTokens].
     * The system message (if any) and the newest turn are always kept.
     */
    fun trim(
        messages: List<Pair<String, String>>,
        budgetTokens: Int,
    ): List<Pair<String, String>> {
        if (messages.isEmpty()) return messages
        var total = messages.sumOf { estimateTokens(it.first) + estimateTokens(it.second) + 4 }
        if (total <= budgetTokens) return messages

        val out = ArrayDeque(messages)
        // Never drop a leading system prompt.
        val headKept = out.first().first == "system"
        while (out.size > 1 && total > budgetTokens) {
            // Keep the newest turn intact; drop the oldest droppable one.
            val idx = if (headKept) 1 else 0
            if (idx >= out.size - 1) break
            val removed = out.removeAt(idx)
            total -= estimateTokens(removed.first) + estimateTokens(removed.second) + 4
        }
        return out.toList()
    }
}
