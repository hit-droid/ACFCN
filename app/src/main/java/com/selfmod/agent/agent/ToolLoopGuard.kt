package com.selfmod.agent.agent

/**
 * Pure helpers for the agent's tool loop (H4).
 *
 * Two problems this addresses:
 *  - A model can get stuck re-issuing the exact same call (same name + same
 *    arguments) forever, burning iterations. [countOf] fingerprints each call so
 *    the caller can stop executing a call it has already run.
 *  - In non-native-tools mode the results of one turn must not be appended as
 *    several consecutive `user` messages — the history then violates the usual
 *    alternating role sequence. [formatObservations] collapses them into one
 *    message.
 */
class ToolLoopGuard {
    private val counts = LinkedHashMap<String, Int>()

    /** Normalized fingerprint: name + whitespace-insensitive arguments. */
    fun key(name: String, args: String): String = "$name|${canonArgs(args)}"

    /** Records a call and returns how many times this fingerprint has run now. */
    fun countOf(name: String, args: String): Int {
        val k = key(name, args)
        val n = (counts[k] ?: 0) + 1
        counts[k] = n
        return n
    }

    fun reset() = counts.clear()

    companion object {
        /** Argument JSON with insignificant whitespace and key order removed. */
        fun canonArgs(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return ""
            val canon = runCatching {
                val o = org.json.JSONObject(t)
                o.keys().asSequence().toList().sorted().joinToString(",") { k ->
                    "$k=${o.get(k)}"
                }
            }.getOrNull()
            return (canon ?: t.replace("\\s+".toRegex(), " ")).trim()
        }

        /**
         * Collapses a turn's tool results into a single user-message body so a
         * ReAct-style model sees one observation block, not N stacked turns (H4).
         */
        fun formatObservations(entries: List<Pair<String, String>>): String =
            entries.joinToString("\n\n") { (name, out) ->
                "Observation from $name:\n$out"
            } + "\n\nContinue. If the task is done, answer without an Action."
    }
}
