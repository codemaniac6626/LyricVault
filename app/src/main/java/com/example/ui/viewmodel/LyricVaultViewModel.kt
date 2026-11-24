package com.example.ui.viewmodel

import android.app.Application
import android.widget.Toast
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.ai.AiMatchingService
import com.example.ai.AiModel
import com.example.ai.AiSettings
import com.example.ai.ApiKeyStore
import com.example.ai.DeviceSpecs
import com.example.ai.DeviceTier
import com.example.ai.DeviceTiers
import com.example.ai.GeminiClient
import com.example.ai.ModelCatalog
import com.example.ai.ModelDownloadWorker
import com.example.ai.TranscriptionWorker
import com.example.ai.ModelStore
import com.example.data.local.BlockType
import com.example.data.local.IdeaBlockEntity
import com.example.data.local.IdeaNoteEntity
import com.example.data.local.IdeaNoteWithDetails
import com.example.data.local.LyricVaultDatabase
import com.example.data.local.SongAudioBlockEntity
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
import com.example.domain.matching.SplitMatchResult
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VaultFilter {
    ALL,
    UNUSED,
    USED,
    WITH_AUDIO,
    HAS_IMAGES
}

sealed interface BackupOperationState {
    data object Idle : BackupOperationState
    data class Working(
        val message: String = "Processing local vault file...",
        val fraction: Float? = null
    ) : BackupOperationState
    data class Success(val message: String) : BackupOperationState
    data class Error(val message: String) : BackupOperationState
}

sealed interface AiDownloadState {
    data object NotDownloaded : AiDownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : AiDownloadState
    data object Installed : AiDownloadState
    data object Paused : AiDownloadState
    data class Failed(val message: String) : AiDownloadState
}

sealed interface KeyTestState {
    data object Idle : KeyTestState
    data object Testing : KeyTestState
    data object Ok : KeyTestState
    data class Error(val message: String) : KeyTestState
}

sealed interface ExplanationState {
    data object Idle : ExplanationState
    data object Loading : ExplanationState
    data class Done(val text: String) : ExplanationState
    data class Error(val message: String) : ExplanationState
}

data class AiUiState(
    val enabled: Boolean = false,
    val specs: DeviceSpecs? = null,
    val tier: DeviceTier? = null,
    val recommendedLlm: AiModel? = null,
    val embedderId: String = ModelCatalog.MINILM.id,
    val llmId: String = AiSettings.LLM_NONE,
    val backend: AiSettings.BackendPreference = AiSettings.BackendPreference.AUTO,
    val wifiOnly: Boolean = true,
    val geminiModel: String = GeminiClient.DEFAULT_LLM_MODEL,
    val customInstruction: String = "",
    val hasGeminiKey: Boolean = false,
    val transcriptionEnabled: Boolean = false,
    val transcriptionProvider: String = "groq",
    val hasGroqKey: Boolean = false,
    val keyStorageEncrypted: Boolean = true,
    val downloads: Map<String, AiDownloadState> = emptyMap(),
    val embeddedCount: Int = 0,
    val totalIdeas: Int = 0,
    val reembedProgress: AiMatchingService.ReembedProgress? = null,
    val keyTestState: KeyTestState = KeyTestState.Idle
)

class LyricVaultViewModel(application: Application) : AndroidViewModel(application) {

    private val database = LyricVaultDatabase.getInstance(application)
    private val dao = database.lyricVaultDao()
    private val repository = LyricVaultRepository(dao, application)
    private val backupManager = VaultBackupManager(application, dao, repository)

    private val prefs = application.getSharedPreferences("lyric_vault_prefs", Context.MODE_PRIVATE)

    // ---- AI matching (on-device models + optional Gemini BYOK) ----
    private val aiSettings = AiSettings(application)
    private val apiKeyStore = ApiKeyStore(application)
    private val modelStore = ModelStore(application)
    private val aiService = AiMatchingService(application, dao, aiSettings, apiKeyStore, modelStore)
    private val workManager = WorkManager.getInstance(application)
    private val downloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private val _aiUiState = MutableStateFlow(
        AiUiState(
            specs = aiService.specs,
            tier = aiService.tier,
            recommendedLlm = aiService.recommendedLlm
        )
    )
    val aiUiState: StateFlow<AiUiState> = _aiUiState.asStateFlow()

