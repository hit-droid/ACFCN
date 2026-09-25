package com.selfmod.agent.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SecretStore(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILE,
            alias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.applicationContext.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
    }

    val encrypted: Boolean get() = prefs.javaClass.name.contains("Encrypted")

    fun getCurrent(): String = prefs.getString(KEY_CURRENT, "").orEmpty()

    fun setCurrent(value: String) {
        prefs.edit().putString(KEY_CURRENT, value).apply()
    }

    fun getProfile(id: String): String = prefs.getString("$KEY_PROFILE$id", "").orEmpty()

    fun setProfile(id: String, value: String) {
        prefs.edit().putString("$KEY_PROFILE$id", value).apply()
    }

    fun removeProfile(id: String) {
        prefs.edit().remove("$KEY_PROFILE$id").apply()
    }

    companion object {
        private const val FILE = "selfmod_secrets"
        private const val FILE_FALLBACK = "selfmod_secrets_fallback"
        private const val KEY_CURRENT = "current_api_key"
        private const val KEY_PROFILE = "profile_"
    }
}
