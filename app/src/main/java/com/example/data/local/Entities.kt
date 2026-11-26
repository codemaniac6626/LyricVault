package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object BlockType {
    const val TEXT = "TEXT"
    const val AUDIO = "AUDIO"
    const val IMAGE = "IMAGE"
}

@Entity(tableName = "idea_notes")
data class IdeaNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String = "",
    val contextNote: String = "",
    val tags: String = "",
    val usedCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "idea_blocks",
    foreignKeys = [
        ForeignKey(
            entity = IdeaNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["ideaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["ideaId"])]
)
data class IdeaBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ideaId: Long,
    val orderIndex: Int,
    val blockType: String = BlockType.TEXT,
    val textContent: String = "",
    val audioFilePath: String? = null,
    val audioDurationMs: Long = 0L,
    val imageFilePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Per-clip opt-in for cloud transcription (audio blocks in ideas
     * only): false by default; the editor's per-audio toggle sets it.
     * Only clips with this on are queued for transcription on save.
     */
    val transcriptionEnabled: Boolean = false
)

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String = "",
    val vibeDescription: String = "",
    val lyrics: String = "",
    /**
     * Per-song matching instruction. Null or blank = fall back to the global
     * instruction in AI settings; set = overrides it for this song only.
     */
    val customInstruction: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One audio clip attached to a song. Songs keep a single lyrics text, but
 * audio lives in blocks below it — each with its own small label, mirroring
 * how idea notes hold audio blocks.
 *
 * [sourceIdeaId] is set when the clip was brought in from a vault idea. The
 * audio file is a copy owned by the song (idea files are deleted with the
 * idea), so this is only a link for "go to the idea" and may go stale.
 */
@Entity(
    tableName = "song_audio_blocks",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["songId"])]
)
data class SongAudioBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val songId: Long,
    val orderIndex: Int,
    val label: String = "",
    val audioFilePath: String,
    val audioDurationMs: Long = 0L,
    val sourceIdeaId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "idea_usages",
    foreignKeys = [
        ForeignKey(
            entity = IdeaNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["ideaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["ideaId"]), Index(value = ["songId"])]
)
data class IdeaUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ideaId: Long,
    val songId: Long,
    val songTitleSnapshot: String = "",
    val usedAt: Long = System.currentTimeMillis()
)

/**
 * One stored embedding per idea, per engine. Embeddings are engine-specific:
 * vectors from one engine must never be compared with another engine's, so
 * the engine id travels with the vector and the matcher only reads rows for
 * the active engine. Switching engines re-embeds the vault (the old rows are
 * left in place and reused if the user switches back).
 *
 * Note: ByteArray in a data class compares by reference; Room only uses it
 * as a BLOB column, so that is fine here.
 */
@Entity(
    tableName = "idea_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = IdeaNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["ideaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["engineId"])]
)
data class IdeaEmbeddingEntity(
    @PrimaryKey val ideaId: Long,
    val embedding: ByteArray,
    val engineId: String,
    val dimensions: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

data class IdeaNoteWithDetails(
    @Embedded val idea: IdeaNoteEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "ideaId"
    )
    val blocks: List<IdeaBlockEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "ideaId"
    )
    val usages: List<IdeaUsageEntity>
) {
    val orderedBlocks: List<IdeaBlockEntity>
        get() = blocks.sortedBy { it.orderIndex }

    val audioBlockCount: Int
        get() = blocks.count { it.blockType == BlockType.AUDIO }

    val imageBlockCount: Int
        get() = blocks.count { it.blockType == BlockType.IMAGE }

    val totalAudioDurationMs: Long
        get() = blocks.filter { it.blockType == BlockType.AUDIO }.sumOf { it.audioDurationMs }

    val combinedLyricText: String
        get() = orderedBlocks
            .filter { it.blockType == BlockType.TEXT && it.textContent.isNotBlank() }
            .joinToString("\n\n") { it.textContent.trim() }

    val searchableText: String
        get() = buildString {
            if (idea.title.isNotBlank()) appendLine(idea.title)
            if (idea.contextNote.isNotBlank()) appendLine(idea.contextNote)
            if (idea.tags.isNotBlank()) appendLine(idea.tags)
            orderedBlocks.forEach { block ->
                if (block.textContent.isNotBlank()) {
                    appendLine(block.textContent)
                }
            }
        }.trim()

    val displayHeadline: String
        get() {
            if (idea.title.isNotBlank()) return idea.title.trim()
            val firstText = orderedBlocks
                .firstOrNull { it.blockType == BlockType.TEXT && it.textContent.isNotBlank() }
                ?.textContent
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
            if (!firstText.isNullOrBlank()) return firstText
            val firstAudioLabel = orderedBlocks
                .firstOrNull { it.blockType == BlockType.AUDIO && it.textContent.isNotBlank() }
                ?.textContent
                ?.trim()
            if (!firstAudioLabel.isNullOrBlank()) return firstAudioLabel
            val firstImageLabel = orderedBlocks
                .firstOrNull { it.blockType == BlockType.IMAGE && it.textContent.isNotBlank() }
                ?.textContent
                ?.trim()
            if (!firstImageLabel.isNullOrBlank()) return firstImageLabel
            return if (audioBlockCount > 0) {
                "Voice Melody Idea"
            } else if (imageBlockCount > 0) {
                "Image Idea"
            } else {
                "Untitled Spark"
            }
        }

    val tagList: List<String>
        get() = idea.tags
            .split(",", "#")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
}
