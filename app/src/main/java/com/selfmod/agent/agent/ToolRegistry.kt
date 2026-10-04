package com.selfmod.agent.agent

import com.selfmod.agent.llm.ToolSpec
import org.json.JSONObject

/** A single tool the LLM can call: its spec plus the executor. */
data class ToolDef(
    val spec: ToolSpec,
    val run: (argsJson: String) -> String,   // returns a JSON string for the LLM
)

class ToolRegistry {
    private val tools = LinkedHashMap<String, ToolDef>()

    fun register(def: ToolDef) { tools[def.spec.name] = def }
    fun registerAll(defs: List<ToolDef>) { defs.forEach { register(it) } }

    fun specs(): List<ToolSpec> = tools.values.map { it.spec }

    fun invoke(name: String, argsJson: String): String {
        val t = tools[name]
            ?: return errJson("unknown tool: $name")
        return runCatching { t.run(coerceArgs(t.spec, argsJson)) }
            .getOrElse { errJson("tool error: ${it.message ?: it.toString()}") }
    }

    /**
     * L2: plain-text arguments used to be sprayed into `input`, `url` and `code`
     * at once, so a tool that declared several of those (or none of them) had to
     * guess which one the model actually meant — and `ui_notify`/`read_script`/
     * `get_memory` would silently read the wrong key.
     *
     * Now the tool's own JSON schema decides: the text goes into the first field
     * the schema asks for, honouring `required` and declaration order, typed as the
     * schema says. A tool that declares no fields gets `{}` — inventing keys would
     * only confuse its executor. A schema we cannot parse at all falls back to the
     * old spray, so a malformed spec can't break the call outright.
     */
    private fun coerceArgs(spec: ToolSpec, raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "{}"
        if (t.startsWith("{") || t.startsWith("[")) return t

        val declared = schemaFieldsInOrder(spec.parameters)
        if (declared == null) {
            return JSONObject().put("input", t).put("url", t).put("code", t).toString()
        }
        val required = requiredFields(spec.parameters)
        val target = declared.firstOrNull { it.first in required } ?: declared.firstOrNull()
            ?: return "{}"
        return JSONObject().put(target.first, typedText(target.second, t)).toString()
    }

    /** `input` used to arrive as a string for every tool; integers and booleans are now written in their declared type. */
    private fun typedText(type: String, text: String): Any = when (type) {
        "integer" -> text.toLongOrNull() ?: text
        "number" -> text.toDoubleOrNull() ?: text
        "boolean" -> text.toBooleanStrictOrNull() ?: text
        else -> text
    }

    /**
     * `properties` entries as (name, type) in **declaration order** — JSONObject
     * drops insertion order, so the schema source is scanned directly to keep the
     * "which field did you mean?" choice deterministic. Null when the schema itself
     * cannot be read.
     */
    private fun schemaFieldsInOrder(parameters: String): List<Pair<String, String>>? {
        val props = runCatching { JSONObject(parameters).optJSONObject("properties") }.getOrNull()
            ?: return null
        if (props.length() == 0) return emptyList()
        val declared = Regex(""""([A-Za-z_][A-Za-z0-9_]*)"\s*:\s*\{\s*"type"\s*:\s*"([a-z]+)"""")
            .findAll(parameters)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { props.has(it.first) }
            .toList()
        if (declared.isNotEmpty()) return declared
        // Field present but not in the scanned shape (e.g. $ref, nested one level deeper):
        // take the keys in whatever order we can read them rather than guessing a type.
        return props.keys().asSequence().toList()
            .map { it to (props.optJSONObject(it)?.optString("type") ?: "string") }
    }

    private fun requiredFields(parameters: String): Set<String> =
        runCatching {
            JSONObject(parameters).optJSONArray("required")
                ?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
        }.getOrNull() ?: emptySet()

    fun names(): List<String> = tools.keys.toList()

    companion object {
        fun okJson(message: String, extra: Map<String, Any?> = emptyMap()): String {
            val o = JSONObject()
            o.put("ok", true)
            o.put("message", message)
            extra.forEach { (k, v) ->
                o.put(k, if (v == null) JSONObject.NULL else v)
            }
            return o.toString()
        }

        fun errJson(message: String): String {
            val o = JSONObject()
            o.put("ok", false)
            o.put("error", message)
            return o.toString()
        }
    }
}
