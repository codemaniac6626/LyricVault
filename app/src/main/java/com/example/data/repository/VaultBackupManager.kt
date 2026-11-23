package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.ai.AiSettings
import com.example.data.local.BlockType
import com.example.data.local.IdeaBlockEntity
import com.example.data.local.IdeaNoteEntity
import com.example.data.local.IdeaUsageEntity
import com.example.data.local.LyricVaultDao
import com.example.data.local.SongAudioBlockEntity
import com.example.data.local.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class BackupSummary(
    val ideasCount: Int,
    val songsCount: Int,
    val audioClipsCount: Int,
    /** Idea image blocks carried by the backup (exported or restored). */
    val imagesCount: Int = 0,
    /** True when the backup carried an aiSettings block and it was applied. */
    val appliedAiSettings: Boolean = false
)

class VaultBackupManager(
    private val context: Context,
    private val dao: LyricVaultDao,
    private val repository: LyricVaultRepository
) {
    // The parent-level (global) AI matching instruction lives in
    // AiSettings' SharedPreferences, outside the database, so the backup
    // reads/writes it through AiSettings directly. Per-song overrides are
    // SongEntity.customInstruction and already ride along with each song.
    private val aiSettings = AiSettings(context)
    suspend fun exportToUri(
        uri: Uri,
        includeAudioBase64: Boolean,
        includeAiPrefs: Boolean = true,
        includeImagesBase64: Boolean = false
    ): Result<BackupSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ideas = dao.getAllIdeasOnce()
                val songs = dao.getAllSongsOnce()
                var exportedAudioCount = 0
                var exportedImagesCount = 0

                val root = JSONObject().apply {
                    put("app", "LyricVault")
                    put("formatVersion", 1)
                    put("exportedAt", System.currentTimeMillis())
                    put("includesAudio", includeAudioBase64)
                    put("includesImages", includeImagesBase64)
                    if (includeAiPrefs) {
                        put("globalCustomInstruction", aiSettings.customInstruction)
                    }
                    // AI matching setup, as metadata only — never the
                    // model files themselves. On import these selections
                    // are re-applied and the chosen models re-downloaded.
                    if (includeAiPrefs) put(
                        "aiSettings",
                        JSONObject().apply {
                            put("aiEnabled", aiSettings.aiEnabled)
                            put("embedderId", aiSettings.embedderId)
                            put("llmId", aiSettings.llmId)
                            put("backend", aiSettings.backendPreference.name)
                            put("wifiOnlyDownloads", aiSettings.wifiOnlyDownloads)
                            put("geminiModel", aiSettings.geminiModel)
                        }
                    )
                }

                val ideasArray = JSONArray()
                ideas.forEach { detail ->
                    val blocksArray = JSONArray()
                    detail.orderedBlocks.forEach { block ->
                        val blockObj = JSONObject().apply {
                            put("orderIndex", block.orderIndex)
                            put("blockType", block.blockType)
                            put("textContent", block.textContent)
                            put("audioDurationMs", block.audioDurationMs)
                            put("createdAt", block.createdAt)
                            if (includeAudioBase64 && block.blockType == BlockType.AUDIO && !block.audioFilePath.isNullOrBlank()) {
                                val file = File(block.audioFilePath)
                                if (file.exists() && file.length() <= 12L * 1024L * 1024L) {
                                    val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                                    put("audioBase64", encoded)
                                    exportedAudioCount++
                                }
                            }
                            if (includeImagesBase64 && block.blockType == BlockType.IMAGE && !block.imageFilePath.isNullOrBlank()) {
                                val file = File(block.imageFilePath)
                                if (file.exists() && file.length() <= 12L * 1024L * 1024L) {
                                    val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                                    put("imageBase64", encoded)
                                    put("imageExt", file.extension.ifBlank { "jpg" })
                                    exportedImagesCount++
                                }
                            }
                        }
                        blocksArray.put(blockObj)
                    }

                    val usagesArray = JSONArray()
                    detail.usages.forEach { usage ->
                        usagesArray.put(
                            JSONObject().apply {
                                put("songId", usage.songId)
                                put("songTitleSnapshot", usage.songTitleSnapshot)
                                put("usedAt", usage.usedAt)
                            }
                        )
                    }

                    val ideaObj = JSONObject().apply {
                        put("originalId", detail.idea.id)
                        put("title", detail.idea.title)
                        put("contextNote", detail.idea.contextNote)
                        put("tags", detail.idea.tags)
                        put("usedCount", detail.idea.usedCount)
                        put("createdAt", detail.idea.createdAt)
                        put("updatedAt", detail.idea.updatedAt)
                        put("blocks", blocksArray)
                        put("usages", usagesArray)
                    }
                    ideasArray.put(ideaObj)
                }
                root.put("ideas", ideasArray)

                val songsArray = JSONArray()
                songs.forEach { song ->
                    // Melody blocks recorded/imported inside this song
                    // (song_audio_blocks). Audio bytes ride along as
                    // Base64 when the caller asked for audio, under the
                    // same 12 MB per-file cap as idea clips. The block's
                    // sourceIdeaId points at an idea in this same backup;
                    // import remaps it to the restored idea's new id.
                    val audioBlocksArray = JSONArray()
                    dao.getSongAudioBlocksOnce(song.id).forEach { block ->
                        audioBlocksArray.put(
                            JSONObject().apply {
                                put("orderIndex", block.orderIndex)
                                put("label", block.label)
                                put("audioDurationMs", block.audioDurationMs)
                                put("createdAt", block.createdAt)
                                block.sourceIdeaId?.let { put("sourceIdeaId", it) }
                                if (includeAudioBase64) {
                                    val file = File(block.audioFilePath)
                                    if (file.exists() && file.length() <= 12L * 1024L * 1024L) {
                                        put(
                                            "audioBase64",
                                            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                                        )
                                        exportedAudioCount++
                                    }
                                }
                            }
                        )
                    }
                    songsArray.put(
                        JSONObject().apply {
                            put("originalId", song.id)
                            put("title", song.title)
                            put("vibeDescription", song.vibeDescription)
                            put("lyrics", song.lyrics)
                            // put(String, null) removes the key, which is
                            // fine: import treats a missing key as null.
                            put("customInstruction", song.customInstruction)
                            put("createdAt", song.createdAt)
                            put("updatedAt", song.updatedAt)
                            put("audioBlocks", audioBlocksArray)
                        }
                    )
                }
                root.put("songs", songsArray)

                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(root.toString(2).toByteArray(Charsets.UTF_8))
                } ?: error("Unable to open destination file for export.")

                BackupSummary(
                    ideasCount = ideas.size,
                    songsCount = songs.size,
                    audioClipsCount = exportedAudioCount,
                    imagesCount = exportedImagesCount
                )
            }
        }

    suspend fun importFromUri(
        uri: Uri,
        includeAudio: Boolean = true,
        applyAiPrefs: Boolean = true,
        includeImages: Boolean = true
    ): Result<BackupSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawContent = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: error("Unable to read selected file.")

                val trimmed = rawContent.trim()
                if (trimmed.isEmpty()) {
                    error("The selected file is empty.")
                }

                // Support plain-text note files seamlessly (e.g., Zoho Notebook or text exports)
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    val count = repository.bulkImportPlainTextIdeas(trimmed, defaultTag = "imported")
                    return@runCatching BackupSummary(ideasCount = count, songsCount = 0, audioClipsCount = 0)
                }

                // Support generic JSON array of idea objects/strings too
                if (trimmed.startsWith("[")) {
                    val array = JSONArray(trimmed)
                    var count = 0
                    val now = System.currentTimeMillis()
                    for (i in 0 until array.length()) {
                        val item = array.get(i)
                        val (title, body, contextNote, tags) = when (item) {
                            is JSONObject -> Quad(
                                item.optString("title", item.optString("name", "")),
                                item.optString("content", item.optString("lyrics", item.optString("text", item.optString("body", "")))),
                                item.optString("contextNote", item.optString("note", item.optString("why", ""))),
                                item.optString("tags", "imported")
                            )
                            else -> Quad("", item.toString(), "", "imported")
                        }
                        if (title.isBlank() && body.isBlank()) continue
                        val idea = IdeaNoteEntity(
                            title = title.ifBlank { body.lines().firstOrNull().orEmpty().take(52) },
                            contextNote = contextNote,
                            tags = tags,
                            createdAt = now,
                            updatedAt = now
                        )
                        val block = IdeaBlockEntity(
                            ideaId = 0L,
                            orderIndex = 0,
                            blockType = BlockType.TEXT,
                            textContent = body.ifBlank { title },
                            createdAt = now
                        )
                        dao.saveIdeaWithBlocks(idea, listOf(block))
                        count++
                    }
                    return@runCatching BackupSummary(ideasCount = count, songsCount = 0, audioClipsCount = 0)
                }

                val root = JSONObject(trimmed)
                // Parent-level AI instruction: restore it when the backup
                // carries one. Backups written before this key existed
                // leave the current instruction untouched.
                if (applyAiPrefs && root.has("globalCustomInstruction")) {
                    aiSettings.customInstruction =
                        root.optString("globalCustomInstruction", "")
                }
                // AI matching setup (see export): re-apply the selections
                // stored in the backup. The ViewModel is told via the
                // summary so it can re-download the selected models.
                var appliedAiSettings = false
                val aiJson = if (applyAiPrefs) root.optJSONObject("aiSettings") else null
                if (aiJson != null) {
                    if (aiJson.has("aiEnabled")) {
                        aiSettings.aiEnabled = aiJson.optBoolean("aiEnabled", false)
                    }
                    if (aiJson.has("embedderId")) {
                        aiSettings.embedderId = aiJson.optString("embedderId", "")
                    }
                    if (aiJson.has("llmId")) {
                        aiSettings.llmId = aiJson.optString("llmId", "")
                    }
                    if (aiJson.has("backend")) {
                        aiSettings.backendPreference = runCatching {
                            AiSettings.BackendPreference.valueOf(
                                aiJson.optString("backend", "")
                            )
                        }.getOrDefault(AiSettings.BackendPreference.AUTO)
                    }
                    if (aiJson.has("wifiOnlyDownloads")) {
                        aiSettings.wifiOnlyDownloads =
                            aiJson.optBoolean("wifiOnlyDownloads", true)
                    }
                    if (aiJson.has("geminiModel")) {
                        aiSettings.geminiModel = aiJson.optString("geminiModel", "")
                    }
                    appliedAiSettings = true
                }
                val songIdMap = mutableMapOf<Long, Long>()
                // Melody blocks are restored AFTER the ideas below, since a
                // block's sourceIdeaId must be remapped through ideaIdMap.
                val pendingSongAudio = mutableListOf<Pair<Long, JSONArray>>()
                var importedSongs = 0
                val songsArray = root.optJSONArray("songs") ?: JSONArray()
                for (i in 0 until songsArray.length()) {
                    val obj = songsArray.getJSONObject(i)
                    val oldId = obj.optLong("originalId", -1L)
                    val newSongId = dao.insertSong(
                        SongEntity(
                            title = obj.optString("title", ""),
                            vibeDescription = obj.optString("vibeDescription", ""),
                            lyrics = obj.optString("lyrics", ""),
                            // Old backups have no key: optString returns "",
                            // which collapses to null (use global instruction).
                            customInstruction = obj.optString("customInstruction", "")
                                .ifBlank { null },
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                    if (oldId > 0L) songIdMap[oldId] = newSongId
                    val blocksJson = obj.optJSONArray("audioBlocks")
                    if (blocksJson != null && blocksJson.length() > 0) {
                        pendingSongAudio.add(newSongId to blocksJson)
                    }
                    importedSongs++
                }

                var importedIdeas = 0
                var importedAudio = 0
                var importedImages = 0
                val ideaIdMap = mutableMapOf<Long, Long>()
                val audioDir = repository.audioClipsDirectory()
                val imagesDir = repository.ideaImagesDirectory()
                val ideasArray = root.optJSONArray("ideas") ?: JSONArray()
                for (i in 0 until ideasArray.length()) {
                    val obj = ideasArray.getJSONObject(i)
                    val now = System.currentTimeMillis()
                    val blocksArray = obj.optJSONArray("blocks") ?: JSONArray()
                    val blocks = mutableListOf<IdeaBlockEntity>()
                    for (b in 0 until blocksArray.length()) {
                        val bObj = blocksArray.getJSONObject(b)
                        val type = bObj.optString("blockType", BlockType.TEXT)
                        // "Leave out audio" import: melody blocks are
                        // skipped altogether, not restored as dead tiles.
                        if (type == BlockType.AUDIO && !includeAudio) continue
                        // Same rule for images: left out means skipped,
                        // not restored as dead tiles.
                        if (type == BlockType.IMAGE && !includeImages) continue
                        var restoredPath: String? = null
                        if (type == BlockType.AUDIO && bObj.has("audioBase64")) {
                            val b64 = bObj.optString("audioBase64", "")
                            if (b64.isNotBlank()) {
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                val outFile = File(audioDir, "clip_${UUID.randomUUID()}.m4a")
                                outFile.writeBytes(bytes)
                                restoredPath = outFile.absolutePath
                                importedAudio++
                            }
                        }
                        var restoredImagePath: String? = null
                        if (type == BlockType.IMAGE && bObj.has("imageBase64")) {
                            val b64 = bObj.optString("imageBase64", "")
                            if (b64.isNotBlank()) {
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                val ext = bObj.optString("imageExt", "jpg")
                                    .filter { it.isLetterOrDigit() }
                                    .ifBlank { "jpg" }
                                val outFile = File(imagesDir, "img_${UUID.randomUUID()}.$ext")
                                outFile.writeBytes(bytes)
                                restoredImagePath = outFile.absolutePath
                                importedImages++
                            }
                        }
                        blocks.add(
                            IdeaBlockEntity(
                                ideaId = 0L,
                                orderIndex = bObj.optInt("orderIndex", b),
                                blockType = type,
                                textContent = bObj.optString("textContent", ""),
                                audioFilePath = restoredPath,
                                audioDurationMs = bObj.optLong("audioDurationMs", 0L),
                                imageFilePath = restoredImagePath,
                                createdAt = bObj.optLong("createdAt", now)
                            )
                        )
                    }

                    val oldIdeaId = obj.optLong("originalId", -1L)
                    val newIdeaId = dao.saveIdeaWithBlocks(
                        IdeaNoteEntity(
                            title = obj.optString("title", ""),
                            contextNote = obj.optString("contextNote", ""),
                            tags = obj.optString("tags", ""),
                            usedCount = obj.optInt("usedCount", 0),
                            createdAt = obj.optLong("createdAt", now),
                            updatedAt = obj.optLong("updatedAt", now)
                        ),
                        blocks
                    )

                    if (oldIdeaId > 0L) ideaIdMap[oldIdeaId] = newIdeaId

                    val usagesArray = obj.optJSONArray("usages") ?: JSONArray()
                    for (u in 0 until usagesArray.length()) {
                        val uObj = usagesArray.getJSONObject(u)
                        val mappedSongId = songIdMap[uObj.optLong("songId", -1L)]
                        if (mappedSongId != null) {
                            dao.insertUsage(
                                IdeaUsageEntity(
                                    ideaId = newIdeaId,
                                    songId = mappedSongId,
                                    songTitleSnapshot = uObj.optString("songTitleSnapshot", ""),
                                    usedAt = uObj.optLong("usedAt", now)
                                )
                            )
                        }
                    }
                    importedIdeas++
                }

                // Restore song melody blocks now that both id maps are
                // complete. A block is restored only when the backup
                // carries its audio bytes (same rule as idea clips: a
                // no-audio export cannot recreate the file). The source
                // idea link is remapped when that idea was restored too,
                // and dropped when it was not part of this backup.
                for ((newSongId, blocksJson) in if (includeAudio) {
                    pendingSongAudio
                } else {
                    emptyList()
                }) {
                    for (b in 0 until blocksJson.length()) {
                        val bObj = blocksJson.getJSONObject(b)
                        val b64 = bObj.optString("audioBase64", "")
                        if (b64.isBlank()) continue
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val outFile = File(audioDir, "song_clip_${UUID.randomUUID()}.m4a")
                        outFile.writeBytes(bytes)
                        importedAudio++
                        dao.insertSongAudioBlock(
                            SongAudioBlockEntity(
                                songId = newSongId,
                                orderIndex = bObj.optInt("orderIndex", b),
                                label = bObj.optString("label", ""),
                                audioFilePath = outFile.absolutePath,
                                audioDurationMs = bObj.optLong("audioDurationMs", 0L),
                                sourceIdeaId = if (bObj.has("sourceIdeaId")) {
                                    ideaIdMap[bObj.optLong("sourceIdeaId", -1L)]
                                } else {
                                    null
                                },
                                createdAt = bObj.optLong(
                                    "createdAt",
                                    System.currentTimeMillis()
                                )
                            )
                        )
                    }
                }

                BackupSummary(
                    ideasCount = importedIdeas,
                    songsCount = importedSongs,
                    audioClipsCount = importedAudio,
                    imagesCount = importedImages,
                    appliedAiSettings = appliedAiSettings
                )
            }
        }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)
}
