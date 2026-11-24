package com.example.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * Plain (non-secret) AI preferences. The Gemini API key itself lives in
 * ApiKeyStore, never here.
 */
class AiSettings(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class BackendPreference { AUTO, CPU, GPU }

    var aiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    /** Embedder id: "minilm" (default), "embeddinggemma2", "gemini". */
    var embedderId: String
        get() = prefs.getString(KEY_EMBEDDER_ID, ModelCatalog.MINILM.id) ?: ModelCatalog.MINILM.id
        set(value) = prefs.edit().putString(KEY_EMBEDDER_ID, value).apply()

    /** LLM id: "none" (default), an on-device model id, or "gemini". */
    var llmId: String
        get() = prefs.getString(KEY_LLM_ID, LLM_NONE) ?: LLM_NONE
        set(value) = prefs.edit().putString(KEY_LLM_ID, value).apply()

    var backendPreference: BackendPreference
        get() = runCatching {
            BackendPreference.valueOf(
                prefs.getString(KEY_BACKEND, BackendPreference.AUTO.name)
                    ?: BackendPreference.AUTO.name
            )
        }.getOrDefault(BackendPreference.AUTO)
        set(value) = prefs.edit().putString(KEY_BACKEND, value.name).apply()

    /** Download large models on Wi-Fi only (default true). */
    var wifiOnlyDownloads: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** Gemini chat model name (user-editable; Google renames models often). */
    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, GeminiClient.DEFAULT_LLM_MODEL)
            ?: GeminiClient.DEFAULT_LLM_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value.trim()).apply()

    /**
     * Global matching instruction: free text steering how AI matches ideas
     * (vector search + "Why this fits" explanations). Blank = no instruction.
     * A per-song instruction on the song overrides this for that song.
     * Not trimmed here: trimming happens at use time (resolveInstruction),
     * so typing a trailing space mid-sentence is not eaten by the field.
     */
    var customInstruction: String
        get() = prefs.getString(KEY_CUSTOM_INSTRUCTION, "") ?: ""
        set(value) = prefs.edit()
            .putString(KEY_CUSTOM_INSTRUCTION, value.take(MAX_INSTRUCTION_CHARS)).apply()

    // ---- Audio transcription (cloud, opt-in, separate from matching) ----

    /** Master switch for background transcription of recorded clips. */
    var transcriptionEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSCRIPTION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TRANSCRIPTION_ENABLED, value).apply()

    /**
     * Transcription provider id: "groq" (default, and the only provider
     * so far). New providers slot in behind the TranscriptionProvider
     * interface without touching stored data.
     */
    var transcriptionProvider: String
        get() = prefs.getString(KEY_TRANSCRIPTION_PROVIDER, "groq") ?: "groq"
        set(value) = prefs.edit().putString(KEY_TRANSCRIPTION_PROVIDER, value).apply()

    /**
     * Audio file paths whose transcription has already been applied.
     * Clip paths are UUID-named per recording, so a stale entry can
     * never match a different clip.
     */
    fun isTranscribed(audioPath: String): Boolean =
        prefs.getStringSet(KEY_TRANSCRIBED_PATHS, emptySet())
            ?.contains(audioPath) == true

    fun markTranscribed(audioPath: String) {
        val current = prefs.getStringSet(KEY_TRANSCRIBED_PATHS, emptySet())
            ?: emptySet()
        prefs.edit().putStringSet(KEY_TRANSCRIBED_PATHS, current + audioPath).apply()
    }

    companion object {
        const val PREFS_NAME = "lyric_vault_ai_prefs"
        const val LLM_NONE = "none"
        /** Instructions stay short: they ride inside every query embedding. */
        const val MAX_INSTRUCTION_CHARS = 500
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_EMBEDDER_ID = "embedder_id"
        private const val KEY_LLM_ID = "llm_id"
        private const val KEY_BACKEND = "backend"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_CUSTOM_INSTRUCTION = "custom_instruction"
        private const val KEY_TRANSCRIPTION_ENABLED = "transcription_enabled"
        private const val KEY_TRANSCRIPTION_PROVIDER = "transcription_provider"
        private const val KEY_TRANSCRIBED_PATHS = "transcribed_paths"
    }
}
