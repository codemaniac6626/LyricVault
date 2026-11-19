package com.example.domain.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class RecordingState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitudes: List<Float> = emptyList(),
    val errorMessage: String? = null
)

data class RecordedAudioResult(
    val filePath: String,
    val durationMs: Long
)

class AudioRecorderController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L
    private var meterJob: Job? = null

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun startRecording(audioDir: File): Boolean {
        if (_state.value.isRecording) return false
        return try {
            if (!audioDir.exists()) audioDir.mkdirs()
            val file = File(audioDir, "melody_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            outputFile = file
            startTimeMs = System.currentTimeMillis()
            _state.value = RecordingState(isRecording = true, elapsedMs = 0L, amplitudes = emptyList(), errorMessage = null)
            startMetering()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            cleanupInternal(deleteFile = true)
            _state.value = RecordingState(
                isRecording = false,
                errorMessage = "Could not start microphone recording: ${e.localizedMessage ?: "Audio hardware unavailable"}"
            )
            false
        }
    }

    fun stopRecording(): RecordedAudioResult? {
        if (!_state.value.isRecording) return null
        val file = outputFile
        val duration = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(300L)
        meterJob?.cancel()
        meterJob = null
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            outputFile = null
            _state.value = RecordingState(isRecording = false)
            if (file != null && file.exists() && file.length() > 0L) {
                RecordedAudioResult(filePath = file.absolutePath, durationMs = duration)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio recording cleanly", e)
            cleanupInternal(deleteFile = true)
            _state.value = RecordingState(isRecording = false, errorMessage = "Recording was too short or interrupted.")
            null
        }
    }

    fun cancelRecording() {
        cleanupInternal(deleteFile = true)
        _state.value = RecordingState(isRecording = false)
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun startMetering() {
        meterJob?.cancel()
        meterJob = scope.launch {
            while (isActive && _state.value.isRecording) {
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val normalized = (amp / 18000f).coerceIn(0.08f, 1f)
                val elapsed = System.currentTimeMillis() - startTimeMs
                val updatedAmps = (_state.value.amplitudes + normalized).takeLast(32)
                _state.value = _state.value.copy(
                    elapsedMs = elapsed,
                    amplitudes = updatedAmps
                )
                delay(90L)
            }
        }
    }

    private fun cleanupInternal(deleteFile: Boolean) {
        meterJob?.cancel()
        meterJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        if (deleteFile) {
            runCatching { outputFile?.delete() }
        }
        outputFile = null
    }

    fun release() {
        cleanupInternal(deleteFile = false)
    }

    companion object {
        private const val TAG = "AudioRecorderController"
    }
}
