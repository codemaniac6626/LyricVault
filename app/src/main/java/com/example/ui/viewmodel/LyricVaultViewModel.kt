package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BlockType
import com.example.data.local.IdeaBlockEntity
import com.example.data.local.IdeaNoteEntity
import com.example.data.local.IdeaNoteWithDetails
import com.example.data.local.LyricVaultDatabase
import com.example.data.local.SongEntity
import com.example.data.repository.BackupSummary
import com.example.data.repository.LyricVaultRepository
import com.example.data.repository.VaultBackupManager
import com.example.domain.audio.AudioPlayerController
import com.example.domain.audio.AudioRecorderController
import com.example.domain.audio.PlaybackState
import com.example.domain.audio.RecordedAudioResult
import com.example.domain.audio.RecordingState
import com.example.domain.matching.MatchMode
import com.example.domain.matching.OfflineVaultMatcher
import com.example.domain.matching.VaultMatchResult
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VaultFilter {
    ALL,
    UNUSED,
    USED,
    WITH_AUDIO
}

sealed interface BackupOperationState {
    data object Idle : BackupOperationState
    data object Working : BackupOperationState
    data class Success(val message: String) : BackupOperationState
    data class Error(val message: String) : BackupOperationState
}

class LyricVaultViewModel(application: Application) : AndroidViewModel(application) {

    private val database = LyricVaultDatabase.getInstance(application)
    private val dao = database.lyricVaultDao()
    private val repository = LyricVaultRepository(dao, application)
    private val backupManager = VaultBackupManager(application, dao, repository)

    private val prefs = application.getSharedPreferences("lyric_vault_prefs", Context.MODE_PRIVATE)

    val audioRecorder = AudioRecorderController(application, viewModelScope)
    val audioPlayer = AudioPlayerController(viewModelScope)

    val recordingState: StateFlow<RecordingState> = audioRecorder.state
    val playbackState: StateFlow<PlaybackState> = audioPlayer.state

    private val _themeMode = MutableStateFlow(loadSavedTheme())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    val allIdeas: StateFlow<List<IdeaNoteWithDetails>> = repository.allIdeas
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allSongs: StateFlow<List<SongEntity>> = repository.allSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _vaultSearchQuery = MutableStateFlow("")
    val vaultSearchQuery: StateFlow<String> = _vaultSearchQuery.asStateFlow()

    private val _vaultFilter = MutableStateFlow(VaultFilter.ALL)
    val vaultFilter: StateFlow<VaultFilter> = _vaultFilter.asStateFlow()

    val filteredIdeas: StateFlow<List<IdeaNoteWithDetails>> = combine(
        allIdeas,
        _vaultSearchQuery,
        _vaultFilter
    ) { ideas, query, filter ->
        val trimmed = query.trim().lowercase()
        ideas.filter { detail ->
            val passesFilter = when (filter) {
                VaultFilter.ALL -> true
                VaultFilter.UNUSED -> detail.idea.usedCount == 0
                VaultFilter.USED -> detail.idea.usedCount > 0
                VaultFilter.WITH_AUDIO -> detail.audioBlockCount > 0
            }
            val passesQuery = if (trimmed.isEmpty()) {
                true
            } else {
                detail.searchableText.lowercase().contains(trimmed)
            }
            passesFilter && passesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _backupState = MutableStateFlow<BackupOperationState>(BackupOperationState.Idle)
    val backupState: StateFlow<BackupOperationState> = _backupState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun setVaultSearchQuery(query: String) {
        _vaultSearchQuery.value = query
    }

    fun setVaultFilter(filter: VaultFilter) {
        _vaultFilter.value = filter
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    suspend fun loadIdeaOnce(ideaId: Long): IdeaNoteWithDetails? = repository.getIdeaOnce(ideaId)

    suspend fun loadSongOnce(songId: Long): SongEntity? = repository.getSongOnce(songId)

    fun saveIdea(
        idea: IdeaNoteEntity,
        blocks: List<IdeaBlockEntity>,
        onSaved: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val savedId = repository.saveIdea(idea, blocks)
            onSaved(savedId)
        }
    }

    fun deleteIdea(ideaId: Long, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            audioPlayer.stop()
            repository.deleteIdea(ideaId)
            _snackbarMessage.value = "Idea deleted from vault."
            onDeleted()
        }
    }

    fun resetIdeaUsage(ideaId: Long) {
        viewModelScope.launch {
            repository.resetIdeaUsage(ideaId)
            _snackbarMessage.value = "Marked as unused again."
        }
    }

    fun saveSong(song: SongEntity, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val savedId = repository.saveSong(song)
            onSaved(savedId)
        }
    }

