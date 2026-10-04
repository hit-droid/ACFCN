package com.selfmod.agent.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SecretStore(context: Context) {
    private val prefs: SharedPreferences
    val encrypted: Boolean
    val initIssue: String?

    init {
        val app = context.applicationContext
        val encryptedFactory: () -> SharedPreferences = {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                FILE,
                alias,
                app,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        val plainFactory: () -> SharedPreferences = {
            app.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
        }
        val outcome = SecretInit.tryInit(encryptedFactory, plainFactory)
        prefs = outcome.value
        encrypted = outcome.encrypted
        initIssue = outcome.initIssue
        if (!outcome.encrypted) {
            Log.w(TAG, "secret store fallback: ${outcome.initIssue}")
        }
    }

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
        private const val TAG = "SecretStore"
        private const val FILE = "selfmod_secrets"
        private const val FILE_FALLBACK = "selfmod_secrets_fallback"
        private const val KEY_CURRENT = "current_api_key"
        private const val KEY_PROFILE = "profile_"
    }
}