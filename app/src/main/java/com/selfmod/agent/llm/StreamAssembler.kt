package com.selfmod.agent.llm

import org.json.JSONObject

class StreamAssembler {
    private val content = StringBuilder()
    private val tools = LinkedHashMap<Int, ToolAcc>()
    var finishReason: String = "stop"
        private set

    fun applyLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        if (!trimmed.startsWith("data:")) return null
        val data = trimmed.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return null
        return applyJson(data)
    }

    fun applyJson(raw: String): String? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!obj.has("choices")) {
            val piece = obj.optString("content")
            if (piece.isNotEmpty() && piece != "null") {
                content.append(piece)
                if (obj.optBoolean("stop") || obj.optBoolean("done")) finishReason = "stop"
                return piece
            }
        }
        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val reason = choice.optString("finish_reason")
        if (reason.isNotBlank() && reason != "null") finishReason = reason
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return null
        val piece = delta.optString("content")
        if (piece.isNotEmpty() && piece != "null") {
            content.append(piece)
            return piece
        }
        delta.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tc = arr.optJSONObject(i) ?: continue
                val idx = if (tc.has("index")) tc.optInt("index") else i
                val acc = tools.getOrPut(idx) { ToolAcc() }
                if (tc.has("id")) {
                    val id = tc.optString("id")
                    if (id.isNotBlank()) acc.id = id
                }
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isNotBlank()) acc.name += name
                acc.args += fn.optString("arguments")
            }
        }
        return null
    }

    fun result(raw: String = ""): ChatResult {
        val calls = tools.entries.sortedBy { it.key }.map { (_, t) ->
            ToolCall(
                id = t.id.ifBlank { "call_${t.name}" },
                function = ToolFunction(t.name, t.args.ifBlank { "{}" }),
            )
        }
        return ChatResult(
            content = content.toString(),
            toolCalls = calls,
            raw = raw,
            finishReason = if (calls.isNotEmpty() && finishReason == "stop") "tool_calls" else finishReason,
        )
    }

    private class ToolAcc {
        var id: String = ""
        var name: String = ""
        var args: String = ""
    }
}
