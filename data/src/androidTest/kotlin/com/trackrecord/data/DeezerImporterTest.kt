package com.trackrecord.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.deezer.DeezerClient
import com.trackrecord.data.deezer.DeezerImporter
import com.trackrecord.data.lists.ListRow
import com.trackrecord.data.musicbrainz.HttpFetcher
import com.trackrecord.data.musicbrainz.HttpResponse
import com.trackrecord.data.musicbrainz.RateLimitedQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Importing a song list into a real (in-memory) database with Deezer faked (REVIEW.md T-04): saved chunk
 * by chunk, songs reused by Deezer id, and a song deleted mid-import (the round 19 crash, D-162).
 */
@RunWith(AndroidJUnit4::class)
class DeezerImporterTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()

    /** Called before answering each request, with its URL. */
    private var onRequest: suspend (String) -> Unit = {}

    private fun track(n: Int, details: Boolean) =
        """{"id":${1000 + n},"title":"Song $n","isrc":"ISRC$n","duration":200,"readable":true,""" +
            """"preview":"https://cdnt-preview.dzcdn.net/$n.mp3","artist":{"id":1,"name":"Artist $n"},"album":{"id":1,"title":"Album"}""" +
            (if (details) ""","release_date":"1990-01-01"""" else "") + "}"

    private val fetcher = object : HttpFetcher {
        override suspend fun get(url: String): HttpResponse {
            onRequest(url)
            Regex("""track/isrc:ISRC(\d+)""").find(url)?.let { return HttpResponse(200, track(it.groupValues[1].toInt(), details = false)) }
            Regex("""track/(\d+)""").find(url)?.let { return HttpResponse(200, track(it.groupValues[1].toInt() - 1000, details = true)) }
            return HttpResponse(200, """{"data":[]}""")
        }
    }

    private val importer = DeezerImporter(
        db,
        DeezerClient(RateLimitedQueue(fetcher, { 0L }, { }, minIntervalMs = 0)),
        links = { null },
        inUse = { emptySet() },
        currentYear = { 2026 },
    )

    private fun rows(range: IntRange) = range.map { ListRow("Artist $it", "Song $it", "ISRC$it", 200_000) }

    @After
    fun close() = db.close()

    @Test
    fun a_long_list_is_saved_chunk_by_chunk_and_an_interruption_keeps_what_was_found() = runBlocking {
        onRequest = { url -> if ("isrc:ISRC150" in url) throw IOException("offline") }
        assertFailsWith<IOException> { importer.importList("Long", rows(1..250)) }
        val playlist = db.playlists().memberships().map { it.playlistId }.distinct().single()
        assertEquals(100, db.playlists().songIds(playlist).size, "the first chunk of 100 was saved")

        onRequest = {}
        val result = importer.importList("Long again", rows(1..250))
        assertEquals(250, result.total)
        assertEquals(150, result.added, "the first 100 songs were reused")
        assertEquals(250, db.playlists().songIds(result.playlistId).size)
    }

    @Test
    fun a_second_list_with_the_same_songs_reuses_them() = runBlocking {
        val first = importer.importList("A", rows(1..5))
        assertEquals(5, first.added)
        val second = importer.importList("B", rows(1..5))
        assertEquals(0, second.added)
        assertEquals(5, db.songs().getAll().size)
        assertEquals(db.playlists().songIds(first.playlistId).sorted(), db.playlists().songIds(second.playlistId).sorted())
    }

    @Test
    fun a_song_deleted_during_an_import_is_left_out_instead_of_crashing() = runBlocking {
        importer.importList("A", rows(1..3))
        val gone = db.songs().idByDeezerId(1002)!!
        // While the new row is looked up, song 2 disappears (e.g. a rescan or a deleted playlist).
        onRequest = { url -> if ("isrc:ISRC4" in url) db.songs().delete(listOf(gone)) }
        val result = importer.importList("B", rows(1..4))
        val songs = db.playlists().songIds(result.playlistId)
        assertEquals(3, songs.size)
        assertTrue(gone !in songs)
    }
}
