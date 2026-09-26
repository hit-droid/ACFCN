package com.selfmod.agent.offline

/**
 * llama.cpp's llama_chat_apply_template only accepts a *name* of a built-in
 * template, never a raw Jinja string. Models embed a full Jinja template in
 * their metadata, so we map it back to the closest built-in family.
 */
object OnDeviceTemplates {

    private val known = setOf(
        "chatml", "gemma", "llama3", "llama2", "mistral", "vicuna",
        "alpaca", "zephyr", "phi3", "qwen", "deepseek", "command-r", "openchat",
    )

    fun resolve(raw: String): String {
        if (raw.isBlank()) return "chatml"
        val lower = raw.trim().lowercase()
        if (lower in known) return lower
        return when {
            raw.contains("<start_of_turn>") -> "gemma"
            raw.contains("<|start_header_id|>") -> "llama3"
            raw.contains("<|im_start|>") -> "chatml"
            raw.contains("[INST]") -> "mistral"
            raw.contains("<|user|>") && raw.contains("<|assistant|>") -> "phi3"
            else -> "chatml"
        }
    }

    /**
     * Families with no real system role: fold every system message into the
     * first user turn so llama.cpp does not drop or reject them.
     */
    fun foldSystem(
        messages: List<Pair<String, String>>,
        family: String,
    ): List<Pair<String, String>> {
        val needsFold = family == "gemma" || family == "mistral"
        if (!needsFold) return messages
        val systems = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
        val rest = messages.filter { it.first != "system" }
        if (systems.isBlank()) return rest
        val out = ArrayList<Pair<String, String>>(rest.size + 1)
        var folded = false
        for (m in rest) {
            if (!folded && m.first == "user") {
                out.add("user" to (systems + "\n\n" + m.second))
                folded = true
            } else {
                out.add(m)
            }
        }
        if (!folded) out.add(0, "user" to systems)
        return out
    }
}
