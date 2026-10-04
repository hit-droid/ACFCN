package com.selfmod.agent.store

import android.content.Context
import android.content.SharedPreferences
import com.selfmod.agent.llm.LlmConfig
import com.selfmod.agent.llm.SavedProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    val keysEncrypted: Boolean get() = secrets.encrypted

    val keysInitIssue: String? get() = secrets.initIssue

    fun llmConfig(): LlmConfig {
        val raw = prefs.getString(KEY_LLM, null) ?: return LlmConfig.DEFAULT
        val cfg = decodeConfig(raw) ?: LlmConfig.DEFAULT
        val stored = secrets.getCurrent()
        if (cfg.apiKey.isNotBlank() && stored.isBlank()) {
            secrets.setCurrent(cfg.apiKey)
            persistConfig(cfg.copy(apiKey = ""))
        }
        return cfg.copy(apiKey = stored.ifBlank { cfg.apiKey })
    }

    fun setLlmConfig(cfg: LlmConfig) {
        secrets.setCurrent(cfg.apiKey)
        persistConfig(cfg.copy(apiKey = ""))
    }

    fun activeProfileId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActiveProfileId(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun profiles(): List<SavedProfile> {
        val raw = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<SavedProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cfg = decodeConfig(o.optJSONObject("config")?.toString().orEmpty()) ?: continue
            val id = o.optString("id")
            val key = secrets.getProfile(id).ifBlank { cfg.apiKey }
            if (cfg.apiKey.isNotBlank() && secrets.getProfile(id).isBlank()) {
                secrets.setProfile(id, cfg.apiKey)
            }
            out += SavedProfile(
                id = id,
                name = o.optString("name"),
                config = cfg.copy(apiKey = key),
                lastOk = if (o.has("lastOk") && !o.isNull("lastOk")) o.optBoolean("lastOk") else null,
                lastLatencyMs = if (o.has("lastLatencyMs") && !o.isNull("lastLatencyMs")) o.optLong("lastLatencyMs") else null,
                lastCheckedAt = o.optLong("lastCheckedAt"),
            )
        }
        return out
    }

    fun upsertProfile(profile: SavedProfile): SavedProfile {
        secrets.setProfile(profile.id, profile.config.apiKey)
        val all = profiles().toMutableList()
        val stored = profile.copy(config = profile.config.copy(apiKey = ""))
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = stored else all.add(stored)
        persistProfiles(all.map { it.copy(config = it.config.copy(apiKey = "")) })
        return profile
    }

    fun saveCurrentAsProfile(name: String): SavedProfile {
        val p = SavedProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "未命名" },
            config = llmConfig(),
        )
        return upsertProfile(p)
    }

    fun deleteProfile(id: String) {
        secrets.removeProfile(id)
        persistProfiles(profiles().filter { it.id != id }.map { it.copy(config = it.config.copy(apiKey = "")) })
        if (activeProfileId() == id) setActiveProfileId(null)
    }

    fun applyProfile(id: String): SavedProfile? {
        val p = profiles().firstOrNull { it.id == id } ?: return null
        setLlmConfig(p.config)
        setActiveProfileId(id)
        return p
    }

    fun recordProbe(id: String, ok: Boolean, latencyMs: Long) {
        val p = profiles().firstOrNull { it.id == id } ?: return
        upsertProfile(p.copy(lastOk = ok, lastLatencyMs = latencyMs, lastCheckedAt = System.currentTimeMillis()))
    }

    fun offlineMode(): Boolean = prefs.getBoolean(KEY_OFFLINE, false)

    fun setOfflineMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE, on).apply()
    }

    fun onboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING, false)

    fun setOnboardingDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING, done).apply()
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

    private fun persistConfig(cfg: LlmConfig) {
        prefs.edit().putString(KEY_LLM, encodeConfig(cfg).toString()).apply()
    }

    private fun persistProfiles(list: List<SavedProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("config", encodeConfig(p.config.copy(apiKey = "")))
                if (p.lastOk != null) put("lastOk", p.lastOk) else put("lastOk", JSONObject.NULL)
                if (p.lastLatencyMs != null) put("lastLatencyMs", p.lastLatencyMs) else put("lastLatencyMs", JSONObject.NULL)
                put("lastCheckedAt", p.lastCheckedAt)
            })
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    private fun encodeConfig(cfg: LlmConfig): JSONObject = JSONObject().apply {
        put("baseUrl", cfg.baseUrl)
        put("apiKey", cfg.apiKey)
        put("model", cfg.model)
        put("temperature", cfg.temperature)
        put("maxTokens", cfg.maxTokens)
        put("timeoutSeconds", cfg.timeoutSeconds)
        put("kind", cfg.kind)
        put("profileName", cfg.profileName)
        put("supportsNativeTools", cfg.supportsNativeTools)
        put("onDeviceModelPath", cfg.onDeviceModelPath)
        put("onDeviceContext", cfg.onDeviceContext)
    }

    private fun decodeConfig(raw: String): LlmConfig? {
        if (raw.isBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            LlmConfig(
                baseUrl = o.optString("baseUrl", LlmConfig.DEFAULT.baseUrl),
                apiKey = o.optString("apiKey", ""),
                model = o.optString("model", LlmConfig.DEFAULT.model),
                temperature = o.optDouble("temperature", LlmConfig.DEFAULT.temperature),
                maxTokens = o.optInt("maxTokens", LlmConfig.DEFAULT.maxTokens),
                timeoutSeconds = o.optLong("timeoutSeconds", LlmConfig.DEFAULT.timeoutSeconds),
                kind = o.optString("kind", LlmConfig.KIND_CLOUD),
                profileName = o.optString("profileName", ""),
                supportsNativeTools = o.optBoolean("supportsNativeTools", true),
                onDeviceModelPath = o.optString("onDeviceModelPath", ""),
                onDeviceContext = o.optInt("onDeviceContext", 2048),
            )
        }.getOrNull()
    }

    companion object {
        private const val PREFS = "selfmod_prefs"
        private const val KEY_LLM = "llm_config_v1"
        private const val KEY_PROFILES = "llm_profiles_v1"
        private const val KEY_ACTIVE = "llm_active_profile"
        private const val KEY_OFFLINE = "offline_mode_v1"
        private const val KEY_ONBOARDING = "onboarding_done_v1"
        private const val MEM_PREFIX = "mem_"
    }
}