    fun deleteSong(songId: Long, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteSong(songId)
            _snackbarMessage.value = "Song removed."
            onDeleted()
        }
    }

    fun markIdeaUsed(ideaId: Long, songId: Long, songTitle: String) {
        viewModelScope.launch {
            repository.markIdeaUsedInSong(ideaId, songId, songTitle)
            _snackbarMessage.value = "Marked idea as used in \"${songTitle.ifBlank { "this song" }}\"."
        }
    }

    fun startRecordingAudio(): Boolean {
        audioPlayer.stop()
        return audioRecorder.startRecording(repository.audioClipsDirectory())
    }

    fun stopRecordingAudio(): RecordedAudioResult? {
        return audioRecorder.stopRecording()
    }

    fun cancelRecordingAudio() {
        audioRecorder.cancelRecording()
    }

    fun deleteAudioFile(path: String?) {
        if (playbackState.value.activePath == path) {
            audioPlayer.stop()
        }
        viewModelScope.launch {
            repository.deleteAudioFileIfExists(path)
        }
    }

    suspend fun computeMatches(
        ideas: List<IdeaNoteWithDetails>,
        query: String,
        mode: MatchMode
    ): VaultMatchResult = withContext(Dispatchers.Default) {
        OfflineVaultMatcher.matchVault(ideas = ideas, queryText = query, mode = mode)
    }

    fun exportVaultJson(uri: Uri, includeAudioBase64: Boolean) {
        viewModelScope.launch {
            _backupState.value = BackupOperationState.Working
            val result = backupManager.exportToUri(uri, includeAudioBase64)
            result.fold(
                onSuccess = { summary: BackupSummary ->
                    val audioPart = if (includeAudioBase64) ", ${summary.audioClipsCount} audio clips" else " (text-only JSON)"
                    _backupState.value = BackupOperationState.Success(
                        "Exported ${summary.ideasCount} ideas, ${summary.songsCount} songs$audioPart."
                    )
                },
                onFailure = { err ->
                    _backupState.value = BackupOperationState.Error(
                        err.localizedMessage ?: "Export failed."
                    )
                }
            )
        }
    }

    fun importVaultFile(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = BackupOperationState.Working
            val result = backupManager.importFromUri(uri)
            result.fold(
                onSuccess = { summary: BackupSummary ->
                    val parts = buildList {
                        add("${summary.ideasCount} ideas")
                        if (summary.songsCount > 0) add("${summary.songsCount} songs")
                        if (summary.audioClipsCount > 0) add("${summary.audioClipsCount} audio clips")
                    }
                    _backupState.value = BackupOperationState.Success(
                        "Imported ${parts.joinToString(", ")} into your local vault."
                    )
                },
                onFailure = { err ->
                    _backupState.value = BackupOperationState.Error(
                        err.localizedMessage ?: "Import failed. Check that the file is valid JSON or plain text."
                    )
                }
            )
        }
    }

    fun bulkImportText(rawText: String, tag: String) {
        viewModelScope.launch {
            _backupState.value = BackupOperationState.Working
            val count = repository.bulkImportPlainTextIdeas(rawText, defaultTag = tag.ifBlank { "imported" })
            if (count > 0) {
                _backupState.value = BackupOperationState.Success("Imported $count ideas into your Vault.")
            } else {
                _backupState.value = BackupOperationState.Error("No text ideas found. Separate ideas with blank lines.")
            }
        }
    }

    fun resetBackupState() {
        _backupState.value = BackupOperationState.Idle
    }

    private fun loadSavedTheme(): ThemeMode {
        val raw = prefs.getString("theme_mode", ThemeMode.DARK.name)
        return runCatching { ThemeMode.valueOf(raw ?: ThemeMode.DARK.name) }.getOrDefault(ThemeMode.DARK)
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
        audioPlayer.stop()
    }
}
