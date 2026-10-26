package com.trackrecord.data

import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.deezer.DeezerAudioResolver
import com.trackrecord.data.deezer.DeezerClient
import com.trackrecord.data.deezer.DeezerImportPlanner
import com.trackrecord.data.deezer.DeezerLinks
import com.trackrecord.data.deezer.DzArtist
import com.trackrecord.data.deezer.DzTrack
import com.trackrecord.data.deezer.ImportPick
import com.trackrecord.data.musicbrainz.HttpFetcher
import com.trackrecord.data.musicbrainz.HttpResponse
import com.trackrecord.data.musicbrainz.RateLimitedQueue
import com.trackrecord.data.musicbrainz.ServiceUnavailableException
import com.trackrecord.data.sources.AudioRef
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeezerTest {
    @Test
    fun `playlist links in every common form`() {
        assertEquals(15888710263, DeezerLinks.playlistId("https://www.deezer.com/de/playlist/15888710263"))
        assertEquals(15888710263, DeezerLinks.playlistId("https://www.deezer.com/playlist/15888710263?utm_source=x"))
        assertEquals(15888710263, DeezerLinks.playlistId("deezer.com/en-gb/playlist/15888710263"))
        assertEquals(15888710263, DeezerLinks.playlistId(" 15888710263 "))
        assertNull(DeezerLinks.playlistId("https://www.deezer.com/de/album/302127"))
        assertNull(DeezerLinks.playlistId("https://link.deezer.com/s/30ZMGDjqBN6tDzWEM9Z7c"))
    }

    @Test
    fun `share links are found inside shared text`() {
        assertEquals(
            "https://link.deezer.com/s/30ZMGDjqBN6tDzWEM9Z7c",
            DeezerLinks.shareLink("Hör dir Testing Track Record auf Deezer an: https://link.deezer.com/s/30ZMGDjqBN6tDzWEM9Z7c."),
        )
        assertNull(DeezerLinks.shareLink("https://example.com/s/abc"))
        assertNull(DeezerLinks.shareLink("https://evil.example/?go=link.deezer.com/s/abc"), "the host must be Deezer's")
        assertNull(DeezerLinks.shareLink("http://link.deezer.com/s/abc"), "https only")
    }

    private fun local(id: Long, artist: String, title: String, isrc: String? = null) = SongEntity(
        id = id, folderId = 1, uri = "content://$id", relativePath = "$id.mp3", lastModified = 0, sizeBytes = 0,
        title = title, artist = artist, album = null, durationMs = 200_000, tagOriginalYear = null, tagYear = 2000,
        mbRecordingId = null, isrc = isrc, hasCover = false,
    )

    private fun track(id: Long, artist: String, title: String, isrc: String? = null, preview: String? = "https://p/$id.mp3") =
        DzTrack(id = id, title = title, isrc = isrc, duration = 200, preview = preview, artist = DzArtist(name = artist))

    @Test
    fun `local files win, known tracks are reused, the rest is new`() {
        val songs = listOf(
            local(1, "Oasis", "Wonderwall (Remastered)", isrc = "GBAAW9500189"),
            local(2, "Queen", "Bohemian Rhapsody"),
            local(3, "Pitbull", "Reach For The Stars").copy(id = 3, folderId = null, uri = AudioRef.deezerUri(4168455662), source = SongSource.DEEZER, deezerId = 4168455662),
        )
        val picks = DeezerImportPlanner.plan(
            listOf(
                track(985745702, "Oasis", "Wonderwall", isrc = "gbaaw9500189"), // same ISRC
                track(7, "Queen", "Bohemian Rhapsody - Remastered 2011"), // same normalized name
                track(4168455662, "Pitbull", "Reach For The Stars"), // already streamed
                track(9, "Mia Julia", "OKF (Ortskontrollfahrt)"),
                track(10, "Someone", "No Preview", preview = null),
                track(11, "Queen", "Bohemian Rhapsody", preview = null), // no preview, but a local file
            ),
            songs,
        )
        assertEquals(ImportPick.Local(1), picks[0])
        assertEquals(ImportPick.Local(2), picks[1])
        assertEquals(ImportPick.Existing(3), picks[2])
        assertEquals(9L, (picks[3] as ImportPick.New).track.id)
        assertEquals(ImportPick.Unavailable, picks[4])
        assertEquals(ImportPick.Local(2), picks[5])
    }

    @Test
    fun `a list row whose ISRC is already a streamed song reuses it`() {
        val streamed = local(3, "Pitbull", "Reach For The Stars", isrc = "USRC11200001")
            .copy(folderId = null, uri = AudioRef.deezerUri(4168455662), source = SongSource.DEEZER, deezerId = 4168455662)
        val standIn = track(-1, "Pitbull", "Reach for the Stars", isrc = "usrc11200001", preview = null)
        assertEquals(listOf<ImportPick>(ImportPick.Existing(3)), DeezerImportPlanner.plan(listOf(standIn), listOf(streamed)))
    }

    private class Fetcher(val respond: (String) -> HttpResponse) : HttpFetcher {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String): HttpResponse = respond(url).also { urls += url }
    }

    private fun client(fetcher: Fetcher) = DeezerClient(RateLimitedQueue(fetcher, { 0L }, { }, minIntervalMs = 0))

    @Test
    fun `playlist tracks are read page by page`() = runBlocking {
        fun page(from: Int, n: Int, total: Int): String {
            val items = (from until from + n).joinToString(",") { """{"id":$it,"title":"T$it","isrc":"I$it","duration":180,"readable":true,"preview":"https://p/$it","artist":{"name":"A"},"album":{"id":1,"title":"Al"}}""" }
            val next = if (from + n < total) ""","next":"https://api.deezer.com/playlist/1/tracks?index=${from + n}"""" else ""
            return """{"data":[$items],"total":$total$next}"""
        }
        val fetcher = Fetcher { url ->
            when {
                "index=0" in url -> HttpResponse(200, page(0, 100, 130))
                "index=100" in url -> HttpResponse(200, page(100, 30, 130))
                else -> HttpResponse(404, "")
            }
        }
        val tracks = client(fetcher).playlistTracks(1)
        assertEquals(130, tracks.size)
        assertEquals("I129", tracks.last().isrc)
        assertEquals(2, fetcher.urls.size)
    }

    @Test
    fun `deezer errors - missing data is null, the quota is a passing failure`() = runBlocking {
        val fetcher = Fetcher { url ->
            if ("track/1" in url) HttpResponse(200, """{"error":{"type":"DataException","message":"no data","code":800}}""")
            else HttpResponse(200, """{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""")
        }
        assertNull(client(fetcher).track(1))
        assertFailsWith<ServiceUnavailableException> { client(fetcher).track(2) }
    }

    @Test
    fun `previews are fetched right before playback and reused only while fresh`() = runBlocking {
        var now = 1_792_794_000_000L
        val exp = 1_792_794_957L // seconds, as in a live preview URL (about 15 minutes)
        val fetcher = Fetcher { url ->
            if ("track/42" in url) HttpResponse(200, """{"id":42,"title":"T","readable":true,"preview":"https://cdnt-preview.dzcdn.net/a.mp3?hdnea=exp=$exp~acl=/x","artist":{"name":"A"}}""")
            else HttpResponse(200, """{"id":7,"title":"Gone","readable":false,"preview":"","artist":{"name":"A"}}""")
        }
        val resolver = DeezerAudioResolver(client(fetcher), now = { now })
        assertEquals("content://x", resolver.playableUri("content://x"))
        val url = resolver.playableUri(AudioRef.deezerUri(42))!!
        assertEquals(exp * 1000, DeezerAudioResolver.expiryOf(url))
        resolver.playableUri(AudioRef.deezerUri(42))
        assertEquals(1, fetcher.urls.size, "fresh URL reused")
        now = exp * 1000 - 60_000 // a minute before expiry: fetch a new one
        resolver.playableUri(AudioRef.deezerUri(42))
        assertEquals(2, fetcher.urls.size)
        assertNull(resolver.playableUri(AudioRef.deezerUri(7)), "no preview: can't be played")
    }

    @Test
    fun `offline previews resolve to null instead of failing`() = runBlocking {
        val resolver = DeezerAudioResolver(client(Fetcher { throw java.io.IOException("offline") }))
        assertNull(resolver.playableUri(AudioRef.deezerUri(42)))
    }

    @Test
    fun `track details carry the release date`() = runBlocking {
        val fetcher = Fetcher {
            HttpResponse(200, """{"id":985745702,"title":"Wonderwall","isrc":"GBAAW9500189","release_date":"1995-10-02","duration":258,"readable":true,"preview":"https://p","artist":{"name":"Oasis"},"album":{"id":1,"title":"Morning Glory","cover_medium":"https://c"}}""")
        }
        val t = client(fetcher).track(985745702)!!
        assertEquals("1995-10-02", t.releaseDate)
        assertEquals("https://c", t.album.coverMedium)
    }
}
