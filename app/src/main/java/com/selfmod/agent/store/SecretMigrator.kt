package com.selfmod.agent.store

import com.selfmod.agent.llm.LlmConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * M5 — pure helper for relocating legacy plain-text keys from the LLM JSON blob
 * and the profile JSON blob into an encrypted secret store. Kept separate from
 * [SettingsStore] so the migration logic can be unit-tested without an Android
 * `SharedPreferences` (and therefore without Robolectric's SDK download).
 *
 * The shape mirrors what `SettingsStore.migrateLegacySecretsIfNeeded` used to do
 * inline, with one deliberate change: writes to the prefs side are reported back
 * via [MigrationOutcome] rather than applied here. The caller (production code
 * or a test) decides what to do with the cleaned blobs.
 */
internal object SecretMigrator {

    data class MigrationOutcome(
        val relocated: Int,
        /** Non-null when the LLM blob was rewritten with the key blanked. */
        val cleanedLlm: LlmConfig?,
        /** Non-null when the profiles blob was rewritten with keys blanked. */
        val cleanedProfilesJson: String?,
    )

    /**
     * @param llmJson current value of `prefs.getString("llm_config_v1", null)`.
     * @param profilesJson current value of `prefs.getString("llm_profiles_v1", null)`.
     * @param sink thin interface over the secret store — production code wires this
     *  to [SecretStore], tests can use any in-memory implementation.
     * @param decodeConfig, encodeConfig injected so the migrator does not depend on
     *  Android `SharedPreferences` or `Context`.
     */
    fun migrate(
        llmJson: String?,
        profilesJson: String?,
        sink: SecretSink,
        decodeConfig: (String) -> LlmConfig?,
        encodeConfig: (LlmConfig) -> String,
    ): MigrationOutcome {
        var relocated = 0
        var cleanedLlm: LlmConfig? = null
        var cleanedProfilesJson: String? = null

        if (!llmJson.isNullOrBlank()) {
            val cfg = decodeConfig(llmJson)
            if (cfg != null && cfg.apiKey.isNotBlank() && sink.getCurrent().isBlank()) {
                sink.setCurrent(cfg.apiKey)
                cleanedLlm = cfg.copy(apiKey = "")
                relocated++
            }
        }

        if (!profilesJson.isNullOrBlank()) {
            val arr = runCatching { JSONArray(profilesJson) }.getOrNull()
            if (arr != null) {
                val newArr = JSONArray()
                var dirty = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    // `persistProfiles` writes config as a String (JSON-encoded).
                    // If some future writer changes that to a nested JSONObject
                    // we still want to handle it.
                    val cfgStr = when (val raw = o.opt("config")) {
                        is JSONObject -> raw.toString()
                        is String -> raw
                        else -> ""
                    }
                    val cfg = decodeConfig(cfgStr)
                    if (cfg == null) {
                        newArr.put(o)
                        continue
                    }
                    val id = o.optString("id")
                    if (cfg.apiKey.isNotBlank() && sink.getProfile(id).isBlank()) {
                        sink.setProfile(id, cfg.apiKey)
                        dirty = true
                        relocated++
                    }
                    val cloned = JSONObject(o.toString())
                    val c = cloned.opt("config")
                    if (c is JSONObject) {
                        if (c.optString("apiKey").isNotEmpty()) {
                            c.put("apiKey", "")
                            dirty = true
                        }
                    } else if (c is String && c.isNotEmpty()) {
                        // Re-encode the config string with the apiKey blanked.
                        val parsed = runCatching { JSONObject(c) }.getOrNull()
                        if (parsed != null && parsed.optString("apiKey").isNotEmpty()) {
                            parsed.put("apiKey", "")
                            cloned.put("config", parsed.toString())
                            dirty = true
                        }
                    }
                    newArr.put(cloned)
                }
                if (dirty) {
                    cleanedProfilesJson = newArr.toString()
                }
            }
        }

        return MigrationOutcome(relocated, cleanedLlm, cleanedProfilesJson)
    }
}

/** Thin interface to keep [SecretMigrator] Android-free. */
internal interface SecretSink {
    fun getCurrent(): String
    fun setCurrent(value: String)
    fun getProfile(id: String): String
    fun setProfile(id: String, value: String)
}

/** Production wiring: routes [SecretMigrator] through the real [SecretStore]. */
internal class SettingsStoreSecretSink(private val secrets: SecretStore) : SecretSink {
    override fun getCurrent(): String = secrets.getCurrent()
    override fun setCurrent(value: String) { secrets.setCurrent(value) }
    override fun getProfile(id: String): String = secrets.getProfile(id)
    override fun setProfile(id: String, value: String) { secrets.setProfile(id, value) }
}