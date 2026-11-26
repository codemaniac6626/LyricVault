package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        IdeaNoteEntity::class,
        IdeaBlockEntity::class,
        SongEntity::class,
        SongAudioBlockEntity::class,
        IdeaUsageEntity::class,
        IdeaEmbeddingEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class LyricVaultDatabase : RoomDatabase() {
    abstract fun lyricVaultDao(): LyricVaultDao

    companion object {
        @Volatile
        private var INSTANCE: LyricVaultDatabase? = null

        /**
         * v1 -> v2: adds the idea_embeddings table (vector store). No existing
         * table changes, so no data is touched. The CREATE TABLE must match
         * Room's expected schema for IdeaEmbeddingEntity exactly.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `idea_embeddings` (" +
                        "`ideaId` INTEGER NOT NULL, " +
                        "`embedding` BLOB NOT NULL, " +
                        "`engineId` TEXT NOT NULL, " +
                        "`dimensions` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ideaId`), " +
                        "FOREIGN KEY(`ideaId`) REFERENCES `idea_notes`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_idea_embeddings_engineId` " +
                        "ON `idea_embeddings` (`engineId`)"
                )
            }
        }

        /**
         * v2 -> v3: adds the nullable `customInstruction` column to songs
         * (per-song matching instruction; null = use the global one).
         * ALTER TABLE keeps every existing song row intact.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `songs` ADD COLUMN `customInstruction` TEXT")
            }
        }

        /**
         * v3 -> v4: adds the song_audio_blocks table (audio clips attached
         * to a song, each with a label and an optional source-idea link).
         * New table only — no existing data is touched. The CREATE TABLE
         * must match Room's expected schema for SongAudioBlockEntity.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `song_audio_blocks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`songId` INTEGER NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`audioFilePath` TEXT NOT NULL, " +
                        "`audioDurationMs` INTEGER NOT NULL, " +
                        "`sourceIdeaId` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_song_audio_blocks_songId` " +
                        "ON `song_audio_blocks` (`songId`)"
                )
            }
        }

        /**
         * v4 -> v5: adds the nullable `imageFilePath` column to
         * idea_blocks (image blocks in ideas; the block's label rides in
         * textContent, exactly like audio block captions, which also
         * keeps labels inside the existing searchable text). ALTER TABLE
         * keeps every existing row intact.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `idea_blocks` ADD COLUMN `imageFilePath` TEXT")
            }
        }

        /**
         * v5 -> v6: adds the `transcriptionEnabled` column to idea_blocks
         * (per-audio opt-in for cloud transcription, off by default).
         * ALTER TABLE keeps every existing row intact; existing clips
         * stay opted out until the user flips their toggle in the editor.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `idea_blocks` ADD COLUMN `transcriptionEnabled` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): LyricVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LyricVaultDatabase::class.java,
                    "lyric_vault.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
            }
        }
    }
}
