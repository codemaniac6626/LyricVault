package com.example.domain.audio

import android.media.MediaPlayer
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

data class PlaybackState(
    val activePath: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

class AudioPlayerController(
    private val scope: CoroutineScope
) {
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun togglePlay(filePath: String) {
        val current = _state.value
        if (current.activePath == filePath && mediaPlayer != null) {
            if (current.isPlaying) {
                runCatching { mediaPlayer?.pause() }
                progressJob?.cancel()
                _state.value = current.copy(isPlaying = false)
            } else {
                runCatching { mediaPlayer?.start() }
                _state.value = current.copy(isPlaying = true)
                startProgressPolling()
            }
            return
        }

        stop()
        val file = File(filePath)
        if (!file.exists()) {
            _state.value = PlaybackState(errorMessage = "Audio clip file is not present on this device.")
            return
        }

        try {
            val player = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                setOnCompletionListener {
                    progressJob?.cancel()
                    _state.value = _state.value.copy(isPlaying = false, positionMs = 0L)
                    runCatching { seekTo(0) }
                }
                start()
            }
            mediaPlayer = player
            _state.value = PlaybackState(
                activePath = filePath,
                isPlaying = true,
                positionMs = 0L,
                durationMs = player.duration.toLong().coerceAtLeast(1L),
                errorMessage = null
            )
            startProgressPolling()
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback failed", e)
            stop()
            _state.value = PlaybackState(errorMessage = "Could not play audio clip.")
        }
    }

    fun seekTo(filePath: String, fraction: Float) {
        val current = _state.value
        if (current.activePath != filePath) return
        val player = mediaPlayer ?: return
        val targetMs = (fraction.coerceIn(0f, 1f) * current.durationMs).toInt()
        runCatching {
            player.seekTo(targetMs)
            _state.value = current.copy(positionMs = targetMs.toLong())
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        _state.value = PlaybackState()
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _state.value.isPlaying) {
                val player = mediaPlayer
                if (player != null) {
                    val pos = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
                    val dur = runCatching { player.duration.toLong() }.getOrDefault(_state.value.durationMs)
                    _state.value = _state.value.copy(
                        positionMs = pos,
                        durationMs = dur.coerceAtLeast(1L)
                    )
                }
                delay(120L)
            }
        }
    }

    companion object {
        private const val TAG = "AudioPlayerController"
    }
}
