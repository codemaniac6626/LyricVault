package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        IdeaNoteEntity::class,
        IdeaBlockEntity::class,
        SongEntity::class,
        IdeaUsageEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LyricVaultDatabase : RoomDatabase() {
    abstract fun lyricVaultDao(): LyricVaultDao

    companion object {
        @Volatile
        private var INSTANCE: LyricVaultDatabase? = null

        fun getInstance(context: Context): LyricVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LyricVaultDatabase::class.java,
                    "lyric_vault.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
