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

    /**
     * P1-4: agent memory now lives in an encrypted SharedPreferences rather than the
     * plaintext `mem_*` keys, so facts the agent is told to remember can't be read
     * back out of a world-readable prefs file or an `adb backup`.
     */
    private val secureMemory = SecureMemoryStore(context, prefs).also { it.migrateIfNeeded() }

    val keysEncrypted: Boolean get() = secrets.encrypted

    val keysInitIssue: String? get() = secrets.initIssue

    /** True once the encrypted memory backend initialised (false ⇒ legacy fallback). */
    val memoryEncrypted: Boolean get() = secureMemory.available

    /**
     * M5 — moves the legacy-key migration out of the read path. Old versions of
     * [llmConfig] and [profiles] did `secrets.setCurrent(...)` / `secrets.setProfile(...)`
     * and rewrote the config JSON inside their getter, so every read had a write side
     * effect (and a crash loop on a wedged SecretStore). The migration now happens once,
     * from [App.initPersistence], and the getters are pure.
     *
     * Returns the number of keys relocated from prefs JSON into the encrypted store, so
     * callers and tests can assert "exactly once, not on every read".
     */
    fun migrateLegacySecretsIfNeeded(): Int {
        val sink = SettingsStoreSecretSink(secrets)
        val outcome = SecretMigrator.migrate(
            llmJson = prefs.getString(KEY_LLM, null),
            profilesJson = prefs.getString(KEY_PROFILES, null),
            sink = sink,
            decodeConfig = ::decodeConfig,
            encodeConfig = { encodeConfig(it).toString() },
        )
        if (outcome.cleanedLlm != null) persistConfig(outcome.cleanedLlm)
        if (outcome.cleanedProfilesJson != null) {
            prefs.edit().putString(KEY_PROFILES, outcome.cleanedProfilesJson).apply()
        }
        return outcome.relocated
    }

    fun llmConfig(): LlmConfig {
        val raw = prefs.getString(KEY_LLM, null) ?: return LlmConfig.DEFAULT
        val cfg = decodeConfig(raw) ?: LlmConfig.DEFAULT
        val stored = secrets.getCurrent()
        // Pure read — no migration side effect. Legacy keys are relocated exactly once
        // by [migrateLegacySecretsIfNeeded] at startup.
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
            // Pure read — no secret write side effect.
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

    /** Which session the chat screen reopens after a process death. */
    fun currentSessionId(): String? = prefs.getString(KEY_CURRENT_SESSION, null)

    fun setCurrentSessionId(id: String?) {
        prefs.edit().putString(KEY_CURRENT_SESSION, id).apply()
    }

    fun memoryGet(key: String): String? = secureMemory.get(key)

    fun memorySet(key: String, value: String) {
        secureMemory.set(key, value)
    }

    fun memoryKeys(): List<String> = secureMemory.keys()

    fun memoryClear(key: String) = secureMemory.remove(key)

    // ---- P0-2: persistent high-risk tool approvals + audit log ----

    /**
     * Tools the user chose to "always allow". Persisted so the choice survives a
     * process restart (the in-memory [AgentViewModel.alwaysAllow] set is seeded
     * from this on startup).
     */
    fun approvedTools(): Set<String> =
        runCatching { JSONArray(prefs.getString(KEY_APPROVED, "[]")).let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() } }
            .getOrDefault(emptySet())

    fun addApprovedTool(name: String) {
        val set = approvedTools() + name
        prefs.edit().putString(KEY_APPROVED, JSONArray(set).toString()).apply()
    }

    fun removeApprovedTool(name: String) {
        val set = approvedTools() - name
        prefs.edit().putString(KEY_APPROVED, JSONArray(set).toString()).apply()
    }

    fun isToolApproved(name: String): Boolean = name in approvedTools()

    /**
     * Append-only audit trail of every approval / denial decision, with the source
     * ("persisted" = auto-approved from the stored allow-list, "user" = explicit tap,
     * "denied" = explicit denial). Capped at [APPROVAL_LOG_MAX] entries.
     */
    fun approvalLog(): List<ApprovalEntry> {
        val raw = prefs.getString(KEY_APPROVAL_LOG, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<ApprovalEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += ApprovalEntry(
                ts = o.optLong("ts"),
                tool = o.optString("tool"),
                granted = o.optBoolean("granted"),
                source = o.optString("source"),
            )
        }
        return out
    }

    fun logApproval(tool: String, granted: Boolean, source: String) {
        val list = approvalLog().toMutableList()
        list.add(0, ApprovalEntry(System.currentTimeMillis(), tool, granted, source))
        val trimmed = if (list.size > APPROVAL_LOG_MAX) list.take(APPROVAL_LOG_MAX) else list
        val arr = JSONArray()
        trimmed.forEach { e ->
            arr.put(JSONObject().apply {
                put("ts", e.ts); put("tool", e.tool)
                put("granted", e.granted); put("source", e.source)
            })
        }
        prefs.edit().putString(KEY_APPROVAL_LOG, arr.toString()).apply()
    }

    // ---- P1-3: declared plugin permission manifest ----

    fun pluginPermissions(name: String): List<String> {
        val raw = prefs.getString("$PLUGIN_PERM_PREFIX$name", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrDefault(emptyList())
    }

    fun setPluginPermissions(name: String, perms: List<String>) {
        prefs.edit().putString("$PLUGIN_PERM_PREFIX$name", JSONArray(perms).toString()).apply()
    }

    // ---- P0-1: auto memory distillation toggle ----

    fun autoMemory(): Boolean = prefs.getBoolean(KEY_AUTO_MEMORY, true)
    fun setAutoMemory(on: Boolean) = prefs.edit().putBoolean(KEY_AUTO_MEMORY, on).apply()

    /** One audit-log row. */
    data class ApprovalEntry(
        val ts: Long,
        val tool: String,
        val granted: Boolean,
        val source: String,
    )

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
        private const val KEY_CURRENT_SESSION = "current_session_v1"
        private const val MEM_PREFIX = "mem_"
        private const val KEY_APPROVED = "approved_tools_v1"
        private const val KEY_APPROVAL_LOG = "approval_log_v1"
        private const val KEY_AUTO_MEMORY = "auto_memory_v1"
        private const val PLUGIN_PERM_PREFIX = "plugin_perm_"
        private const val APPROVAL_LOG_MAX = 100
    }
}
