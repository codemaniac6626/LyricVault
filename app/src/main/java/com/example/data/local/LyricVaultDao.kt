package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LyricVaultDao {

    @Transaction
    @Query("SELECT * FROM idea_notes ORDER BY updatedAt DESC")
    fun observeAllIdeas(): Flow<List<IdeaNoteWithDetails>>

    @Transaction
    @Query("SELECT * FROM idea_notes ORDER BY updatedAt DESC")
    suspend fun getAllIdeasOnce(): List<IdeaNoteWithDetails>

    @Transaction
    @Query("SELECT * FROM idea_notes WHERE id = :ideaId LIMIT 1")
    fun observeIdeaById(ideaId: Long): Flow<IdeaNoteWithDetails?>

    @Transaction
    @Query("SELECT * FROM idea_notes WHERE id = :ideaId LIMIT 1")
    suspend fun getIdeaByIdOnce(ideaId: Long): IdeaNoteWithDetails?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdea(idea: IdeaNoteEntity): Long

    @Update
    suspend fun updateIdea(idea: IdeaNoteEntity)

    @Query("DELETE FROM idea_notes WHERE id = :ideaId")
    suspend fun deleteIdeaById(ideaId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlock(block: IdeaBlockEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<IdeaBlockEntity>)

    @Update
    suspend fun updateBlock(block: IdeaBlockEntity)

    @Query("DELETE FROM idea_blocks WHERE id = :blockId")
    suspend fun deleteBlockById(blockId: Long)

    @Query("DELETE FROM idea_blocks WHERE ideaId = :ideaId")
    suspend fun deleteBlocksForIdea(ideaId: Long)

    /**
     * Make room for a block to be inserted after [afterIndex] (used by
     * background transcription landing a transcript under its audio
     * block). Touches block rows only — the idea's updatedAt is
     * deliberately left alone.
     */
    @Query(
        "UPDATE idea_blocks SET orderIndex = orderIndex + 1 " +
            "WHERE ideaId = :ideaId AND orderIndex > :afterIndex"
    )
    suspend fun shiftBlockOrderAfter(ideaId: Long, afterIndex: Int)

    @Query("SELECT * FROM songs ORDER BY updatedAt DESC")
    fun observeAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY updatedAt DESC")
    suspend fun getAllSongsOnce(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :songId LIMIT 1")
    fun observeSongById(songId: Long): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id = :songId LIMIT 1")
    suspend fun getSongByIdOnce(songId: Long): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity): Long

    @Update
    suspend fun updateSong(song: SongEntity)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSongById(songId: Long)

    @Query("SELECT * FROM song_audio_blocks WHERE songId = :songId ORDER BY orderIndex ASC, createdAt ASC")
    fun observeSongAudioBlocks(songId: Long): Flow<List<SongAudioBlockEntity>>

    @Query("SELECT * FROM song_audio_blocks WHERE songId = :songId ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun getSongAudioBlocksOnce(songId: Long): List<SongAudioBlockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongAudioBlock(block: SongAudioBlockEntity): Long

    @Update
    suspend fun updateSongAudioBlock(block: SongAudioBlockEntity)

    @Query("DELETE FROM song_audio_blocks WHERE id = :blockId")
    suspend fun deleteSongAudioBlockById(blockId: Long)

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM song_audio_blocks WHERE songId = :songId")
    suspend fun maxSongAudioOrderIndex(songId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsage(usage: IdeaUsageEntity): Long

    @Query("SELECT * FROM idea_usages ORDER BY usedAt DESC")
    suspend fun getAllUsagesOnce(): List<IdeaUsageEntity>

    // Usage bookkeeping is NOT an edit of the idea itself, so none of
    // these queries touch updatedAt: that timestamp means "the idea's
    // content last changed" and nothing else.
    @Query("UPDATE idea_notes SET usedCount = usedCount + 1 WHERE id = :ideaId")
    suspend fun incrementIdeaUsedCount(ideaId: Long)

    @Query("UPDATE idea_notes SET usedCount = CASE WHEN usedCount > 0 THEN usedCount - 1 ELSE 0 END WHERE id = :ideaId")
    suspend fun decrementIdeaUsedCount(ideaId: Long)

    @Query("DELETE FROM idea_usages WHERE ideaId = :ideaId")
    suspend fun clearUsagesForIdea(ideaId: Long)

    @Query("UPDATE idea_notes SET usedCount = 0 WHERE id = :ideaId")
    suspend fun resetIdeaUsedCount(ideaId: Long)

    // ---- Vector store: one embedding row per idea, per engine ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbedding(embedding: IdeaEmbeddingEntity)

    @Query("SELECT * FROM idea_embeddings WHERE engineId = :engineId")
    suspend fun getEmbeddingsForEngine(engineId: String): List<IdeaEmbeddingEntity>

    @Query("SELECT * FROM idea_embeddings WHERE ideaId = :ideaId AND engineId = :engineId LIMIT 1")
    suspend fun getEmbedding(ideaId: Long, engineId: String): IdeaEmbeddingEntity?

    @Query("DELETE FROM idea_embeddings WHERE ideaId = :ideaId")
    suspend fun deleteEmbeddingsForIdea(ideaId: Long)

    @Query("SELECT COUNT(*) FROM idea_embeddings WHERE engineId = :engineId")
    suspend fun countEmbeddingsForEngine(engineId: String): Int

    @Transaction
    suspend fun saveIdeaWithBlocks(
        idea: IdeaNoteEntity,
        blocks: List<IdeaBlockEntity>
    ): Long {
        val now = System.currentTimeMillis()
        val finalId = if (idea.id == 0L) {
            // Preserve caller-supplied timestamps on insert: the idea
            // editor already stamps updatedAt itself when saving, and
            // backup import passes each idea's real created/updated
            // times — forcing "now" here made every restored idea show
            // up as "just now" in the vault.
            insertIdea(
                idea.copy(
                    createdAt = if (idea.createdAt == 0L) now else idea.createdAt,
                    updatedAt = if (idea.updatedAt == 0L) now else idea.updatedAt
                )
            )
        } else {
            updateIdea(idea.copy(updatedAt = now))
            idea.id
        }
        deleteBlocksForIdea(finalId)
        val normalized = blocks.mapIndexed { idx, block ->
            block.copy(id = 0L, ideaId = finalId, orderIndex = idx)
        }
        if (normalized.isNotEmpty()) {
            insertBlocks(normalized)
        }
        return finalId
    }
}
