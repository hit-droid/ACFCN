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

    private fun assistantText(m: ChatMessage): String = m.content.ifBlank {
        m.toolCalls.joinToString("\n") { "[调用工具 ${it.function.name}]" }
    }

    private fun toolResultText(m: ChatMessage): String = buildString {
        if (!m.name.isNullOrBlank()) append("[工具结果 ").append(m.name).append("]\n")
        append(m.content)
    }
}
