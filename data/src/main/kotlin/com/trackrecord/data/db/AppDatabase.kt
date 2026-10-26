package com.trackrecord.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FolderEntity::class,
        SongEntity::class,
        SongOverrideEntity::class,
        ProfileEntity::class,
        SavedGameEntity::class,
        MbCacheEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlayerRoundEntity::class,
        PlayerGameEntity::class,
    ],
    version = 14,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 11, to = 12, spec = AppDatabase.DropConfirmed::class), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14)],
)
abstract class AppDatabase : RoomDatabase() {
    /** Found years count without confirmation since feedback round 30. */
    @DeleteColumn(tableName = "mb_cache", columnName = "confirmed")
    class DropConfirmed : AutoMigrationSpec

    abstract fun folders(): FolderDao
    abstract fun songs(): SongDao
    abstract fun overrides(): SongOverrideDao
    abstract fun profiles(): ProfileDao
    abstract fun savedGame(): SavedGameDao
    abstract fun mbCache(): MbCacheDao
    abstract fun playlists(): PlaylistDao
    abstract fun playerStats(): PlayerStatsDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "trackrecord.db")
                .addMigrations(MIGRATION_10_11)
                .addCallback(
                    object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(INSERT_EXCLUDED)
                    },
                )
                .build()

        private const val INSERT_EXCLUDED = "INSERT INTO playlists (name, created_at, kind) VALUES ('', 0, 'EXCLUDED')"

        /**
         * Exclusion becomes the "Excluded songs" playlist (feedback round 28): the playlist is created, every
         * excluded song joins it, and the override's `excluded` column goes (rows left without a year edit too).
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN kind TEXT NOT NULL DEFAULT 'USER'")
                db.execSQL(INSERT_EXCLUDED)
                db.execSQL(
                    "INSERT OR IGNORE INTO playlist_songs (playlist_id, song_id) " +
                        "SELECT (SELECT id FROM playlists WHERE kind = 'EXCLUDED'), s.id FROM songs s " +
                        "JOIN song_overrides o ON o.song_uri = s.uri WHERE o.excluded = 1",
                )
                db.execSQL(
                    "CREATE TABLE song_overrides_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `song_uri` TEXT NOT NULL, " +
                        "`match_key` TEXT NOT NULL, `manual_year` INTEGER, `updated_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO song_overrides_new (id, song_uri, match_key, manual_year, updated_at) " +
                        "SELECT id, song_uri, match_key, manual_year, updated_at FROM song_overrides WHERE manual_year IS NOT NULL",
                )
                db.execSQL("DROP TABLE song_overrides")
                db.execSQL("ALTER TABLE song_overrides_new RENAME TO song_overrides")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_song_overrides_song_uri` ON `song_overrides` (`song_uri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_overrides_match_key` ON `song_overrides` (`match_key`)")
            }
        }
    }
}
