package com.example.ai

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Failure talking to a transcription provider. An [httpCode] of 401/403
 * means the API key was rejected — retrying will never fix that.
 */
class TranscriptionException(
    val httpCode: Int?,
    message: String
) : IOException(message)

/**
 * A cloud speech-to-text provider behind one small interface, so the
 * provider can be swapped later without touching the worker or the UI:
 * settings store a provider id, [TranscriptionProviders] builds the
 * matching implementation. Implementations block on IO — call them from
 * a background dispatcher.
 */
interface TranscriptionProvider {

    /** Full verbatim transcript of the audio file ("" when it holds no speech). */
    fun transcribe(audioFile: File): String

    /** One short label summarising a transcript, used as the audio block's caption. */
    fun summarize(transcript: String): String
}

object TranscriptionProviders {

    const val GROQ = "groq"

    /**
     * The configured provider, or null when transcription is off or the
     * selected provider has no API key yet.
     */
    fun create(context: Context): TranscriptionProvider? {
        val settings = AiSettings(context)
        if (!settings.transcriptionEnabled) return null
        val keys = ApiKeyStore(context)
        return when (settings.transcriptionProvider) {
            GROQ -> keys.getGroqKey()?.let { GroqTranscriptionProvider(it) }
            else -> null
        }
    }
}

/**
 * Groq: Whisper Large v3 for transcription (OpenAI-compatible endpoint)
 * and a small Llama model for the one-line summary label. One API key
 * covers both calls.
 */
class GroqTranscriptionProvider(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
) : TranscriptionProvider {

    override fun transcribe(audioFile: File): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", audioFile.name,
                audioFile.asRequestBody("audio/m4a".toMediaType())
            )
            .addFormDataPart("model", WHISPER_MODEL)
            .addFormDataPart("response_format", "json")
            .build()
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TranscriptionException(
                    response.code,
                    "Groq transcription failed (${response.code})"
                )
            }
            return JSONObject(text).optString("text", "").trim()
        }
    }

    override fun summarize(transcript: String): String {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", SUMMARY_SYSTEM_PROMPT)
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", transcript.take(4000))
            )
        val payload = JSONObject()
            .put("model", SUMMARY_MODEL)
            .put("temperature", 0.3)
            .put("messages", messages)
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TranscriptionException(
                    response.code,
                    "Groq summary failed (${response.code})"
                )
            }
            val content = JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")
                .orEmpty()
            return content.lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()?.trim('"')?.take(90)
                .orEmpty()
        }
    }

    companion object {
        const val WHISPER_MODEL = "whisper-large-v3"
        const val SUMMARY_MODEL = "llama-3.1-8b-instant"

        private const val SUMMARY_SYSTEM_PROMPT =
            "You write the label for a songwriter's audio idea. Given the " +
                "transcript of a voice recording, reply with ONE short label " +
                "(max 12 words) saying what the idea is for and what it is " +
                "about, so it can be recognised at a glance later. If the " +
                "songwriter states the purpose in the recording, lead with " +
                "that. If it is sung lyrics, use a distinctive fragment or " +
                "the lyric's theme. Reply with the label only — no quotes, " +
                "no preamble."
    }
}
