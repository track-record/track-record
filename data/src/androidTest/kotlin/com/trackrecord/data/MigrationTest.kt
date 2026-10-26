package com.trackrecord.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackrecord.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The database migrations against the exported schemas (REVIEW-2 T-07): a broken one would crash the
 * app at start on a phone with a library in it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(AppDatabase.DropConfirmed()),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun SupportSQLiteDatabase.song(id: Long, uri: String) =
        execSQL(
            "INSERT INTO songs (id, uri, relative_path, last_modified, size_bytes, title, duration_ms, has_cover, read_failed) " +
                "VALUES ($id, '$uri', 'x.mp3', 0, 0, 'Song $id', 1000, 0, 0)",
        )

    private fun SupportSQLiteDatabase.longs(sql: String): List<Long> = query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    @Test
    fun excluded_songs_become_the_Excluded_playlist_and_year_edits_stay() {
        helper.createDatabase(name, 10).apply {
            song(1, "content://a")
            song(2, "content://b")
            song(3, "content://c")
            execSQL("INSERT INTO song_overrides (song_uri, match_key, manual_year, excluded, updated_at) VALUES ('content://a', 'a', NULL, 1, 0)")
            execSQL("INSERT INTO song_overrides (song_uri, match_key, manual_year, excluded, updated_at) VALUES ('content://b', 'b', 1985, 0, 0)")
            execSQL("INSERT INTO song_overrides (song_uri, match_key, manual_year, excluded, updated_at) VALUES ('content://c', 'c', 1990, 1, 0)")
            execSQL("INSERT INTO playlists (name, created_at) VALUES ('Mine', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 11, true, AppDatabase.MIGRATION_10_11)
        val excluded = db.longs("SELECT id FROM playlists WHERE kind = 'EXCLUDED'").single()
        assertEquals(listOf(1L, 3L), db.longs("SELECT song_id FROM playlist_songs WHERE playlist_id = $excluded ORDER BY song_id"))
        assertEquals(listOf(1985L, 1990L), db.longs("SELECT manual_year FROM song_overrides ORDER BY manual_year"), "only rows with a year edit stay")
        assertEquals(1, db.longs("SELECT COUNT(*) FROM playlists WHERE kind = 'USER'").single().toInt())
        db.close()
    }

    @Test
    fun every_version_since_9_reaches_the_current_one_with_its_rows() {
        helper.createDatabase(name, 9).apply {
            song(1, "content://a")
            execSQL("INSERT INTO saved_game (id, json, updated_at) VALUES (1, '{\"old\":true}', 5)")
            execSQL("INSERT INTO mb_cache (song_key, year, confirmed, checked_at) VALUES ('k', 1977, 1, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 15, true, AppDatabase.MIGRATION_10_11)
        assertEquals(listOf(1L), db.longs("SELECT id FROM songs"))
        assertEquals(listOf(1977L), db.longs("SELECT year FROM mb_cache"))
        db.query("SELECT json, songs_json FROM saved_game").use { c ->
            c.moveToFirst()
            assertEquals("{\"old\":true}", c.getString(0))
            assertEquals(true, c.isNull(1), "a game saved before D-269 has its songs inside json")
        }
        db.close()
    }

    @Test
    fun the_app_opens_a_migrated_database() {
        helper.createDatabase(name, 12).apply {
            execSQL("INSERT INTO saved_game (id, json, updated_at) VALUES (1, '{}', 5)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_10_11).build()
        val saved = runBlocking { db.savedGame().get() }
        assertEquals("{}", saved?.json)
        assertNull(saved?.songsJson)
        db.close()
    }
}
