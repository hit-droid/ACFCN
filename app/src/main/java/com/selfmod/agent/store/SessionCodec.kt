package com.selfmod.agent.store

import com.selfmod.agent.llm.ChatMessage
import com.selfmod.agent.llm.ToolCall
import com.selfmod.agent.llm.ToolFunction
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for persisted chat history (M8).
 *
 * The previous store dropped [ChatMessage.toolCalls], so a restored native-tool
 * conversation contained `tool` results with no matching assistant `tool_calls`
 * block — an invalid OpenAI sequence. This codec round-trips tool calls and also
 * drops leading orphan `tool` messages left by window truncation.
 */
object SessionCodec {
    const val MAX_MESSAGES = 80
    const val MAX_CONTENT = 8000

    fun encode(messages: List<ChatMessage>): String {
        val window = dropLeadingOrphanTools(
            messages.filter { it.role != "system" }.takeLast(MAX_MESSAGES),
        )
        val arr = JSONArray()
        window.forEach { m ->
            arr.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content.take(MAX_CONTENT))
                if (!m.name.isNullOrBlank()) put("name", m.name)
                if (!m.toolCallId.isNullOrBlank()) put("toolCallId", m.toolCallId)
                if (m.toolCalls.isNotEmpty()) {
                    val tcArr = JSONArray()
                    m.toolCalls.forEach { tc ->
                        tcArr.put(JSONObject().apply {
                            put("id", tc.id)
                            put("type", tc.type)
                            put(
                                "function",
                                JSONObject().apply {
                                    put("name", tc.function.name)
                                    put("arguments", tc.function.arguments)
                                },
                            )
                        })
                    }
                    put("toolCalls", tcArr)
                }
            })
        }
        return arr.toString()
    }

    fun decode(raw: String): List<ChatMessage> {
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role")
            if (role.isBlank() || role == "system") continue
            out += ChatMessage(
                role = role,
                content = o.optString("content"),
                name = o.optString("name").ifBlank { null },
                toolCalls = parseToolCalls(o.optJSONArray("toolCalls")),
                toolCallId = o.optString("toolCallId").ifBlank { null },
            )
        }
        return dropLeadingOrphanTools(out)
    }

    private fun parseToolCalls(arr: JSONArray?): List<ToolCall> {
        if (arr == null) return emptyList()
        val out = ArrayList<ToolCall>()
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function") ?: continue
            out += ToolCall(
                id = tc.optString("id"),
                type = tc.optString("type", "function"),
                function = ToolFunction(
                    name = fn.optString("name"),
                    arguments = fn.optString("arguments", "{}"),
                ),
            )
        }
        return out
    }

    /** A `tool` message needs a preceding assistant `tool_calls`; drop a leading run. */
    private fun dropLeadingOrphanTools(messages: List<ChatMessage>): List<ChatMessage> {
        val firstNonTool = messages.indexOfFirst { it.role != "tool" }
        return when {
            firstNonTool < 0 -> emptyList()
            firstNonTool == 0 -> messages
            else -> messages.subList(firstNonTool, messages.size).toList()
        }
    }
}
