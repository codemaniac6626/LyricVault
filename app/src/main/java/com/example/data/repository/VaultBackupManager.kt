package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
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
import java.io.InputStreamReader
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
    /**
     * Imports a backup in batches, streaming the JSON with [JsonReader]
     * instead of loading the whole file into memory. The old importer
     * read the entire file as one String and built a full JSONObject
     * tree; a media-heavy backup (tens of MB of Base64) blew the app's
     * 256 MB heap and the import died with an OutOfMemoryError before
     * restoring anything. Streaming keeps at most one block's payload in
     * memory at a time: each idea is parsed, written to the database and
     * released before the next one is read, and [onProgress] fires as
     * batches land so the UI can show real progress.
     *
     * The passes over the file are:
     *  0. count ideas/songs (progress needs the totals up front);
     *  1. AI prefs + song rows (usages inside ideas point at songs, so
     *     the song id map must be complete before ideas are read);
     *  2. ideas, one at a time, resolving usages through the song map;
     *  3. song melody blocks (their source-idea links need the idea id
     *     map from pass 2).
     * Plain-text and bare-JSON-array files keep the legacy small-file
     * path: they carry no Base64 media, so they cannot grow the way a
     * vault backup does.
     */
    suspend fun importFromUri(
        uri: Uri,
        includeAudio: Boolean = true,
        applyAiPrefs: Boolean = true,
        includeImages: Boolean = true,
        onProgress: (fraction: Float, label: String) -> Unit = { _, _ -> }
    ): Result<BackupSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val firstByte = peekFirstNonWhitespaceByte(uri)
                    ?: error("The selected file is empty.")
                if (firstByte == '{'.code) {
                    importVaultJsonStreaming(
                        uri, includeAudio, applyAiPrefs, includeImages, onProgress
                    )
                } else {
                    val rawContent = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    } ?: error("Unable to read selected file.")
                    importFromTextContent(rawContent)
                }
            }
        }

    private fun peekFirstNonWhitespaceByte(uri: Uri): Int? {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("Unable to read selected file.")
        stream.use {
            val buffer = ByteArray(8192)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) return null
                for (i in 0 until read) {
                    val b = buffer[i].toInt()
                    if (b != ' '.code && b != '\n'.code && b != '\r'.code && b != '\t'.code) {
                        return b
                    }
                }
            }
        }
    }

    /** Legacy import for plain-text note files and bare JSON arrays. */
    private suspend fun importFromTextContent(rawContent: String): BackupSummary {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) {
            error("The selected file is empty.")
        }

        // Support plain-text note files seamlessly (e.g., Zoho Notebook or text exports)
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            val count = repository.bulkImportPlainTextIdeas(trimmed, defaultTag = "imported")
            return BackupSummary(ideasCount = count, songsCount = 0, audioClipsCount = 0)
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
            return BackupSummary(ideasCount = count, songsCount = 0, audioClipsCount = 0)
        }

        // A "{" file always takes the streaming path above; reaching
        // here means the peek and the read disagreed about the content.
        error("The selected file could not be read as a backup.")
    }

    private fun openReader(uri: Uri): JsonReader {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("Unable to read selected file.")
        return JsonReader(InputStreamReader(stream, Charsets.UTF_8))
    }

    private suspend fun importVaultJsonStreaming(
        uri: Uri,
        includeAudio: Boolean,
        applyAiPrefs: Boolean,
        includeImages: Boolean,
        onProgress: (Float, String) -> Unit
    ): BackupSummary {
        val audioDir = repository.audioClipsDirectory()
        val imagesDir = repository.ideaImagesDirectory()
        val counters = ImportCounters()
        var appliedAiSettings = false

        // Pass 0: count ideas and songs so progress can be determinate.
        // skipValue walks the tokens without materialising the Base64
        // payloads, so this pass is cheap even on a huge file.
        onProgress(0f, "Reading backup…")
        var totalIdeas = 0
        var totalSongs = 0
        openReader(uri).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "ideas" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.skipValue()
                            totalIdeas++
                        }
                        reader.endArray()
                    }
                    "songs" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.skipValue()
                            totalSongs++
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        val totalUnits = (totalIdeas + totalSongs).coerceAtLeast(1)

        // Pass 1: parent-level AI prefs + song rows. Idea usages point
        // at songs, so the song id map must be complete before pass 2.
        // Song melody blocks are deferred to pass 3: their source-idea
        // links need the idea id map, which only exists after pass 2.
        val songIdMap = mutableMapOf<Long, Long>()
        var importedSongs = 0
        var songsWithAudio = false
        openReader(uri).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "globalCustomInstruction" -> {
                        val value = reader.nextStringOr("")
                        if (applyAiPrefs) aiSettings.customInstruction = value
                    }
                    "aiSettings" -> {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            readAiSettings(reader, applyAiPrefs)
                            if (applyAiPrefs) appliedAiSettings = true
                        } else {
                            reader.skipValue()
                        }
                    }
                    "songs" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val (oldId, newId, hasAudio) = readSongRow(reader)
                            if (oldId > 0L) songIdMap[oldId] = newId
                            if (hasAudio) songsWithAudio = true
                            importedSongs++
                            onProgress(
                                (importedSongs).toFloat() / totalUnits,
                                "Importing songs… $importedSongs of $totalSongs"
                            )
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        // Pass 2: ideas, one at a time. Each idea's blocks are decoded
        // and written to disk as they stream past; nothing accumulates.
        val ideaIdMap = mutableMapOf<Long, Long>()
        var importedIdeas = 0
        openReader(uri).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "ideas") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readAndSaveIdea(
                            reader, includeAudio, includeImages,
                            audioDir, imagesDir, counters, songIdMap, ideaIdMap
                        )
                        importedIdeas++
                        onProgress(
                            (importedSongs + importedIdeas).toFloat() / totalUnits,
                            "Importing ideas… $importedIdeas of $totalIdeas"
                        )
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }

        // Pass 3: song melody blocks, now that both id maps are known.
        if (includeAudio && songsWithAudio) {
            onProgress(
                (importedSongs + importedIdeas).toFloat() / totalUnits,
                "Restoring song melodies…"
            )
            openReader(uri).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "songs") {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            readSongAudioBlocks(reader, songIdMap, ideaIdMap, audioDir, counters)
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }

        return BackupSummary(
            ideasCount = importedIdeas,
            songsCount = importedSongs,
            audioClipsCount = counters.audio,
            imagesCount = counters.images,
            appliedAiSettings = appliedAiSettings
        )
    }

    /** Reads the aiSettings object; applies it only when [apply] is set. */
    private fun readAiSettings(reader: JsonReader, apply: Boolean) {
        var aiEnabled: Boolean? = null
        var embedderId: String? = null
        var llmId: String? = null
        var backend: String? = null
        var wifiOnly: Boolean? = null
        var geminiModel: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "aiEnabled" -> aiEnabled = reader.nextBooleanOr(false)
                "embedderId" -> embedderId = reader.nextStringOr("")
                "llmId" -> llmId = reader.nextStringOr("")
                "backend" -> backend = reader.nextStringOr("")
                "wifiOnlyDownloads" -> wifiOnly = reader.nextBooleanOr(true)
                "geminiModel" -> geminiModel = reader.nextStringOr("")
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (!apply) return
        aiEnabled?.let { aiSettings.aiEnabled = it }
        embedderId?.let { aiSettings.embedderId = it }
        llmId?.let { aiSettings.llmId = it }
        backend?.let {
            aiSettings.backendPreference = runCatching {
                AiSettings.BackendPreference.valueOf(it)
            }.getOrDefault(AiSettings.BackendPreference.AUTO)
        }
        wifiOnly?.let { aiSettings.wifiOnlyDownloads = it }
        geminiModel?.let { aiSettings.geminiModel = it }
    }

    /**
     * Reads one song object and inserts the song row. Melody blocks are
     * skipped (pass 3 restores them); the third return value reports
     * whether the song carried any, so pass 3 knows there is work.
     */
    private suspend fun readSongRow(reader: JsonReader): Triple<Long, Long, Boolean> {
        var oldId = -1L
        var title = ""
        var vibe = ""
        var lyrics = ""
        var custom: String? = null
        var created = System.currentTimeMillis()
        var updated = created
        var hasAudio = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "originalId" -> oldId = reader.nextLongOr(-1L)
                "title" -> title = reader.nextStringOr("")
                "vibeDescription" -> vibe = reader.nextStringOr("")
                "lyrics" -> lyrics = reader.nextStringOr("")
                "customInstruction" -> custom =
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        null
                    } else {
                        reader.nextStringOr("").ifBlank { null }
                    }
                "createdAt" -> created = reader.nextLongOr(created)
                "updatedAt" -> updated = reader.nextLongOr(updated)
                "audioBlocks" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.skipValue()
                            hasAudio = true
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val newId = dao.insertSong(
            SongEntity(
                title = title,
                vibeDescription = vibe,
                lyrics = lyrics,
                customInstruction = custom,
                createdAt = created,
                updatedAt = updated
            )
        )
        return Triple(oldId, newId, hasAudio)
    }

    /** Reads one idea (blocks + usages included) and saves it. */
    private suspend fun readAndSaveIdea(
        reader: JsonReader,
        includeAudio: Boolean,
        includeImages: Boolean,
        audioDir: File,
        imagesDir: File,
        counters: ImportCounters,
        songIdMap: Map<Long, Long>,
        ideaIdMap: MutableMap<Long, Long>
    ) {
        val now = System.currentTimeMillis()
        var oldId = -1L
        var title = ""
        var contextNote = ""
        var tags = ""
        var usedCount = 0
        var created = now
        var updated = now
        val blocks = mutableListOf<IdeaBlockEntity>()
        val usages = mutableListOf<Triple<Long, String, Long>>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "originalId" -> oldId = reader.nextLongOr(-1L)
                "title" -> title = reader.nextStringOr("")
                "contextNote" -> contextNote = reader.nextStringOr("")
                "tags" -> tags = reader.nextStringOr("")
                "usedCount" -> usedCount = reader.nextIntOr(0)
                "createdAt" -> created = reader.nextLongOr(now)
                "updatedAt" -> updated = reader.nextLongOr(now)
                "blocks" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        var fallbackIndex = 0
                        while (reader.hasNext()) {
                            val block = readIdeaBlock(
                                reader, includeAudio, includeImages,
                                audioDir, imagesDir, now, counters, fallbackIndex
                            )
                            fallbackIndex++
                            if (block != null) blocks.add(block)
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                "usages" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var songId = -1L
                            var snapshot = ""
                            var usedAt = now
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "songId" -> songId = reader.nextLongOr(-1L)
                                    "songTitleSnapshot" -> snapshot = reader.nextStringOr("")
                                    "usedAt" -> usedAt = reader.nextLongOr(now)
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            usages.add(Triple(songId, snapshot, usedAt))
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val newIdeaId = dao.saveIdeaWithBlocks(
            IdeaNoteEntity(
                title = title,
                contextNote = contextNote,
                tags = tags,
                usedCount = usedCount,
                createdAt = created,
                updatedAt = updated
            ),
            blocks
        )
        if (oldId > 0L) ideaIdMap[oldId] = newIdeaId
        usages.forEach { (oldSongId, snapshot, usedAt) ->
            val mappedSongId = songIdMap[oldSongId] ?: return@forEach
            dao.insertUsage(
                IdeaUsageEntity(
                    ideaId = newIdeaId,
                    songId = mappedSongId,
                    songTitleSnapshot = snapshot,
                    usedAt = usedAt
                )
            )
        }
    }

    /**
     * Reads one idea block. Field order inside the object is not
     * assumed: media payloads are held (one at a time) until the object
     * ends and the block type is known, then decoded straight to disk.
     * Returns null for media blocks the import options exclude — the
     * same "skip, don't restore as dead tiles" rule as before.
     */
    private fun readIdeaBlock(
        reader: JsonReader,
        includeAudio: Boolean,
        includeImages: Boolean,
        audioDir: File,
        imagesDir: File,
        now: Long,
        counters: ImportCounters,
        fallbackIndex: Int
    ): IdeaBlockEntity? {
        var orderIndex = fallbackIndex
        var type = BlockType.TEXT
        var text = ""
        var duration = 0L
        var created = now
        var audioB64: String? = null
        var imageB64: String? = null
        var imageExt = "jpg"
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "orderIndex" -> orderIndex = reader.nextIntOr(fallbackIndex)
                "blockType" -> type = reader.nextStringOr(BlockType.TEXT)
                "textContent" -> text = reader.nextStringOr("")
                "audioDurationMs" -> duration = reader.nextLongOr(0L)
                "createdAt" -> created = reader.nextLongOr(now)
                "audioBase64" -> audioB64 = reader.nextStringOrNull()
                "imageBase64" -> imageB64 = reader.nextStringOrNull()
                "imageExt" -> imageExt = reader.nextStringOr("jpg")
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (type == BlockType.AUDIO && !includeAudio) return null
        if (type == BlockType.IMAGE && !includeImages) return null

        var audioPath: String? = null
        if (type == BlockType.AUDIO && !audioB64.isNullOrBlank()) {
            val bytes = Base64.decode(audioB64, Base64.DEFAULT)
            val outFile = File(audioDir, "clip_${UUID.randomUUID()}.m4a")
            outFile.writeBytes(bytes)
            audioPath = outFile.absolutePath
            counters.audio++
        }
        var imagePath: String? = null
        if (type == BlockType.IMAGE && !imageB64.isNullOrBlank()) {
            val bytes = Base64.decode(imageB64, Base64.DEFAULT)
            val ext = imageExt.filter { it.isLetterOrDigit() }.ifBlank { "jpg" }
            val outFile = File(imagesDir, "img_${UUID.randomUUID()}.$ext")
            outFile.writeBytes(bytes)
            imagePath = outFile.absolutePath
            counters.images++
        }
        return IdeaBlockEntity(
            ideaId = 0L,
            orderIndex = orderIndex,
            blockType = type,
            textContent = text,
            audioFilePath = audioPath,
            audioDurationMs = duration,
            imageFilePath = imagePath,
            createdAt = created
        )
    }

    /**
     * Pass 3 worker: reads one song object and restores its melody
     * blocks. Relies on the exporter's field order (originalId first,
     * audioBlocks last — the order exportToUri writes them), since the
     * target song id must be known before its blocks stream past. A block is restored only when the backup carries its
     * audio bytes (same rule as idea clips); the source-idea link is
     * remapped when that idea was restored too, and dropped when not.
     */
    private suspend fun readSongAudioBlocks(
        reader: JsonReader,
        songIdMap: Map<Long, Long>,
        ideaIdMap: Map<Long, Long>,
        audioDir: File,
        counters: ImportCounters
    ) {
        var oldId = -1L
        var newSongId: Long? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "originalId" -> {
                    oldId = reader.nextLongOr(-1L)
                    newSongId = songIdMap[oldId]
                }
                "audioBlocks" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        var fallbackIndex = 0
                        while (reader.hasNext()) {
                            readOneSongAudioBlock(
                                reader, newSongId, ideaIdMap, audioDir, counters, fallbackIndex
                            )
                            fallbackIndex++
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private suspend fun readOneSongAudioBlock(
        reader: JsonReader,
        newSongId: Long?,
        ideaIdMap: Map<Long, Long>,
        audioDir: File,
        counters: ImportCounters,
        fallbackIndex: Int
    ) {
        var orderIndex = fallbackIndex
        var label = ""
        var duration = 0L
        var created = System.currentTimeMillis()
        var sourceOldId: Long? = null
        var audioB64: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "orderIndex" -> orderIndex = reader.nextIntOr(fallbackIndex)
                "label" -> label = reader.nextStringOr("")
                "audioDurationMs" -> duration = reader.nextLongOr(0L)
                "createdAt" -> created = reader.nextLongOr(created)
                "sourceIdeaId" -> sourceOldId =
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        null
                    } else {
                        reader.nextLongOr(-1L)
                    }
                "audioBase64" -> audioB64 = reader.nextStringOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (newSongId == null || audioB64.isNullOrBlank()) return
        val bytes = Base64.decode(audioB64, Base64.DEFAULT)
        val outFile = File(audioDir, "song_clip_${UUID.randomUUID()}.m4a")
        outFile.writeBytes(bytes)
        counters.audio++
        dao.insertSongAudioBlock(
            SongAudioBlockEntity(
                songId = newSongId,
                orderIndex = orderIndex,
                label = label,
                audioFilePath = outFile.absolutePath,
                audioDurationMs = duration,
                sourceIdeaId = sourceOldId?.let { ideaIdMap[it] },
                createdAt = created
            )
        )
    }

    private class ImportCounters {
        var audio = 0
        var images = 0
    }

    private fun JsonReader.nextStringOr(default: String): String =
        when (peek()) {
            JsonToken.STRING -> nextString()
            JsonToken.NULL -> {
                nextNull()
                default
            }
            else -> {
                skipValue()
                default
            }
        }

    /** Reads a string payload that may legitimately be absent/null. */
    private fun JsonReader.nextStringOrNull(): String? =
        when (peek()) {
            JsonToken.STRING -> nextString()
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }

    private fun JsonReader.nextLongOr(default: Long): Long =
        when (peek()) {
            JsonToken.NUMBER -> nextLong()
            JsonToken.STRING -> {
                val raw = nextString()
                raw.toLongOrNull() ?: default
            }
            JsonToken.NULL -> {
                nextNull()
                default
            }
            else -> {
                skipValue()
                default
            }
        }

    private fun JsonReader.nextIntOr(default: Int): Int =
        nextLongOr(default.toLong()).toInt()

    private fun JsonReader.nextBooleanOr(default: Boolean): Boolean =
        when (peek()) {
            JsonToken.BOOLEAN -> nextBoolean()
            JsonToken.NULL -> {
                nextNull()
                default
            }
            else -> {
                skipValue()
                default
            }
        }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)
}

