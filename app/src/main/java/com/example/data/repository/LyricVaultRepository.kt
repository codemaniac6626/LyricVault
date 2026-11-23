package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.data.local.BlockType
import com.example.data.local.IdeaBlockEntity
import com.example.data.local.IdeaNoteEntity
import com.example.data.local.IdeaNoteWithDetails
import com.example.data.local.IdeaUsageEntity
import com.example.data.local.LyricVaultDao
import com.example.data.local.SongAudioBlockEntity
import com.example.data.local.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class LyricVaultRepository(
    private val dao: LyricVaultDao,
    private val appContext: Context
) {
    val allIdeas: Flow<List<IdeaNoteWithDetails>> = dao.observeAllIdeas()
    val allSongs: Flow<List<SongEntity>> = dao.observeAllSongs()

    fun observeIdea(ideaId: Long): Flow<IdeaNoteWithDetails?> = dao.observeIdeaById(ideaId)

    fun observeSong(songId: Long): Flow<SongEntity?> = dao.observeSongById(songId)

    suspend fun getAllIdeasOnce(): List<IdeaNoteWithDetails> = dao.getAllIdeasOnce()

    suspend fun getAllSongsOnce(): List<SongEntity> = dao.getAllSongsOnce()

    suspend fun getIdeaOnce(ideaId: Long): IdeaNoteWithDetails? = dao.getIdeaByIdOnce(ideaId)

    suspend fun getSongOnce(songId: Long): SongEntity? = dao.getSongByIdOnce(songId)

    suspend fun saveIdea(
        idea: IdeaNoteEntity,
        blocks: List<IdeaBlockEntity>
    ): Long = dao.saveIdeaWithBlocks(idea, blocks)

    suspend fun deleteIdea(ideaId: Long) {
        val existing = dao.getIdeaByIdOnce(ideaId)
        dao.deleteIdeaById(ideaId)
        withContext(Dispatchers.IO) {
            existing?.blocks?.forEach { block ->
                when (block.blockType) {
                    BlockType.AUDIO ->
                        if (!block.audioFilePath.isNullOrBlank()) {
                            runCatching { File(block.audioFilePath!!).delete() }
                        }
                    BlockType.IMAGE ->
                        if (!block.imageFilePath.isNullOrBlank()) {
                            runCatching { File(block.imageFilePath!!).delete() }
                        }
                }
            }
        }
    }

    suspend fun deleteAudioFileIfExists(path: String?) {
        deleteBlockFileIfExists(path)
    }

    /** Deletes any block-owned media file (audio clip or idea image). */
    suspend fun deleteBlockFileIfExists(path: String?) {
        if (path.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            runCatching { File(path).delete() }
        }
    }

    /**
     * Copies an image picked by the user (photo picker / document picker
     * Uri) into the app's own images directory and returns the new file's
     * path. The copy matters: picker Uris are temporary grants, while the
     * idea must own its image for as long as it exists — same reason idea
     * audio is recorded straight into the clips directory.
     */
    suspend fun importImageFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val mime = appContext.contentResolver.getType(uri)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?.takeIf { it.isNotBlank() } ?: "jpg"
            val target = File(
                ideaImagesDirectory(),
                "idea_img_${System.currentTimeMillis()}_${System.nanoTime()}.$ext"
            )
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            if (target.exists() && target.length() > 0L) target.absolutePath else null
        }.getOrNull()
    }

    suspend fun saveSong(song: SongEntity): Long {
        val now = System.currentTimeMillis()
        return if (song.id == 0L) {
            dao.insertSong(song.copy(createdAt = now, updatedAt = now))
        } else {
            dao.updateSong(song.copy(updatedAt = now))
            song.id
        }
    }

    suspend fun deleteSong(songId: Long) {
        // Rows in song_audio_blocks cascade with the song row, but the
        // audio files are the song's own copies and need deleting by hand.
        val audioBlocks = dao.getSongAudioBlocksOnce(songId)
        dao.deleteSongById(songId)
        withContext(Dispatchers.IO) {
            audioBlocks.forEach { block ->
                runCatching { File(block.audioFilePath).delete() }
            }
        }
    }

    fun observeSongAudioBlocks(songId: Long): Flow<List<SongAudioBlockEntity>> =
        dao.observeSongAudioBlocks(songId)

    suspend fun addSongAudioBlock(
        songId: Long,
        label: String,
        audioFilePath: String,
        audioDurationMs: Long,
        sourceIdeaId: Long? = null
    ): Long {
        val nextIndex = dao.maxSongAudioOrderIndex(songId) + 1
        return dao.insertSongAudioBlock(
            SongAudioBlockEntity(
                songId = songId,
                orderIndex = nextIndex,
                label = label,
                audioFilePath = audioFilePath,
                audioDurationMs = audioDurationMs,
                sourceIdeaId = sourceIdeaId
            )
        )
    }

    suspend fun updateSongAudioBlock(block: SongAudioBlockEntity) {
        dao.updateSongAudioBlock(block)
    }

    suspend fun deleteSongAudioBlock(block: SongAudioBlockEntity) {
        dao.deleteSongAudioBlockById(block.id)
        deleteAudioFileIfExists(block.audioFilePath)
    }

    /**
     * Brings every audio block of [idea] into the song as song-owned
     * blocks, each labelled with the idea's name. The audio files are
     * COPIED into the clips directory: an idea's files are deleted when
     * the idea is deleted, so referencing them would leave the song with
     * dead audio. Returns how many blocks were added.
     */
    suspend fun copyIdeaAudioIntoSong(songId: Long, idea: IdeaNoteWithDetails): Int {
        val audioBlocks = idea.orderedBlocks.filter {
            it.blockType == BlockType.AUDIO && !it.audioFilePath.isNullOrBlank()
        }
        var added = 0
        for (block in audioBlocks) {
            val copyPath = copyAudioFileIntoClipsDir(block.audioFilePath!!) ?: continue
            addSongAudioBlock(
                songId = songId,
                label = idea.displayHeadline,
                audioFilePath = copyPath,
                audioDurationMs = block.audioDurationMs,
                sourceIdeaId = idea.idea.id
            )
            added++
        }
        return added
    }

    private suspend fun copyAudioFileIntoClipsDir(sourcePath: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(sourcePath)
                if (!source.exists()) return@runCatching null
                val ext = source.extension.ifBlank { "m4a" }
                val target = File(
                    audioClipsDirectory(),
                    "song_copy_${System.currentTimeMillis()}_${System.nanoTime()}.$ext"
                )
                source.copyTo(target, overwrite = false)
                target.absolutePath
            }.getOrNull()
        }

    suspend fun markIdeaUsedInSong(ideaId: Long, songId: Long, songTitle: String) {
        val now = System.currentTimeMillis()
        dao.incrementIdeaUsedCount(ideaId, now)
        if (songId > 0L) {
            dao.insertUsage(
                IdeaUsageEntity(
                    ideaId = ideaId,
                    songId = songId,
                    songTitleSnapshot = songTitle.ifBlank { "Untitled Song" },
                    usedAt = now
                )
            )
        }
    }

    suspend fun resetIdeaUsage(ideaId: Long) {
        dao.clearUsagesForIdea(ideaId)
        dao.resetIdeaUsedCount(ideaId)
    }

    suspend fun bulkImportPlainTextIdeas(rawText: String, defaultTag: String = "imported"): Int {
        val chunks = rawText
            .replace("\r\n", "\n")
            .split(Regex("\\n\\s*\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        var importedCount = 0
        val now = System.currentTimeMillis()
        chunks.forEachIndexed { index, chunk ->
            val lines = chunk.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return@forEachIndexed
            val title = if (lines.size > 1 && lines.first().length <= 64) {
                lines.first()
            } else {
                lines.first().take(52)
            }
            val body = chunk
            val idea = IdeaNoteEntity(
                title = title,
                contextNote = "",
                tags = defaultTag,
                usedCount = 0,
                createdAt = now - (chunks.size - index) * 1000L,
                updatedAt = now - (chunks.size - index) * 1000L
            )
            val block = IdeaBlockEntity(
                ideaId = 0L,
                orderIndex = 0,
                blockType = BlockType.TEXT,
                textContent = body,
                createdAt = idea.createdAt
            )
            dao.saveIdeaWithBlocks(idea, listOf(block))
            importedCount++
        }
        return importedCount
    }

    fun audioClipsDirectory(): File {
        val dir = File(appContext.filesDir, "audio_clips")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun ideaImagesDirectory(): File {
        val dir = File(appContext.filesDir, "idea_images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
