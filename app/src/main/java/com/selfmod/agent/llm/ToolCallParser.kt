package com.selfmod.agent.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Extracts tool calls from local / offline models that emit ReAct or XML
 * instead of OpenAI `tool_calls`. Used so an imported GGUF served by
 * Ollama / llama.cpp / LM Studio can still drive the agent tool loop.
 */
object ToolCallParser {

    private val xmlBlock = Regex("<tool_call>\\s*([\\s\\S]*?)</tool_call>", RegexOption.IGNORE_CASE)
    private val invokeLine = Regex("(?im)^(?:invoke|call|tool)\\s*[:：]\\s*([A-Za-z0-9_\\-]+)\\s*$")
    private val actionLine = Regex("(?im)^Action\\s*[:：]\\s*([A-Za-z0-9_\\-]+)\\s*$")
    private val actionInputLine = Regex("(?im)^Action Input\\s*[:：]\\s*(.*)$")

    fun parse(text: String): List<ToolCall> {
        if (text.isBlank()) return emptyList()
        val xml = parseXmlBlocks(text)
        if (xml.isNotEmpty()) return xml
        val react = parseReact(text)
        if (react.isNotEmpty()) return react
        val json = parseBareJson(text)
        if (json.isNotEmpty()) return json
        return emptyList()
    }

    fun stripMarkup(text: String): String {
        var s = xmlBlock.replace(text, "").trim()
        s = s.replace(Regex("(?im)^Action\\s*[:：].*$"), "")
        s = s.replace(Regex("(?im)^Action Input\\s*[:：].*$"), "")
        return s.trim()
    }

    fun looksLikeToolUse(text: String): Boolean = parse(text).isNotEmpty()

    private fun parseXmlBlocks(text: String): List<ToolCall> {
        val out = ArrayList<ToolCall>()
        xmlBlock.findAll(text).forEachIndexed { i, m ->
            parsePayload(m.groupValues[1].trim(), i)?.let { out += it }
        }
        return out
    }

    private fun parsePayload(raw: String, index: Int): ToolCall? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            return fromJsonObject(trimmed, index)
        }
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val name = lines[0].removePrefix("name:").trim().trim('"')
        val rest = lines.drop(1).joinToString("\n").trim().ifEmpty { "{}" }
        val args = when {
            rest.startsWith("{") || rest.startsWith("[") -> rest
            else -> JSONObject().put("input", rest).toString()
        }
        if (name.isEmpty()) return null
        return ToolCall(
            id = "local_$index",
            function = ToolFunction(name = sanitizeName(name), arguments = args),
        )
    }

    private fun parseReact(text: String): List<ToolCall> {
        val actions = actionLine.findAll(text).toList()
        if (actions.isEmpty()) return emptyList()
        val inputs = actionInputLine.findAll(text).toList()
        val out = ArrayList<ToolCall>()
        actions.forEachIndexed { i, m ->
            val name = sanitizeName(m.groupValues[1])
            val argsRaw = inputs.getOrNull(i)?.groupValues?.get(1)?.trim().orEmpty()
            val args = normalizeArgs(argsRaw)
            out += ToolCall(
                id = "react_$i",
                function = ToolFunction(name = name, arguments = args),
            )
        }
        return out
    }

    private fun parseBareJson(text: String): List<ToolCall> {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        runCatching {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val out = ArrayList<ToolCall>()
                for (i in 0 until arr.length()) {
                    fromJsonObject(arr.getJSONObject(i).toString(), i)?.let { out += it }
                }
                return out
            }
            val obj = JSONObject(trimmed)
            if (obj.has("name") || obj.has("tool") || obj.has("function")) {
                return listOfNotNull(fromJsonObject(trimmed, 0))
            }
        }
        return emptyList()
    }

    private fun fromJsonObject(raw: String, index: Int): ToolCall? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val fn = o.optJSONObject("function")
        val name = sequenceOf(
            o.optString("name"),
            o.optString("tool"),
            fn?.optString("name").orEmpty(),
        ).firstOrNull { it.isNotBlank() } ?: return null
        val argsValue = when {
            o.has("arguments") -> o.get("arguments")
            o.has("args") -> o.get("args")
            o.has("parameters") -> o.get("parameters")
            fn != null && fn.has("arguments") -> fn.get("arguments")
            else -> JSONObject()
        }
        val args = when (argsValue) {
            is JSONObject -> argsValue.toString()
            is String -> normalizeArgs(argsValue)
            else -> JSONObject().put("input", argsValue.toString()).toString()
        }
        return ToolCall(
            id = o.optString("id").ifBlank { "json_$index" },
            function = ToolFunction(name = sanitizeName(name), arguments = args),
        )
    }

    private fun normalizeArgs(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "{}"
        if (t.startsWith("{") || t.startsWith("[")) return t
        return JSONObject().put("input", t).toString()
    }

    private fun sanitizeName(name: String): String =
        name.trim().trim('`', '"', '\'', '*').replace(" ", "_")
}
