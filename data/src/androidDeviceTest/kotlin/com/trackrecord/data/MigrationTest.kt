package com.trackrecord.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackrecord.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The database migrations against the exported schemas (REVIEW-2 T-07): a broken one would crash the
 * app at start on a phone with a library in it. Through Android's SQLite driver, as the app opens it since
 * Room KMP (PLATFORMS.md phase 3).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = context.getDatabasePath("migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = file,
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class,
        autoMigrationSpecs = listOf(AppDatabase.DropConfirmed()),
    )

    /** Each test starts without the file (this helper, unlike the old one, opens an existing one). */
    @Before
    fun deleteFile() {
        context.deleteDatabase(file.name)
    }

    private fun SQLiteConnection.song(id: Long, uri: String) =
        execSQL(
            "INSERT INTO songs (id, uri, relative_path, last_modified, size_bytes, title, duration_ms, has_cover, read_failed) " +
                "VALUES ($id, '$uri', 'x.mp3', 0, 0, 'Song $id', 1000, 0, 0)",
        )

    private fun SQLiteConnection.longs(sql: String): List<Long> = prepare(sql).use { st -> buildList { while (st.step()) add(st.getLong(0)) } }

    @Test
    fun excluded_songs_become_the_Excluded_playlist_and_year_edits_stay() {
        helper.createDatabase(10).apply {
            song(1, "content://a")
            song(2, "content://b")
            song(3, "content://c")
            execSQL("INSERT INTO song_overrides (song_uri, match_key, manual_year, excluded, updated_at) VALUES ('content://a', 'a', NULL, 1, 0)")
            execSQL("INSERT INTO song_overrides (song_uri, match_key, manual_year, excluded, updated_at) VALUES ('content://b', 'b', 1985, 0, 0)")
            execSQL("INSERT INTO song_overrides (song_uri, match_key, manual_year, excluded, updated_at) VALUES ('content://c', 'c', 1990, 1, 0)")
            execSQL("INSERT INTO playlists (name, created_at) VALUES ('Mine', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(11, listOf(AppDatabase.MIGRATION_10_11))
        val excluded = db.longs("SELECT id FROM playlists WHERE kind = 'EXCLUDED'").single()
        assertEquals(listOf(1L, 3L), db.longs("SELECT song_id FROM playlist_songs WHERE playlist_id = $excluded ORDER BY song_id"))
        assertEquals(listOf(1985L, 1990L), db.longs("SELECT manual_year FROM song_overrides ORDER BY manual_year"), "only rows with a year edit stay")
        assertEquals(1, db.longs("SELECT COUNT(*) FROM playlists WHERE kind = 'USER'").single().toInt())
        db.close()
    }

    @Test
    fun every_version_since_9_reaches_the_current_one_with_its_rows() {
        helper.createDatabase(9).apply {
            song(1, "content://a")
            execSQL("INSERT INTO saved_game (id, json, updated_at) VALUES (1, '{\"old\":true}', 5)")
            execSQL("INSERT INTO mb_cache (song_key, year, confirmed, checked_at) VALUES ('k', 1977, 1, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(17, listOf(AppDatabase.MIGRATION_10_11))
        assertEquals(listOf(1L), db.longs("SELECT id FROM songs"))
        assertEquals(listOf(1977L), db.longs("SELECT year FROM mb_cache"))
        db.prepare("SELECT json, songs_json FROM saved_game").use { st ->
            st.step()
            assertEquals("{\"old\":true}", st.getText(0))
            assertEquals(true, st.isNull(1), "a game saved before D-269 has its songs inside json")
        }
        db.close()
    }

    @Test
    fun the_app_opens_a_migrated_database() {
        helper.createDatabase(12).apply {
            execSQL("INSERT INTO saved_game (id, json, updated_at) VALUES (1, '{}', 5)")
            close()
        }
        // Built as the app builds it (AppDatabase.create), on this file.
        val db = AppDatabase.build(Room.databaseBuilder<AppDatabase>(context, file.absolutePath).setDriver(AndroidSQLiteDriver()))
        val saved = runBlocking { db.savedGame().get() }
        assertEquals("{}", saved?.json)
        assertNull(saved?.songsJson)
        db.close()
    }
}
