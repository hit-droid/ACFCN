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
        return runCatching { t.run(argsJson) }
            .getOrElse { errJson("tool error: ${it.message ?: it.toString()}") }
    }

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
