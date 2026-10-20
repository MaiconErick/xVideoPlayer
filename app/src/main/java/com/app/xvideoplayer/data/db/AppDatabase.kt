package com.app.xvideoplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.app.xvideoplayer.data.db.dao.*
import com.app.xvideoplayer.data.db.entity.*

@Database(
    entities = [
        VideoEntity::class,
        ServerEntity::class,
        PlaybackHistoryEntity::class,
        ThumbnailEntity::class,
        PrivateMetaEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun serverDao(): ServerDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun thumbnailDao(): ThumbnailDao
    abstract fun privateMetaDao(): PrivateMetaDao

    companion object {
        /** v2 adds display width/height so the library can filter and the player can pre-rotate */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN width INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE videos ADD COLUMN height INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 adds the private section's tags/favorites table */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS private_meta (path TEXT NOT NULL PRIMARY KEY, " +
                        "extraTags TEXT NOT NULL DEFAULT '', favorite INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        /** v4 remembers when the tagger last saved each file (Recent edits) */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE private_meta ADD COLUMN taggedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "xvideoplayer.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