    /**
     * True while background work is queued or running — drives the
     * small corner indicator in the app shell. Standing rule: every
     * background job added to this app feeds into this signal so the
     * user can always see that something is running.
     */
    val backgroundWorkActive: StateFlow<Boolean> =
        WorkManager.getInstance(application)
            .getWorkInfosByTagFlow(TranscriptionWorker.TAG)
            .map { infos ->
                infos.any {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.RUNNING
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _explanations = MutableStateFlow<Map<Long, ExplanationState>>(emptyMap())
    val explanations: StateFlow<Map<Long, ExplanationState>> = _explanations.asStateFlow()

    init {
        viewModelScope.launch {
            aiService.reembedProgress.collect { progress ->
                _aiUiState.value = _aiUiState.value.copy(reembedProgress = progress)
                if (progress == null) refreshAiState()
            }
        }
        refreshAiState()
    }

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
                VaultFilter.HAS_IMAGES -> detail.imageBlockCount > 0
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
            // Keep the vector store fresh: embed the saved idea in the
            // background. Best-effort — a failure here never breaks saving.
            runCatching {
                repository.getIdeaOnce(savedId)?.let { aiService.embedIdea(it) }
            }
            // Queue background transcription for any recorded clips in
            // this idea (a no-op unless transcription is enabled with a
            // provider key). WorkManager finishes it even if the app is
            // closed right after saving.
            blocks.filter {
                it.blockType == BlockType.AUDIO && !it.audioFilePath.isNullOrBlank()
            }.forEach { block ->
                TranscriptionWorker.enqueue(
                    getApplication(),
                    TranscriptionWorker.TARGET_IDEA,
                    savedId,
                    block.audioFilePath!!
                )
            }
            refreshAiState()
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

    // ---- Song audio blocks (melodies attached to a song) ----

    fun songAudioBlocks(songId: Long): Flow<List<SongAudioBlockEntity>> =
        repository.observeSongAudioBlocks(songId)

    fun addRecordedAudioToSong(songId: Long, recorded: RecordedAudioResult) {
        if (songId <= 0L) return
        viewModelScope.launch {
            repository.addSongAudioBlock(
                songId = songId,
                label = "",
                audioFilePath = recorded.filePath,
                audioDurationMs = recorded.durationMs
            )
            // Background transcription gives the melody a summary label
            // (songs have no block stream, so no transcript block there).
            TranscriptionWorker.enqueue(
                getApplication(),
                TranscriptionWorker.TARGET_SONG,
                songId,
                recorded.filePath
            )
            _snackbarMessage.value = "Melody added to this song."
        }
    }

    fun updateSongAudioLabel(block: SongAudioBlockEntity, label: String) {
        viewModelScope.launch {
            repository.updateSongAudioBlock(block.copy(label = label))
        }
    }

    fun deleteSongAudioBlock(block: SongAudioBlockEntity) {
        viewModelScope.launch {
            if (playbackState.value.activePath == block.audioFilePath) {
                audioPlayer.stop()
            }
            repository.deleteSongAudioBlock(block)
            _snackbarMessage.value = "Audio block removed from song."
        }
    }

    /**
     * Copies the idea's audio into the song as song-owned blocks labelled
     * with the idea's name (see LyricVaultRepository.copyIdeaAudioIntoSong).
     */
    fun addIdeaAudioToSong(songId: Long, idea: IdeaNoteWithDetails) {
        if (songId <= 0L) return
        viewModelScope.launch {
            repository.copyIdeaAudioIntoSong(songId, idea)
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

    /**
     * Copies a picked image into the app's image store for an idea block.
     * The editor adds the block when the path comes back; a null result
     * means the copy failed and nothing is added.
     */
    fun importIdeaImage(uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val path = repository.importImageFromUri(uri)
            if (path == null) {
                _snackbarMessage.value = "Could not add that image."
            }
            onResult(path)
        }
    }

    fun deleteImageFile(path: String?) {
        viewModelScope.launch {
            repository.deleteBlockFileIfExists(path)
        }
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
        mode: MatchMode,
        songInstruction: String? = null
    ): SplitMatchResult = withContext(Dispatchers.Default) {
        // Keyword matching always runs; vector search runs alongside it
        // when AI is enabled and an embedder is ready. The results stay
        // SPLIT by engine (issue #8) — the UI shows an AI section and a
        // keyword section instead of one merged list. The instruction
        // steers the vector side (and explanations); a per-song instruction
        // overrides the global one for this song.
        aiService.match(
            ideas = ideas,
            query = query,
            mode = mode,
            instruction = AiMatchingService.resolveInstruction(
                songInstruction,
                aiSettings.customInstruction
            )
        )
    }

    // ---- AI matching: settings, downloads, Gemini key, explanations ----

    fun refreshAiState() {
        viewModelScope.launch {
            val downloads = mutableMapOf<String, AiDownloadState>()
            for (model in ModelCatalog.downloadable) {
                downloads[model.id] = downloadStateFor(model)
            }
            val totalIdeas = allIdeas.value.size
            val embedded = runCatching { aiService.embeddedCount() }.getOrDefault(0)
            _aiUiState.value = _aiUiState.value.copy(
                enabled = aiSettings.aiEnabled,
                specs = DeviceTiers.detectSpecs(getApplication()),
                embedderId = aiSettings.embedderId,
                llmId = aiSettings.llmId,
                backend = aiSettings.backendPreference,
                wifiOnly = aiSettings.wifiOnlyDownloads,
                geminiModel = aiSettings.geminiModel,
                customInstruction = aiSettings.customInstruction,
                hasGeminiKey = apiKeyStore.hasGeminiKey(),
                transcriptionEnabled = aiSettings.transcriptionEnabled,
                transcriptionProvider = aiSettings.transcriptionProvider,
                hasGroqKey = apiKeyStore.hasGroqKey(),
                keyStorageEncrypted = apiKeyStore.storageEncrypted,
                downloads = downloads,
                embeddedCount = embedded,
                totalIdeas = totalIdeas
            )
        }
    }

    private suspend fun downloadStateFor(model: AiModel): AiDownloadState {
        if (modelStore.isInstalled(model)) return AiDownloadState.Installed
        // Is a download worker currently running for this model?
        val infos = runCatching {
            workManager.getWorkInfosByTagFlow("download_${model.id}").first()
        }.getOrDefault(emptyList())
        val running = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
        if (running != null) {
            val downloaded = running.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0L)
            val total = running.progress.getLong(
                ModelDownloadWorker.KEY_TOTAL,
                model.totalSizeBytes
            )
            observeDownload(model, running.id)
            return AiDownloadState.Downloading(downloaded, total)
        }
        val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
        if (failed != null) {
            val message = failed.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                ?: "Download failed."
            return AiDownloadState.Failed(message)
        }
        if (modelStore.downloadedBytes(model) > 0L) return AiDownloadState.Paused
        return AiDownloadState.NotDownloaded
    }

    fun setAiEnabled(enabled: Boolean) {
        aiSettings.aiEnabled = enabled
        if (enabled) {
            // First enable: make sure the default embedder is on its way.
            val embedderModel = ModelCatalog.byId(aiSettings.embedderId)
            if (embedderModel != null && !embedderModel.noDownload &&
                !modelStore.isInstalled(embedderModel)
            ) {
                downloadModel(embedderModel.id)
            } else {
                reembedVault()
            }
        }
        refreshAiState()
    }

    fun downloadModel(modelId: String) {
        val model = ModelCatalog.byId(modelId) ?: return
        if (!modelStore.hasRoomFor(model)) {
            val short = modelStore.shortfallBytes(model) / 1_000_000L
            _snackbarMessage.value =
                "Not enough free storage for ${model.displayName} (need ~${short} MB more)."
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (aiSettings.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to modelId))
            .setConstraints(constraints)
            .addTag("download_$modelId")
            .build()
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.uniqueName(modelId),
            ExistingWorkPolicy.REPLACE,
            request
        )
        observeDownload(model, request.id)
        _aiUiState.value = _aiUiState.value.copy(
            downloads = _aiUiState.value.downloads + (
                modelId to AiDownloadState.Downloading(
                    modelStore.downloadedBytes(model),
                    model.totalSizeBytes
                )
            )
        )
    }

    /**
     * After a backup import applies stored AI settings, queue downloads
     * for the selected embedder and LLM models that are not installed
     * yet. Cloud selections (Gemini) and "none" need no download, and
     * downloadModel already handles storage checks and the Wi-Fi-only
     * preference — which by now is the restored one.
     */
    /** Returns how many model downloads were queued. */
    fun redownloadSelectedAiModels(): Int {
        var queued = 0
        listOf(aiSettings.embedderId, aiSettings.llmId)
            .distinct()
            .forEach { modelId ->
                val model = ModelCatalog.byId(modelId) ?: return@forEach
                if (model.noDownload) return@forEach
                if (!modelStore.isInstalled(model)) {
                    downloadModel(modelId)
                    queued++
                }
            }
        return queued
    }

    private fun observeDownload(model: AiModel, workId: java.util.UUID) {
        downloadJobs[model.id]?.cancel()
        downloadJobs[model.id] = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { info ->
                if (info == null) return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val downloaded = info.progress.getLong(
                            ModelDownloadWorker.KEY_DOWNLOADED, 0L
                        )
                        val total = info.progress.getLong(
                            ModelDownloadWorker.KEY_TOTAL,
                            model.totalSizeBytes
                        )
                        updateDownload(model.id, AiDownloadState.Downloading(downloaded, total))
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        updateDownload(model.id, AiDownloadState.Installed)
                        Toast.makeText(
                            getApplication(),
                            "AI model installed: ${model.displayName}",
                            Toast.LENGTH_SHORT
                        ).show()
                        downloadJobs.remove(model.id)
                        // A fresh embedder means the vault needs embeddings.
                        if (model.kind == com.example.ai.ModelKind.EMBEDDER &&
                            aiSettings.embedderId == model.id
                        ) {
                            reembedVault()
                        }
                        // Downloading an LLM without tapping its radio left
                        // it installed but unselected, so explanations still
                        // said "no model selected". Auto-select it when no
                        // explanation model is picked yet.
                        if (model.kind == com.example.ai.ModelKind.LLM &&
                            aiSettings.llmId == AiSettings.LLM_NONE
                        ) {
                            aiService.setLlm(model.id)
                            _snackbarMessage.value =
                                "${model.displayName} installed and selected as your explanation model."
                        }
                        refreshAiState()
                    }
                    WorkInfo.State.FAILED -> {
                        val message = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                            ?: "Download failed."
                        updateDownload(model.id, AiDownloadState.Failed(message))
                        downloadJobs.remove(model.id)
                    }
                    WorkInfo.State.CANCELLED -> {
                        updateDownload(
                            model.id,
                            if (modelStore.downloadedBytes(model) > 0L) {
                                AiDownloadState.Paused
                            } else {
                                AiDownloadState.NotDownloaded
                            }
                        )
                        downloadJobs.remove(model.id)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun updateDownload(modelId: String, state: AiDownloadState) {
        _aiUiState.value = _aiUiState.value.copy(
            downloads = _aiUiState.value.downloads + (modelId to state)
        )
    }

    fun pauseDownload(modelId: String) {
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueName(modelId))
    }

    fun cancelDownload(modelId: String) {
        val model = ModelCatalog.byId(modelId) ?: return
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueName(modelId))
        viewModelScope.launch {
            modelStore.deletePartFiles(model)
            updateDownload(modelId, AiDownloadState.NotDownloaded)
        }
    }

    fun deleteModel(modelId: String) {
        val model = ModelCatalog.byId(modelId) ?: return
        viewModelScope.launch {
            workManager.cancelUniqueWork(ModelDownloadWorker.uniqueName(modelId))
            if (aiSettings.embedderId == modelId) {
                aiService.setEmbedder(ModelCatalog.MINILM.id)
            }
            if (aiSettings.llmId == modelId) {
                aiService.setLlm(AiSettings.LLM_NONE)
            }
            modelStore.delete(model)
            _snackbarMessage.value = "${model.displayName} deleted."
            refreshAiState()
        }
    }

    fun setEmbedder(embedderId: String) {
        viewModelScope.launch {
            aiService.setEmbedder(embedderId)
            val model = ModelCatalog.byId(embedderId)
            if (model != null && !model.noDownload && !modelStore.isInstalled(model)) {
                downloadModel(embedderId)
            } else {
                reembedVault()
            }
            refreshAiState()
        }
    }

    fun setLlm(llmId: String) {
        viewModelScope.launch {
            if (llmId == "gemini" && !apiKeyStore.hasGeminiKey()) {
                _snackbarMessage.value = "Add your Gemini API key first (below)."
                return@launch
            }
            val model = ModelCatalog.byId(llmId)
            if (model != null && !model.noDownload && !modelStore.isInstalled(model)) {
                aiService.setLlm(llmId)
                downloadModel(llmId)
            } else {
                aiService.setLlm(llmId)
            }
            refreshAiState()
        }
    }

    fun setBackendPreference(preference: AiSettings.BackendPreference) {
        aiSettings.backendPreference = preference
        viewModelScope.launch { aiService.closeEngines() }
        refreshAiState()
    }

    fun setWifiOnlyDownloads(wifiOnly: Boolean) {
        aiSettings.wifiOnlyDownloads = wifiOnly
        refreshAiState()
    }

    fun setGeminiModel(modelName: String) {
        val trimmed = modelName.trim()
        if (trimmed.isEmpty()) return
        aiSettings.geminiModel = trimmed
        aiService.refreshLlmModel()
        refreshAiState()
    }

    /** Global matching instruction. Trimmed + capped by AiSettings. */
    fun setCustomInstruction(text: String) {
        aiSettings.customInstruction = text
        refreshAiState()
    }

    fun saveGeminiKey(key: String) {
        if (key.isBlank()) {
            _snackbarMessage.value = "Paste your Gemini API key first."
            return
        }
        apiKeyStore.setGeminiKey(key)
        _aiUiState.value = _aiUiState.value.copy(
            hasGeminiKey = true,
            keyTestState = KeyTestState.Idle
        )
        testGeminiKey()
    }

    fun testGeminiKey() {
        val key = apiKeyStore.getGeminiKey()
        if (key == null) {
            _aiUiState.value = _aiUiState.value.copy(
                keyTestState = KeyTestState.Error("No key saved yet.")
            )
            return
        }
        _aiUiState.value = _aiUiState.value.copy(keyTestState = KeyTestState.Testing)
        viewModelScope.launch {
            val result = runCatching { GeminiClient(key).testKey() }
            _aiUiState.value = _aiUiState.value.copy(
                keyTestState = result.fold(
                    onSuccess = { KeyTestState.Ok },
                    onFailure = { KeyTestState.Error(it.message ?: "Key test failed.") }
                )
            )
        }
    }

    fun clearGeminiKey() {
        viewModelScope.launch {
            if (aiSettings.embedderId == "gemini") {
                aiService.setEmbedder(ModelCatalog.MINILM.id)
            }
            if (aiSettings.llmId == "gemini") {
                aiService.setLlm(AiSettings.LLM_NONE)
            }
            apiKeyStore.clearGeminiKey()
            _aiUiState.value = _aiUiState.value.copy(
                hasGeminiKey = false,
                keyTestState = KeyTestState.Idle
            )
            refreshAiState()
        }
    }

    // ---- Audio transcription settings ----

    fun setTranscriptionEnabled(enabled: Boolean) {
        aiSettings.transcriptionEnabled = enabled
        refreshAiState()
    }

    fun saveGroqKey(key: String) {
        if (key.isBlank()) {
            _snackbarMessage.value = "Paste your Groq API key first."
            return
        }
        apiKeyStore.setGroqKey(key)
        _snackbarMessage.value = "Groq key saved."
        refreshAiState()
    }

    fun clearGroqKey() {
        apiKeyStore.clearGroqKey()
        refreshAiState()
    }

    fun reembedVault() {
        viewModelScope.launch {
            val ideas = repository.getAllIdeasOnce()
            val count = aiService.reembedVault(ideas)
            if (count > 0) {
                _snackbarMessage.value = "Embedded $count ideas for AI matching."
            }
            refreshAiState()
        }
    }

    fun explainMatch(ideaId: Long, query: String, songInstruction: String? = null) {
        _explanations.value = _explanations.value + (ideaId to ExplanationState.Loading)
        viewModelScope.launch {
            val result = runCatching {
                val detail = repository.getIdeaOnce(ideaId)
                    ?: throw IllegalStateException("That idea is gone.")
                aiService.explainMatch(
                    detail,
                    query,
                    AiMatchingService.resolveInstruction(
                        songInstruction,
                        aiSettings.customInstruction
                    )
                )
            }
            _explanations.value = _explanations.value + (
                ideaId to result.fold(
                    onSuccess = { ExplanationState.Done(it) },
                    onFailure = { ExplanationState.Error(it.message ?: "Could not explain.") }
                )
            )
        }
    }

    fun clearExplanation(ideaId: Long) {
        _explanations.value = _explanations.value - ideaId
    }

    /**
     * Drops every generated "Why this fits" explanation. Explanations are
     * session-scoped to one song visit: the songwriting screen calls this
     * when a song is opened and when it is left, so a reason written for
     * one song (or an earlier visit) never shows up under another.
     */
    fun clearExplanations() {
        _explanations.value = emptyMap()
    }

    fun exportVaultJson(
        uri: Uri,
        includeAudioBase64: Boolean,
        includeAiPrefs: Boolean,
        includeImagesBase64: Boolean
    ) {
        viewModelScope.launch {
            _backupState.value = BackupOperationState.Working()
            val result = backupManager.exportToUri(
                uri, includeAudioBase64, includeAiPrefs, includeImagesBase64
            )
            result.fold(
                onSuccess = { summary: BackupSummary ->
                    val mediaParts = buildList {
                        if (includeAudioBase64) add("${summary.audioClipsCount} audio clips")
                        if (includeImagesBase64) add("${summary.imagesCount} images")
                    }
                    val mediaPart = if (mediaParts.isEmpty()) {
                        " (text-only JSON)"
                    } else {
                        ", " + mediaParts.joinToString(", ")
                    }
                    _backupState.value = BackupOperationState.Success(
                        "Exported ${summary.ideasCount} ideas, ${summary.songsCount} songs$mediaPart."
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

    fun importVaultFile(
        uri: Uri,
        includeAudio: Boolean,
        applyAiPrefs: Boolean,
        includeImages: Boolean
    ) {
        viewModelScope.launch {
            _backupState.value = BackupOperationState.Working()
            var lastFraction = -1f
            var lastLabel = ""
            val result = backupManager.importFromUri(
                uri, includeAudio, applyAiPrefs, includeImages
            ) { fraction, label ->
                // The importer reports after every song/idea; only
                // republish when something visibly changed so the UI
                // is not rebuilt hundreds of times on a big file.
                if (label != lastLabel || fraction - lastFraction >= 0.01f) {
                    lastLabel = label
                    lastFraction = fraction
                    _backupState.value = BackupOperationState.Working(
                        message = label,
                        fraction = fraction
                    )
                }
            }
            result.fold(
                onSuccess = { summary: BackupSummary ->
                    val parts = buildList {
                        add("${summary.ideasCount} ideas")
                        if (summary.songsCount > 0) add("${summary.songsCount} songs")
                        if (summary.audioClipsCount > 0) add("${summary.audioClipsCount} audio clips")
                        if (summary.imagesCount > 0) add("${summary.imagesCount} images")
                    }
                    _backupState.value = BackupOperationState.Success(
                        "Imported ${parts.joinToString(", ")} into your local vault."
                    )
                    // New ideas need embeddings for AI matching.
                    if (summary.ideasCount > 0) reembedVault()
                    // A backup that carried AI settings has just applied
                    // them: fetch the selected models again so the
                    // restored setup works without a trip through the
                    // AI settings screen.
                    if (summary.appliedAiSettings) {
                        val queued = redownloadSelectedAiModels()
                        refreshAiState()
                        if (queued > 0) {
                            Toast.makeText(
                                getApplication(),
                                "AI models installing in the background…",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
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
            _backupState.value = BackupOperationState.Working()
            val count = repository.bulkImportPlainTextIdeas(rawText, defaultTag = tag.ifBlank { "imported" })
            if (count > 0) {
                _backupState.value = BackupOperationState.Success("Imported $count ideas into your Vault.")
                reembedVault()
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
        // AI engines are closed on engine switches; the process owns them
        // after this point (viewModelScope is already cancelled here, so a
        // suspend close would never run).
    }
}
