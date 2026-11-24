package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user's cloud API keys (Gemini, Groq) in EncryptedSharedPreferences
 * (AES-256, backed by the Android KeyStore). The key never leaves the device
 * except in the `x-goog-api-key` header of Gemini API calls, and is never
 * logged, backed up in the JSON export, or written to any other file.
 *
 * If the KeyStore is unavailable on a device (rare), storage falls back to
 * plain SharedPreferences and [storageEncrypted] reports false so the UI can
 * say so honestly instead of pretending the key is encrypted.
 */
class ApiKeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences
    val storageEncrypted: Boolean

    init {
        var encrypted: SharedPreferences? = null
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            encrypted = EncryptedSharedPreferences.create(
                appContext,
                "lyric_vault_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            encrypted = null
        }
        if (encrypted != null) {
            prefs = encrypted
            storageEncrypted = true
        } else {
            prefs = appContext.getSharedPreferences(
                "lyric_vault_secrets_fallback",
                Context.MODE_PRIVATE
            )
            storageEncrypted = false
        }
    }

    fun getGeminiKey(): String? =
        prefs.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }

    fun hasGeminiKey(): Boolean = getGeminiKey() != null

    fun setGeminiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    fun clearGeminiKey() {
        prefs.edit().remove(KEY_GEMINI).apply()
    }

    fun getGroqKey(): String? =
        prefs.getString(KEY_GROQ, null)?.takeIf { it.isNotBlank() }

    fun hasGroqKey(): Boolean = getGroqKey() != null

    fun setGroqKey(key: String) {
        prefs.edit().putString(KEY_GROQ, key.trim()).apply()
    }

    fun clearGroqKey() {
        prefs.edit().remove(KEY_GROQ).apply()
    }

    private companion object {
        const val KEY_GEMINI = "gemini_api_key"
        const val KEY_GROQ = "groq_api_key"
    }
}
