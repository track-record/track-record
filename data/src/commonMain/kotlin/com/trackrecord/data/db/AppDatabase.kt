package com.trackrecord.data.db

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.immediateTransaction
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

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
        PendingPlaylistSongEntity::class,
    ],
    version = 17,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 11, to = 12, spec = AppDatabase.DropConfirmed::class), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14), AutoMigration(from = 14, to = 15), AutoMigration(from = 15, to = 16), AutoMigration(from = 16, to = 17)],
)
@ConstructedBy(AppDatabaseConstructor::class)
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
        /** The database file's name; on Android in the app's databases folder, as always. */
        const val FILE_NAME = "trackrecord.db"

        /**
         * The app's database from [builder], the platform's (PLATFORMS.md phase 3): Android keeps its own SQLite and
         * file, the desktop app the bundled SQLite. Both go through the driver, so migrations and the callback use
         * [SQLiteConnection].
         */
        fun build(builder: Builder<AppDatabase>): AppDatabase =
            builder
                .addMigrations(MIGRATION_10_11)
                .addCallback(
                    object : Callback() {
                        override fun onCreate(connection: SQLiteConnection) = connection.execSQL(INSERT_EXCLUDED)
                    },
                )
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()

        private const val INSERT_EXCLUDED = "INSERT INTO playlists (name, created_at, kind) VALUES ('', 0, 'EXCLUDED')"

        /**
         * Exclusion becomes the "Excluded songs" playlist (feedback round 28): the playlist is created, every
         * excluded song joins it, and the override's `excluded` column goes (rows left without a year edit too).
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE playlists ADD COLUMN kind TEXT NOT NULL DEFAULT 'USER'")
                connection.execSQL(INSERT_EXCLUDED)
                connection.execSQL(
                    "INSERT OR IGNORE INTO playlist_songs (playlist_id, song_id) " +
                        "SELECT (SELECT id FROM playlists WHERE kind = 'EXCLUDED'), s.id FROM songs s " +
                        "JOIN song_overrides o ON o.song_uri = s.uri WHERE o.excluded = 1",
                )
                connection.execSQL(
                    "CREATE TABLE song_overrides_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `song_uri` TEXT NOT NULL, " +
                        "`match_key` TEXT NOT NULL, `manual_year` INTEGER, `updated_at` INTEGER NOT NULL)",
                )
                connection.execSQL(
                    "INSERT INTO song_overrides_new (id, song_uri, match_key, manual_year, updated_at) " +
                        "SELECT id, song_uri, match_key, manual_year, updated_at FROM song_overrides WHERE manual_year IS NOT NULL",
                )
                connection.execSQL("DROP TABLE song_overrides")
                connection.execSQL("ALTER TABLE song_overrides_new RENAME TO song_overrides")
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_song_overrides_song_uri` ON `song_overrides` (`song_uri`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_song_overrides_match_key` ON `song_overrides` (`match_key`)")
            }
        }
    }
}

/** Generated by Room for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Runs [block] in one write transaction: Room KMP's form of `withTransaction` (PLATFORMS.md phase 3). */
suspend fun <R> AppDatabase.inTransaction(block: suspend () -> R): R = useWriterConnection { it.immediateTransaction { block() } }
