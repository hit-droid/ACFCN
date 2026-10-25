package com.selfmod.agent.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted key-value store for agent memory.
 *
 * The original memory lived in the plaintext `selfmod_prefs` SharedPreferences
 * under a `mem_` prefix — anything the agent "remembered" (which can include
 * secrets it was told to keep) was readable by any app that could read the
 * app's shared prefs, and by `adb backup` while `allowBackup` was on. This wraps
 * [EncryptedSharedPreferences] (AES256-GCM, key in the Android Keystore) so memory
 * at rest is encrypted, and migrates the legacy `mem_*` keys across exactly once.
 *
 * If the Keystore / EncryptedSharedPreferences cannot be initialised (some
 * emulators, Robolectric, or hardware without a keystore) we degrade gracefully
 * to a dedicated plaintext file instead of silently dropping every write — the
 * alternative would lose all agent memory on those devices.
 */
class SecureMemoryStore(
    context: Context,
    private val legacy: SharedPreferences,
) {
    private val ctx = context.applicationContext
    private val fallback: SharedPreferences =
        ctx.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)

    private val enc: SharedPreferences? = runCatching {
        val keyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILE,
            keyAlias,
            ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    /** The active backend: encrypted when available, otherwise the plaintext fallback. */
    private val active: SharedPreferences get() = enc ?: fallback

    val available: Boolean get() = enc != null

    /**
     * Moves every legacy `mem_*` entry from the plaintext prefs into the encrypted
     * store (or the fallback when encryption is unavailable), then deletes the
     * originals. Runs once (guarded by a flag).
     */
    fun migrateIfNeeded() {
        if (legacy.getBoolean(MIGRATED_FLAG, false)) return
        val sink = enc ?: fallback
        runCatching {
            val batch = legacy.all
                .filterKeys { it.startsWith(LEGACY_PREFIX) }
            if (batch.isNotEmpty()) {
                val edit = sink.edit()
                batch.forEach { (k, v) ->
                    if (v is String) edit.putString(k.removePrefix(LEGACY_PREFIX), v)
                }
                edit.apply()
                val del = legacy.edit()
                batch.keys.forEach { del.remove(it) }
                del.apply()
            }
        }
        legacy.edit().putBoolean(MIGRATED_FLAG, true).apply()
    }

    fun get(key: String): String? = active.getString(key, null)

    fun set(key: String, value: String) {
        active.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        active.edit().remove(key).apply()
    }

    fun keys(): List<String> = active.all.keys.toList().sorted()

    companion object {
        private const val FILE = "acfcn_secure_memory"
        private const val FALLBACK_FILE = "acfcn_memory_fallback"
        private const val LEGACY_PREFIX = "mem_"
        private const val MIGRATED_FLAG = "mem_migrated_v1"
    }
}
