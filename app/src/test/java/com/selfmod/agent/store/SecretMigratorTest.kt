package com.selfmod.agent.store

import com.selfmod.agent.llm.LlmConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * M5 — `SettingsStore.llmConfig()` and `SettingsStore.profiles()` used to do
 * `secrets.setCurrent(...)` / `secrets.setProfile(...)` inside their getter,
 * rewriting the prefs JSON on every read. Two bugs hid in that shape:
 *
 * 1. Every getter call had a write side effect — fine when the secret store is
 *    working, but it locked up wedged keystores and competed with concurrent
 *    `setLlmConfig` calls.
 * 2. If `secrets.setCurrent` threw, the prefs JSON was already rewritten with
 *    the key blanked, so the key was lost permanently.
 *
 * The migration is now a pure helper ([SecretMigrator]) that callers run once
 * at startup and report back what to write. These tests pin the contract:
 * legacy keys are relocated exactly once, the prefs blobs are cleaned, and
 * re-running the migration is a no-op.
 */
class SecretMigratorTest {

    /** In-memory sink so the test can assert what was relocated where. */
    private class FakeSink : SecretSink {
        var storedCurrent: String = ""
        val profiles = mutableMapOf<String, String>()
        val setCurrentCalls = AtomicInteger(0)
        val setProfileCalls = AtomicInteger(0)
        override fun getCurrent(): String = storedCurrent
        override fun setCurrent(value: String) {
            storedCurrent = value
            setCurrentCalls.incrementAndGet()
        }
        override fun getProfile(id: String): String = profiles[id].orEmpty()
        override fun setProfile(id: String, value: String) {
            profiles[id] = value
            setProfileCalls.incrementAndGet()
        }
    }

    private fun encode(cfg: LlmConfig): String = JSONObject().apply {
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
    }.toString()

    private fun decode(raw: String): LlmConfig? {
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

    @Test
    fun `legacy current apiKey is relocated and prefs blob is cleaned`() {
        val sink = FakeSink()
        val cfg = LlmConfig.DEFAULT.copy(apiKey = "sk-legacy-current")
        val first = SecretMigrator.migrate(
            llmJson = encode(cfg),
            profilesJson = null,
            sink = sink,
            decodeConfig = ::decode,
            encodeConfig = ::encode,
        )
        assertEquals(1, first.relocated)
        assertEquals("sk-legacy-current", sink.storedCurrent)
        assertEquals(1, sink.setCurrentCalls.get())
        // The cleaned blob is reported back, with apiKey blanked — caller writes it.
        assertNotNull("caller must rewrite the prefs blob", first.cleanedLlm)
        assertEquals("", first.cleanedLlm!!.apiKey)
        assertEquals(cfg.baseUrl, first.cleanedLlm!!.baseUrl)
    }

    @Test
    fun `running migration twice is a no-op the second time`() {
        val sink = FakeSink()
        val cfg = LlmConfig.DEFAULT.copy(apiKey = "sk-legacy-current")
        val json = encode(cfg)
        val first = SecretMigrator.migrate(json, null, sink, ::decode, ::encode)
        assertEquals(1, first.relocated)

        // Simulate the caller writing the cleaned blob back to prefs and the
        // encrypted sink retaining the key.
        val rewritten = encode(first.cleanedLlm!!)
        val second = SecretMigrator.migrate(rewritten, null, sink, ::decode, ::encode)
        assertEquals("no second relocation expected", 0, second.relocated)
        assertEquals(1, sink.setCurrentCalls.get())
        assertNull("nothing to rewrite", second.cleanedLlm)
        assertNull("nothing to rewrite", second.cleanedProfilesJson)
    }

    @Test
    fun `profile keys are relocated per-id and prefs blob is cleaned`() {
        val sink = FakeSink()
        val idA = UUID.randomUUID().toString()
        val idB = UUID.randomUUID().toString()
        val arr = org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("id", idA); put("name", "A")
                put("config", encode(LlmConfig.DEFAULT.copy(apiKey = "sk-A")))
            })
            put(JSONObject().apply {
                put("id", idB); put("name", "B")
                put("config", encode(LlmConfig.DEFAULT.copy(apiKey = "sk-B")))
            })
        }
        val profilesJson = arr.toString()
        val outcome = SecretMigrator.migrate(
            llmJson = null,
            profilesJson = profilesJson,
            sink = sink,
            decodeConfig = ::decode,
            encodeConfig = ::encode,
        )
        assertEquals(2, outcome.relocated)
        assertEquals("sk-A", sink.getProfile(idA))
        assertEquals("sk-B", sink.getProfile(idB))
        assertEquals(2, sink.setProfileCalls.get())

        val cleaned = outcome.cleanedProfilesJson!!
        val cleanedArr = org.json.JSONArray(cleaned)
        for (i in 0 until cleanedArr.length()) {
            val o = cleanedArr.getJSONObject(i)
            // `persistProfiles` writes `config` as an encoded JSON string, so the
            // migrator rewrites it back the same way (with apiKey blanked).
            val cfgStr = o.opt("config") as? String
                ?: error("expected config string, got ${o.opt("config")}")
            val cfg = JSONObject(cfgStr)
            assertEquals("profile $i key must be blanked", "", cfg.getString("apiKey"))
        }
    }

    @Test
    fun `profiles with already-migrated keys are left alone`() {
        val sink = FakeSink()
        val id = UUID.randomUUID().toString()
        sink.setProfile(id, "sk-already-encrypted")
        // Pre-migration blob has empty apiKey (post-encryption shape).
        val arr = org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("id", id); put("name", "A")
                put("config", encode(LlmConfig.DEFAULT.copy(apiKey = "")))
            })
        }
        val profilesJson = arr.toString()
        val outcome = SecretMigrator.migrate(
            llmJson = null,
            profilesJson = profilesJson,
            sink = sink,
            decodeConfig = ::decode,
            encodeConfig = ::encode,
        )
        assertEquals(0, outcome.relocated)
        // No dirty rows → no rewrite reported.
        assertNull(outcome.cleanedProfilesJson)
    }

    @Test
    fun `blank and missing blobs are no-ops`() {
        val sink = FakeSink()
        val blank = SecretMigrator.migrate("", null, sink, ::decode, ::encode)
        assertEquals(0, blank.relocated)
        assertNull(blank.cleanedLlm)
        assertNull(blank.cleanedProfilesJson)

        val missing = SecretMigrator.migrate(null, null, sink, ::decode, ::encode)
        assertEquals(0, missing.relocated)
        assertEquals(0, sink.setCurrentCalls.get())
        assertEquals(0, sink.setProfileCalls.get())
    }

    @Test
    fun `current key already set in sink does not get overwritten`() {
        val sink = FakeSink().apply { storedCurrent = "sk-already-current" }
        val cfg = LlmConfig.DEFAULT.copy(apiKey = "sk-legacy-current")
        val outcome = SecretMigrator.migrate(
            llmJson = encode(cfg),
            profilesJson = null,
            sink = sink,
            decodeConfig = ::decode,
            encodeConfig = ::encode,
        )
        // No relocation — the legacy key in JSON is left untouched because the sink
        // already has a value (which may be the *real* post-encryption key). The
        // caller can still choose to wipe the JSON key as a precaution.
        assertEquals(0, outcome.relocated)
        assertEquals("sk-already-current", sink.storedCurrent)
    }
}