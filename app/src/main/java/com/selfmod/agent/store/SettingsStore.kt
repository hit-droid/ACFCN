package com.selfmod.agent.store

import android.content.Context
import android.content.SharedPreferences
import com.selfmod.agent.llm.LlmConfig
import org.json.JSONObject

/** Centralised persistence for LLM config and the agent's key-value memory. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun llmConfig(): LlmConfig {
        val raw = prefs.getString(KEY_LLM, null) ?: return LlmConfig.DEFAULT
        return runCatching {
            val o = JSONObject(raw)
            LlmConfig(
                baseUrl = o.optString("baseUrl", LlmConfig.DEFAULT.baseUrl),
                apiKey = o.optString("apiKey", ""),
                model = o.optString("model", LlmConfig.DEFAULT.model),
                temperature = o.optDouble("temperature", LlmConfig.DEFAULT.temperature),
                maxTokens = o.optInt("maxTokens", LlmConfig.DEFAULT.maxTokens),
                timeoutSeconds = o.optLong("timeoutSeconds", LlmConfig.DEFAULT.timeoutSeconds),
            )
        }.getOrDefault(LlmConfig.DEFAULT)
    }

    fun setLlmConfig(cfg: LlmConfig) {
        val o = JSONObject()
        o.put("baseUrl", cfg.baseUrl)
        o.put("apiKey", cfg.apiKey)
        o.put("model", cfg.model)
        o.put("temperature", cfg.temperature)
        o.put("maxTokens", cfg.maxTokens)
        o.put("timeoutSeconds", cfg.timeoutSeconds)
        prefs.edit().putString(KEY_LLM, o.toString()).apply()
    }

    fun memoryGet(key: String): String? = prefs.getString("$MEM_PREFIX$key", null)

    fun memorySet(key: String, value: String) {
        prefs.edit().putString("$MEM_PREFIX$key", value).apply()
    }

    fun memoryKeys(): List<String> = prefs.all.keys
        .filter { it.startsWith(MEM_PREFIX) }
        .map { it.removePrefix(MEM_PREFIX) }
        .sorted()

    fun memoryClear(key: String) = prefs.edit().remove("$MEM_PREFIX$key").apply()

    companion object {
        private const val PREFS = "selfmod_prefs"
        private const val KEY_LLM = "llm_config_v1"
        private const val MEM_PREFIX = "mem_"
    }
}
