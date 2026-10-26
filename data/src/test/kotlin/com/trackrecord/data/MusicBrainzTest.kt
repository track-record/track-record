package com.trackrecord.data

import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.musicbrainz.AcoustIdClient
import com.trackrecord.data.musicbrainz.AcoustIdKeyState
import com.trackrecord.data.musicbrainz.AcoustIdKeyStatus
import com.trackrecord.data.musicbrainz.FingerprintMatcher
import com.trackrecord.data.musicbrainz.Fingerprinter
import com.trackrecord.data.musicbrainz.HttpFetcher
import com.trackrecord.data.musicbrainz.HttpResponse
import com.trackrecord.data.musicbrainz.LookupResult
import com.trackrecord.data.musicbrainz.LookupService
import com.trackrecord.data.musicbrainz.MbCache
import com.trackrecord.data.musicbrainz.MusicBrainzClient
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import com.trackrecord.data.musicbrainz.RateLimitedQueue
import com.trackrecord.data.musicbrainz.ServiceUnavailableException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake clock: sleeping advances virtual time instantly. */
private class FakeClock {
    var now = 1_000_000L
    val sleeps = mutableListOf<Long>()
    fun sleep(ms: Long) { sleeps += ms; now += ms }
}

private class FakeFetcher(
    private val clock: FakeClock,
    private val respondPost: (Map<String, String>) -> HttpResponse = { HttpResponse(404, "") },
    private val respond: (String) -> HttpResponse,
) : HttpFetcher {
    val calls = mutableListOf<Pair<Long, String>>()
    val posts = mutableListOf<Map<String, String>>()
    override suspend fun get(url: String): HttpResponse {
        calls += clock.now to url
        clock.now += 50 // the request itself takes a little time
        return respond(url)
    }

    override suspend fun post(url: String, form: Map<String, String>): HttpResponse {
        posts += form
        clock.now += 50
        return respondPost(form)
    }
}

private fun fixture(name: String) = MusicBrainzTest::class.java.getResource("/mb/$name.json")!!.readText()

private class MemoryCache : MbCache {
    val map = mutableMapOf<String, MbCacheEntity>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(entry: MbCacheEntity) { map[entry.songKey] = entry }
}

class MusicBrainzTest {
    private val queen = SongEntity(
        id = 1, folderId = 1, uri = "u", relativePath = "q.mp3", lastModified = 0, sizeBytes = 0,
        title = "Bohemian Rhapsody (Remastered 2011)", artist = "Queen", album = null, durationMs = 354_000,
        tagOriginalYear = null, tagYear = 2011, mbRecordingId = "b1a9c0e9-d987-4042-ae91-78d6a3267d69",
        isrc = "GBUM71029604", hasCover = false,
    )

    private fun fixtureResponses(url: String): HttpResponse {
        val q = java.net.URLDecoder.decode(url, "UTF-8")
        return when {
            // Narrowed searches (earlier releases only) find nothing older in this fixture.
            "firstreleasedate" in q -> HttpResponse(200, """{"count":0,"recordings":[]}""")
            // Batched id searches include each recording's releases (the saved lookup fixture has none).
            "rid:(" in q -> HttpResponse(
                200,
                """{"count":1,"recordings":[${fixture("recording").replaceFirst("{", """{"releases":[{"id":"rel-1975","date":"1975-11-21","release-group":{"id":"rg-1975"}}],""")}]}""",
            )
            "isrc:(" in q -> HttpResponse(200, fixture("isrc"))
            "recording?query=" in q -> HttpResponse(200, fixture("search"))
            else -> HttpResponse(404, "")
        }
    }

    @Test
    fun `never two requests within 1 s, also when requested concurrently`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { HttpResponse(200, "{}") }
        val queue = RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })
        coroutineScope { (1..8).map { async { queue.get("https://x/$it") } }.awaitAll() }
        assertEquals(8, fetcher.calls.size)
        val gaps = fetcher.calls.zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.all { it >= 1_000 }, "gaps $gaps")
        assertTrue(gaps.all { it >= 1_000 }, "spacing must be at least 1 s: $gaps")
    }

    @Test
    fun `a 503 fails the call and the spacing doubles per failure, then stays until it proves too slow`() = runBlocking {
        val clock = FakeClock()
        val codes = ArrayDeque(listOf(503, 503, 429, 200, 200))
        val fetcher = FakeFetcher(clock) { HttpResponse(codes.removeFirst(), "ok") }
        val queue = RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })
        repeat(3) { assertFailsWith<ServiceUnavailableException> { queue.get("https://x") } }
        assertEquals("ok", queue.get("https://x").body)
        queue.get("https://x")
        val gaps = fetcher.calls.zipWithNext { a, b -> b.first - a.first }
        // Another phone on the same network may share the limit: no jump back to 1 s (feedback round 28).
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 8_000L), gaps, "one attempt per call; backoff 2/4/8 s, kept after a success")
    }

    @Test
    fun `the spacing halves after every 2 successes and grows again on the next 503`() = runBlocking {
        val clock = FakeClock()
        var code = 503
        val fetcher = FakeFetcher(clock) { HttpResponse(code, "ok") }
        val queue = RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })
        repeat(3) { assertFailsWith<ServiceUnavailableException> { queue.get("https://x") } }
        assertEquals(8_000L, queue.currentIntervalMs)
        code = 200
        queue.get("https://x")
        assertEquals(8_000L, queue.currentIntervalMs, "one success isn't enough")
        queue.get("https://x")
        assertEquals(4_000L, queue.currentIntervalMs, "half after 2 successes in a row")
        repeat(4) { queue.get("https://x") }
        assertEquals(1_000L, queue.currentIntervalMs, "back to normal within a few requests (feedback round 47)")
        repeat(20) { queue.get("https://x") }
        assertEquals(1_000L, queue.currentIntervalMs, "never below the normal spacing")
        code = 503
        assertFailsWith<ServiceUnavailableException> { queue.get("https://x") }
        assertEquals(2_000L, queue.currentIntervalMs)
    }

    @Test
    fun `however often requests fail, they are at most 10 s apart`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { HttpResponse(503, "busy") }
        val queue = RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })
        repeat(8) { assertFailsWith<ServiceUnavailableException> { queue.get("https://x") } }
        assertEquals(10_000L, queue.currentIntervalMs)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 10_000L, 10_000L), fetcher.calls.zipWithNext { a, b -> b.first - a.first }.take(5))
    }

    @Test
    fun `a new run after a quiet minute starts at the fastest pace again`() = runBlocking {
        val clock = FakeClock()
        var code = 503
        val fetcher = FakeFetcher(clock) { HttpResponse(code, "ok") }
        val queue = RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })
        repeat(3) { assertFailsWith<ServiceUnavailableException> { queue.get("https://x") } }
        assertEquals(8_000L, queue.currentIntervalMs)
        clock.sleep(61_000)
        code = 200
        queue.get("https://x")
        assertEquals(1_000L, queue.currentIntervalMs, "only slowed down again when something fails (feedback round 29)")
    }

    @Test
    fun `a timeout counts as a passing failure, other errors are thrown as they are`() = runBlocking {
        val clock = FakeClock()
        var timeout = true
        val fetcher = FakeFetcher(clock) { if (timeout) throw java.io.InterruptedIOException("timeout") else throw IOException("offline") }
        val queue = RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })
        assertFailsWith<ServiceUnavailableException> { queue.get("https://x") }
        timeout = false
        assertFailsWith<IOException> { queue.get("https://x") }
    }

    @Test
    fun `lookup combines recording, isrc and search and takes the earliest accepted date`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock, respond = ::fixtureResponses)
        val cache = MemoryCache()
        val lookup = MusicBrainzLookup(MusicBrainzClient(RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })), cache, { true }, { 2026 }, { clock.now })
        assertEquals(LookupResult.Match(1975), lookup.lookup(queen))
        val urls = fetcher.calls.map { decoded(it.second) }
        assertEquals(1, urls.count { "rid:(" in it }, "one batched recording-id search")
        assertEquals(1, urls.count { "isrc:(" in it }, "one batched ISRC search")
        assertTrue(urls.any { "rid:(" !in it && "isrc:(" !in it }, "and the name search, though the id agrees with the tags (feedback round 47)")
        assertTrue(cache.map.values.single().nameSearched)
        assertEquals(1975, cache.map.values.single().year)
    }

    @Test
    fun `the earliest release of the accepted recordings is stored for its cover`() = runBlocking {
        val clock = FakeClock()
        val cache = MemoryCache()
        val lookup = MusicBrainzLookup(MusicBrainzClient(RateLimitedQueue(FakeFetcher(clock, respond = ::fixtureResponses), { clock.now }, { clock.sleep(it) })), cache, { true }, { 2026 }, { clock.now })
        lookup.lookup(queen)
        val entry = cache.map.values.single()
        assertTrue(entry.releaseId != null, "release id stored")
        assertTrue(entry.releaseGroupId != null, "release group id stored")
        assertTrue(lookup.checked(queen))
    }

    @Test
    fun `first release prefers the earliest dated release`() {
        val r = { id: String, date: String? -> com.trackrecord.data.musicbrainz.MbRelease(id, date) }
        val recs = listOf(
            com.trackrecord.data.musicbrainz.MbRecording(releases = listOf(r("b", "1980-01-01"), r("c", null))),
            com.trackrecord.data.musicbrainz.MbRecording(releases = listOf(r("a", "1975-11"))),
        )
        assertEquals("a", MusicBrainzLookup.firstRelease(recs)?.id)
        assertEquals(null, MusicBrainzLookup.firstRelease(emptyList()))
    }

    @Test
    fun `an incomplete search page is narrowed to earlier releases`() = runBlocking {
        val clock = FakeClock()
        fun rec(date: String) = """{"id":"r$date","title":"Wonderwall","first-release-date":"$date","artist-credit":[{"name":"Oasis"}]}"""
        val fetcher = FakeFetcher(clock) { url ->
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            when {
                "firstreleasedate:[1000 TO 1995-12-31]" in decoded -> HttpResponse(200, """{"count":2,"recordings":[${rec("1995-10-02")},${rec("1995")}]}""")
                "recording?query=" in decoded -> HttpResponse(200, """{"count":247,"recordings":[${rec("1996")},${rec("2001")}]}""")
                else -> HttpResponse(404, "")
            }
        }
        val lookup = MusicBrainzLookup(MusicBrainzClient(RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })), MemoryCache(), { true }, { 2026 }, { clock.now })
        val song = queen.copy(title = "Wonderwall", artist = "Oasis", mbRecordingId = null, isrc = null)
        assertEquals(LookupResult.Match(1995), lookup.lookup(song))
        assertEquals(2, fetcher.calls.size, "one search plus one narrowing; the second page is complete")
    }

    private fun recJson(id: String, title: String, artist: String, date: String) =
        """{"id":"$id","title":"$title","first-release-date":"$date","artist-credit":[{"name":"$artist"}]}"""

    private fun song(id: Long, title: String, artist: String, mbid: String?, tagYear: Int?, isrc: String? = null) = queen.copy(
        id = id, uri = "u$id", title = title, artist = artist, mbRecordingId = mbid, isrc = isrc, tagYear = tagYear, tagOriginalYear = null,
    )

    private fun lookupWith(clock: FakeClock, fetcher: FakeFetcher, cache: MemoryCache = MemoryCache()) =
        MusicBrainzLookup(MusicBrainzClient(RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })), cache, { true }, { 2026 }, { clock.now })

    private fun decoded(url: String) = java.net.URLDecoder.decode(url, "UTF-8")

    @Test
    fun `recording ids of many songs go into one request, and every song is searched by name too`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { url ->
            if ("rid:(" in decoded(url)) HttpResponse(200, """{"count":3,"recordings":[${recJson("a", "One", "X", "1971")},${recJson("b", "Two", "Y", "1982")},${recJson("c", "Three", "Z", "1993")}]}""")
            else HttpResponse(200, """{"count":0,"recordings":[]}""")
        }
        val songs = listOf(song(1, "One", "X", "a", 2001), song(2, "Two", "Y", "b", 1982), song(3, "Three", "Z", "c", 2010))
        val seen = mutableListOf<Long>()
        val results = lookupWith(clock, fetcher).lookupAll(songs) { s, _ -> seen += s.id }
        assertEquals(mapOf(1L to LookupResult.Match(1971), 2L to LookupResult.Match(1982), 3L to LookupResult.Match(1993)), results)
        assertEquals(listOf(1L, 2L, 3L), seen)
        assertEquals(1, fetcher.calls.count { "rid:(" in decoded(it.second) }, "three songs, one id request")
        assertTrue(songs.all { song -> fetcher.calls.any { isSearchFor(song.title, it.second) } }, "ids name one recording, maybe a remaster")
    }

    @Test
    fun `more than 40 ids are split into several requests`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { HttpResponse(200, """{"count":0,"recordings":[]}""") }
        lookupWith(clock, fetcher).lookupAll((1L..41L).map { song(it, "T$it", "A", "id$it", null) })
        assertEquals(2, fetcher.calls.count { "rid:(" in decoded(it.second) })
    }

    @Test
    fun `a recording id later than the tag year still searches, starting at the tag year`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { url ->
            val q = decoded(url)
            when {
                "rid:(" in q -> HttpResponse(200, """{"count":1,"recordings":[${recJson("r", "Song", "Band", "2011")}]}""")
                "firstreleasedate:[1000 TO 1975-12-31]" in q -> HttpResponse(200, """{"count":1,"recordings":[${recJson("o", "Song", "Band", "1975")}]}""")
                else -> HttpResponse(200, """{"count":0,"recordings":[]}""")
            }
        }
        assertEquals(LookupResult.Match(1975), lookupWith(clock, fetcher).lookup(song(1, "Song", "Band", "r", 1975)))
        assertEquals(2, fetcher.calls.size, "id batch, then one search narrowed to the tag year")
    }

    @Test
    fun `an untagged song with a tag year needs one narrowed search`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { url ->
            if ("firstreleasedate:[1000 TO 1984-12-31]" in decoded(url)) HttpResponse(200, """{"count":1,"recordings":[${recJson("o", "Song", "Band", "1984-05-01")}]}""")
            else HttpResponse(200, """{"count":300,"recordings":[]}""")
        }
        assertEquals(LookupResult.Match(1984), lookupWith(clock, fetcher).lookup(song(1, "Song", "Band", null, 1984)))
        assertEquals(1, fetcher.calls.size)
    }

    @Test
    fun `a failing request skips the rest of the batch and caches nothing for them`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { throw IOException("offline") }
        val cache = MemoryCache()
        val results = lookupWith(clock, fetcher, cache).lookupAll(listOf(song(1, "A", "X", null, 1990), song(2, "B", "Y", null, 1991)))
        assertEquals(listOf(LookupResult.Offline, LookupResult.Offline), results.values.toList())
        assertTrue(cache.map.isEmpty())
        assertEquals(1, fetcher.calls.size, "after the first failed search no more requests")
    }

    @Test
    fun `a cache hit makes no request`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock, respond = ::fixtureResponses)
        val lookup = MusicBrainzLookup(MusicBrainzClient(RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })), MemoryCache(), { true }, { 2026 }, { clock.now })
        lookup.lookup(queen)
        val before = fetcher.calls.size
        assertEquals(LookupResult.Match(1975), lookup.lookup(queen))
        assertEquals(before, fetcher.calls.size)
    }

    @Test
    fun `no match is cached and retried after 30 days`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { HttpResponse(200, """{"recordings":[]}""") }
        val cache = MemoryCache()
        val lookup = MusicBrainzLookup(MusicBrainzClient(RateLimitedQueue(fetcher, { clock.now }, { clock.sleep(it) })), cache, { true }, { 2026 }, { clock.now })
        val song = queen.copy(mbRecordingId = null, isrc = null)
        assertEquals(LookupResult.NoMatch, lookup.lookup(song))
        assertNull(cache.map.values.single().year)
        val calls = fetcher.calls.size
        clock.now += 29L * 24 * 3600 * 1000
        assertEquals(LookupResult.NoMatch, lookup.lookup(song))
        assertEquals(calls, fetcher.calls.size)
        clock.now += 2L * 24 * 3600 * 1000
        lookup.lookup(song)
        // a search up to the tag year, then an open search
        assertEquals(calls + 2, fetcher.calls.size)
    }

    @Test
    fun `offline or switched-off lookups say which, and cache nothing`() = runBlocking {
        val clock = FakeClock()
        val offline = FakeFetcher(clock) { throw IOException("offline") }
        val cache = MemoryCache()
        val q = RateLimitedQueue(offline, { clock.now }, { clock.sleep(it) })
        assertEquals(LookupResult.Offline, MusicBrainzLookup(MusicBrainzClient(q), cache, { true }, { 2026 }, { clock.now }).lookup(queen))
        assertEquals(LookupResult.Skipped, MusicBrainzLookup(MusicBrainzClient(q), cache, { false }, { 2026 }, { clock.now }).lookup(queen))
        assertTrue(cache.map.isEmpty())
    }

    @Test
    fun `cache key survives remaster suffixes`() {
        assertEquals(
            MusicBrainzLookup.keyOf("Queen", "Bohemian Rhapsody - 2011 Remaster", null, null),
            MusicBrainzLookup.keyOf("QUEEN", "Bohemian Rhapsody (Remastered 2011)", null, null),
        )
    }

    // --- Requeueing (D-074) ---

    private fun searchFor(title: String, year: String) =
        HttpResponse(200, """{"count":1,"recordings":[${recJson("r$title", title, "Band", year)}]}""")

    private fun isSearchFor(title: String, url: String) = "recording:\"$title\"" in decoded(url)

    /** Answers single and combined name searches: one recording (by "Band") for every title asked for. */
    private fun searches(vararg titleYears: Pair<String, String>): (String) -> HttpResponse = { url ->
        val hits = titleYears.filter { (title, _) -> isSearchFor(title, url) }
        HttpResponse(200, """{"count":${hits.size},"recordings":[${hits.joinToString(",") { (t, y) -> recJson("r$t", t, "Band", y) }}]}""")
    }

    private fun titled(range: LongRange) = range.map { song(it, "T$it", "Band", null, null) }

    @Test
    fun `a rate-limited search goes to the end of the queue`() = runBlocking {
        val clock = FakeClock()
        var failedOnce = false
        val answer = searches("T1" to "1970", "T9" to "1980")
        val fetcher = FakeFetcher(clock) { url ->
            // The first batch (songs 1-8) is rate limited once; song 9's batch goes through meanwhile.
            if (isSearchFor("T1", url) && !failedOnce) { failedOnce = true; HttpResponse(503, "") } else answer(url)
        }
        val order = mutableListOf<Long>()
        val results = lookupWith(clock, fetcher).lookupAll(titled(1L..9L)) { s, _ -> order += s.id }
        assertEquals(9L, order.first(), "song 9 is done while songs 1-8 wait at the end")
        assertEquals(LookupResult.Match(1970), results[1L])
        assertEquals(LookupResult.Match(1980), results[9L])
    }

    @Test
    fun `a song refused after 3 retries finds MusicBrainz unavailable, and nothing is cached for it`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { HttpResponse(503, "") }
        val cache = MemoryCache()
        val results = lookupWith(clock, fetcher, cache).lookupAll(titled(1L..1L))
        assertEquals(LookupResult.Unavailable(LookupService.MUSICBRAINZ), results[1L])
        assertEquals(1 + MusicBrainzLookup.MAX_RETRIES, fetcher.calls.size, "fewer than five failures in a row: the retry limit ends it")
        assertTrue(cache.map.isEmpty())
    }

    @Test
    fun `failed requests in a row end the run, MusicBrainz counting as unavailable`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock) { HttpResponse(503, "") }
        val cache = MemoryCache()
        // Five search batches (8 songs each) fail in turn until the limit of failures in a row.
        val results = lookupWith(clock, fetcher, cache).lookupAll(titled(1L..40L))
        assertTrue(results.values.all { it == LookupResult.Unavailable(LookupService.MUSICBRAINZ) }, "the warning to try later (feedback round 47)")
        assertEquals(40, results.size)
        assertEquals(MusicBrainzLookup.MAX_FAILURES_IN_ROW, fetcher.calls.size)
        assertTrue(cache.map.isEmpty())
    }

    // --- Batched name searches (D-078) ---

    @Test
    fun `name searches of several songs go into one request`() = runBlocking {
        val clock = FakeClock()
        val fetcher = FakeFetcher(clock, respond = searches("T1" to "1971", "T2" to "1982", "T3" to "1993"))
        val results = lookupWith(clock, fetcher).lookupAll(titled(1L..3L))
        assertEquals(mapOf(1L to LookupResult.Match(1971), 2L to LookupResult.Match(1982), 3L to LookupResult.Match(1993)), results)
        assertEquals(1, fetcher.calls.size)
        assertTrue(" OR " in decoded(fetcher.calls.single().second))
    }

    @Test
    fun `a batch that overflows the page is split, and a lone big song is narrowed`() = runBlocking {
        val clock = FakeClock()
        val small = searches("T1" to "1971", "T2" to "1982", "T3" to "1993")
        val fetcher = FakeFetcher(clock) { url ->
            val q = decoded(url)
            when {
                // "Big" has 247 recordings: any search including it doesn't fit on a page.
                "firstreleasedate:[1000 TO 1995-12-31]" in q -> HttpResponse(200, """{"count":1,"recordings":[${recJson("o", "Big", "Band", "1995")}]}""")
                isSearchFor("Big", url) -> HttpResponse(200, """{"count":247,"recordings":[${recJson("l", "Big", "Band", "1996")}]}""")
                else -> small(url)
            }
        }
        val songs = titled(1L..3L) + song(4, "Big", "Band", null, null)
        val results = lookupWith(clock, fetcher).lookupAll(songs)
        assertEquals(LookupResult.Match(1971), results[1L])
        assertEquals(LookupResult.Match(1993), results[3L])
        assertEquals(LookupResult.Match(1995), results[4L], "narrowed below the first page's best year (D-069)")
        // [1,2,3,Big] overflows -> [1,2] fits; [3,Big] overflows -> [3] fits; [Big] alone + one narrowing.
        assertEquals(6, fetcher.calls.size)
    }

    @Test
    fun `songs with a tag year get their open search batched too`() = runBlocking {
        val clock = FakeClock()
        val answer = searches("T1" to "1971", "T2" to "1982")
        val fetcher = FakeFetcher(clock) { url -> if ("firstreleasedate" in decoded(url)) HttpResponse(200, """{"count":0,"recordings":[]}""") else answer(url) }
        val songs = listOf(song(1, "T1", "Band", null, 1960), song(2, "T2", "Band", null, 1960))
        val results = lookupWith(clock, fetcher).lookupAll(songs)
        assertEquals(LookupResult.Match(1971), results[1L])
        assertEquals(LookupResult.Match(1982), results[2L])
        assertEquals(2, fetcher.calls.size, "one narrowed batch, one open batch")
    }

    @Test
    fun `a remaster tagged with its own recording id still gets the original release from the name search`() = runBlocking {
        val clock = FakeClock()
        val names = searches("T1" to "1971")
        val fetcher = FakeFetcher(clock) { url ->
            if ("rid:(" in decoded(url)) HttpResponse(200, """{"count":1,"recordings":[${recJson("a", "T1", "Band", "2011")}]}""") else names(url)
        }
        // Tagged 2011 like the remaster's recording: before feedback round 47 that settled it without a search.
        val results = lookupWith(clock, fetcher).lookupAll(listOf(song(1, "T1", "Band", "a", 2011)))
        assertEquals(LookupResult.Match(1971), results[1L])
    }

    // --- Fingerprint matching (D-075) ---

    /** AcoustID results (a JSON array) for one fingerprint. */
    private fun acoustIdFor(vararg recordings: Pair<String, String>): String {
        if (recordings.isEmpty()) return "[]"
        val recs = recordings.joinToString(",") { (id, title) -> """{"id":"$id","title":"$title","artists":[{"name":"Band"}]}""" }
        return """[{"id":"t","score":0.97,"recordings":[$recs]}]"""
    }

    /** A batched AcoustID answer (`batch=1`): [results] for each numbered fingerprint in the request. */
    private fun batched(results: (fingerprint: String) -> String): (Map<String, String>) -> HttpResponse = { form ->
        assertEquals("1", form["batch"])
        val n = form.keys.count { it.startsWith("fingerprint.") }
        val fps = (0 until n).joinToString(",") { """{"index":$it,"results":${results(form.getValue("fingerprint.$it"))}}""" }
        HttpResponse(200, """{"status":"ok","fingerprints":[$fps]}""")
    }

    private fun fingerprintLookup(
        clock: FakeClock,
        mb: FakeFetcher,
        acoustId: FakeFetcher,
        cache: MemoryCache = MemoryCache(),
        key: String = "key",
        fingerprinter: Fingerprinter = Fingerprinter { "fp${it.id}" },
    ) = MusicBrainzLookup(
        MusicBrainzClient(RateLimitedQueue(mb, { clock.now }, { clock.sleep(it) })), cache, { true }, { 2026 }, { clock.now },
        fingerprints = FingerprintMatcher(
            AcoustIdClient(RateLimitedQueue(acoustId, { clock.now }, { clock.sleep(it) }, minIntervalMs = 340), { key }),
            fingerprinter,
            enabled = { key.isNotBlank() },
        ),
    )

    private fun recordingsIn(q: String, vararg recs: Triple<String, String, String>): HttpResponse {
        val found = recs.filter { it.first in q }.joinToString(",") { recJson(it.first, it.second, "Band", it.third) }
        return HttpResponse(200, """{"count":${recs.count { it.first in q }},"recordings":[$found]}""")
    }

    @Test
    fun `fingerprint ids of many songs go into one request, their name searches into one more`() = runBlocking {
        val clock = FakeClock()
        val acoustId = FakeFetcher(clock, respondPost = batched { fp ->
            when (fp) {
                "fp1" -> acoustIdFor("a1" to "One", "x1" to "Something Else")
                "fp2" -> acoustIdFor("b1" to "Two (Remastered 2011)")
                else -> acoustIdFor("c1" to "Three")
            }
        }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { url ->
            val q = decoded(url)
            assertTrue("x1" !in q, "recordings with another title are not asked for")
            if ("rid:(" in q) recordingsIn(q, Triple("a1", "One", "1971"), Triple("b1", "Two", "1982"), Triple("c1", "Three", "1993"))
            else HttpResponse(200, """{"count":0,"recordings":[]}""")
        }
        val cache = MemoryCache()
        val songs = listOf(song(1, "One", "Band", null, null), song(2, "Two", "Band", null, null), song(3, "Three", "Band", null, null))
        val results = fingerprintLookup(clock, mb, acoustId, cache).lookupAll(songs)
        assertEquals(mapOf(1L to LookupResult.Match(1971), 2L to LookupResult.Match(1982), 3L to LookupResult.Match(1993)), results)
        assertEquals(2, mb.calls.size, "three untagged songs: one request for the ids, one combined name search")
        assertEquals(1, acoustId.posts.size, "three fingerprints, one AcoustID request (D-077)")
        assertEquals("354", acoustId.posts.first()["duration.0"])
        assertTrue(cache.map.values.all { it.lookupVersion == MusicBrainzLookup.LOOKUP_VERSION_FINGERPRINT })
    }

    @Test
    fun `a fingerprint year later than the tag year still searches up to the tag year`() = runBlocking {
        val clock = FakeClock()
        val acoustId = FakeFetcher(clock, respondPost = batched { acoustIdFor("r" to "Song") }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { url ->
            val q = decoded(url)
            when {
                "rid:(" in q -> recordingsIn(q, Triple("r", "Song", "2011"))
                "firstreleasedate:[1000 TO 1975-12-31]" in q -> HttpResponse(200, """{"count":1,"recordings":[${recJson("o", "Song", "Band", "1975")}]}""")
                else -> HttpResponse(200, """{"count":0,"recordings":[]}""")
            }
        }
        assertEquals(LookupResult.Match(1975), fingerprintLookup(clock, mb, acoustId).lookup(song(1, "Song", "Band", null, 1975)))
        assertEquals(2, mb.calls.size)
    }

    @Test
    fun `checking all by fingerprint redoes name matches, searches anyway, and keeps the earliest year`() = runBlocking {
        val clock = FakeClock()
        val cache = MemoryCache()
        val acoustId = FakeFetcher(clock, respondPost = batched { acoustIdFor("r" to "Song") }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { url ->
            val q = decoded(url)
            if ("rid:(" in q) recordingsIn(q, Triple("r", "Song", "1985")) else searchFor("Song", "1990")
        }
        val song = song(1, "Song", "Band", null, null)
        // First matched by name only, before fingerprinting (no key).
        assertEquals(LookupResult.Match(1990), fingerprintLookup(clock, mb, acoustId, cache, key = "").lookup(song))
        assertEquals(false, cache.map.values.single().fingerprintChecked)

        val searchesBefore = mb.calls.count { "recording:" in decoded(it.second) }
        val results = fingerprintLookup(clock, mb, acoustId, cache).lookupAll(listOf(song), byFingerprint = true)
        assertEquals(LookupResult.Match(1985), results[1L], "the fingerprint's earlier year wins")
        assertEquals(1, acoustId.posts.size)
        assertTrue(mb.calls.count { "recording:" in decoded(it.second) } > searchesBefore, "the name search runs as well")
        assertEquals(true, cache.map.values.single().fingerprintChecked)
        assertEquals(true, cache.map.values.single().viaFingerprint)
    }

    @Test
    fun `checking all by fingerprint checks a streamed song by name and marks it checked`() = runBlocking {
        val clock = FakeClock()
        val cache = MemoryCache()
        val acoustId = FakeFetcher(clock, respondPost = batched { acoustIdFor() }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { searchFor("Song", "1990") }
        val fingerprinted = mutableListOf<Long>()
        val lookup = fingerprintLookup(clock, mb, acoustId, cache, fingerprinter = Fingerprinter { fingerprinted += it.id; "fp${it.id}" })
        val streamed = song(1, "Song", "Band", null, null).copy(source = SongSource.DEEZER)
        val results = lookup.lookupAll(listOf(streamed), byFingerprint = true)
        assertEquals(LookupResult.Match(1990), results[1L])
        assertTrue(fingerprinted.isEmpty(), "a streamed song has no file to fingerprint")
        assertTrue(acoustId.posts.isEmpty())
        // Marked as checked, so the next run doesn't take it again (D-276).
        assertEquals(true, cache.map.values.single().fingerprintChecked)
    }

    @Test
    fun `checking all by fingerprint reports results group by group`() = runBlocking {
        val clock = FakeClock()
        val acoustId = FakeFetcher(clock, respondPost = batched { acoustIdFor() }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { url -> if ("rid:(" in decoded(url)) recordingsIn(decoded(url)) else searchFor("Song", "1990") }
        val fingerprinted = mutableListOf<Long>()
        val lookup = fingerprintLookup(clock, mb, acoustId, fingerprinter = Fingerprinter { fingerprinted += it.id; "fp${it.id}" })
        val songs = (1L..100L).map { song(it, "Song", "Band", null, null) }
        var fingerprintedAtFirstResult = -1
        lookup.lookupAll(songs, byFingerprint = true) { _, _ -> if (fingerprintedAtFirstResult < 0) fingerprintedAtFirstResult = fingerprinted.size }
        // Before: no result until all 100 songs were fingerprinted (feedback round 27).
        assertTrue(fingerprintedAtFirstResult in 1 until 100, "first result after $fingerprintedAtFirstResult fingerprints")
    }

    @Test
    fun `an undecodable file falls back to the name search`() = runBlocking {
        val clock = FakeClock()
        val acoustId = FakeFetcher(clock, respondPost = batched { acoustIdFor() }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { searchFor("A", "1970") }
        val lookup = fingerprintLookup(clock, mb, acoustId, fingerprinter = Fingerprinter { null })
        assertEquals(LookupResult.Match(1970), lookup.lookup(song(1, "A", "Band", null, null)))
        assertTrue(acoustId.posts.isEmpty())
    }

    @Test
    fun `a rejected AcoustID key falls back to name searches`() = runBlocking {
        val clock = FakeClock()
        val acoustId = FakeFetcher(clock, respondPost = { HttpResponse(400, """{"status":"error","error":{"code":4,"message":"invalid API key"}}""") }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock, respond = searches("A" to "1970", "B" to "1980"))
        val results = fingerprintLookup(clock, mb, acoustId).lookupAll(listOf(song(1, "A", "Band", null, null), song(2, "B", "Band", null, null)))
        assertEquals(LookupResult.Match(1970), results[1L])
        assertEquals(LookupResult.Match(1980), results[2L])
        assertEquals(1, acoustId.posts.size, "fingerprinting stops after the key is rejected")
    }

    @Test
    fun `AcoustID refusing every request is reported as unavailable, not as offline`() = runBlocking {
        val clock = FakeClock()
        val acoustId = FakeFetcher(clock, respondPost = { HttpResponse(503, "") }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { HttpResponse(200, """{"count":0,"recordings":[]}""") }
        val results = fingerprintLookup(clock, mb, acoustId).lookupAll(listOf(song(1, "A", "Band", null, null)))
        assertEquals(LookupResult.Unavailable(LookupService.ACOUSTID), results[1L])
    }

    @Test
    fun `a failed AcoustID request puts its songs at the end without fingerprinting them again`() = runBlocking {
        val clock = FakeClock()
        var failed = false
        val answer = batched { fp -> if (fp == "fp1") acoustIdFor("a1" to "A") else acoustIdFor("b1" to "B") }
        val acoustId = FakeFetcher(clock, respondPost = { form ->
            when {
                // The first batch holds songs 1-10; it fails once.
                form["fingerprint.0"] == "fp1" && !failed -> { failed = true; HttpResponse(503, "") }
                else -> answer(form)
            }
        }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { url -> recordingsIn(decoded(url), Triple("a1", "A", "1970"), Triple("b1", "B", "1980")) }
        var fingerprinted = 0
        val lookup = fingerprintLookup(clock, mb, acoustId, fingerprinter = Fingerprinter { fingerprinted++; "fp${it.id}" })
        val order = mutableListOf<Long>()
        // Song 11 is in the second AcoustID batch, so it is done while songs 1-10 wait at the end.
        val songs = listOf(song(1, "A", "Band", null, null)) + (2L..10L).map { song(it, "N$it", "Band", null, null) } + song(11, "B", "Band", null, null)
        val results = lookup.lookupAll(songs) { s, _ -> order += s.id }
        assertEquals(11L, order.first())
        assertEquals(LookupResult.Match(1970), results[1L])
        assertEquals(LookupResult.Match(1980), results[11L])
        assertEquals(11, fingerprinted)
        assertEquals(3, acoustId.posts.size, "two batches, the failed one once more")
        assertTrue(mb.calls.any { isSearchFor("A", it.second) } && mb.calls.any { isSearchFor("B", it.second) }, "matched songs are searched by name too")
    }

    @Test
    fun `audio matches accept extra title words and co-credited artists and are marked`() = runBlocking {
        val clock = FakeClock()
        // Felix's AniMe - Terrordome: AcoustID knows it as "Terrordome #TiH" (D-076).
        val acoustId = FakeFetcher(clock, respondPost = batched { fp ->
            if (fp == "fp1") """[{"id":"t","score":0.94,"recordings":[{"id":"t1","title":"Terrordome #TiH","artists":[{"name":"AniMe"}]}]}]"""
            else """[{"id":"t","score":0.62,"recordings":[{"id":"h1","title":"Hardcore Machine","artists":[{"name":"AniMe"},{"name":"DJ Mad Dog"}]}]}]"""
        }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { url ->
            val q = decoded(url)
            if ("rid:(" in q) {
                val recs = listOfNotNull(
                    recJson("t1", "Terrordome #TiH", "AniMe", "2008").takeIf { "t1" in q },
                    """{"id":"h1","title":"Hardcore Machine","first-release-date":"2010","artist-credit":[{"name":"AniMe","joinphrase":" & "},{"name":"DJ Mad Dog"}]}""".takeIf { "h1" in q },
                )
                HttpResponse(200, """{"count":${recs.size},"recordings":[${recs.joinToString(",")}]}""")
            } else HttpResponse(200, """{"count":0,"recordings":[]}""")
        }
        val cache = MemoryCache()
        val results = fingerprintLookup(clock, mb, acoustId, cache).lookupAll(listOf(song(1, "Terrordome", "AniMe", null, null), song(2, "Hardcore machine", "AniMe", null, null)))
        assertEquals(LookupResult.Match(2008), results[1L])
        assertEquals(LookupResult.Match(2010), results[2L])
        assertTrue(cache.map.values.all { it.viaFingerprint })
        assertEquals(2, mb.calls.size, "the ids, then one combined name search")
    }

    @Test
    fun `a year from the name search is not marked as a fingerprint match`() = runBlocking {
        val clock = FakeClock()
        val acoustId = FakeFetcher(clock, respondPost = batched { acoustIdFor() }) { HttpResponse(404, "") }
        val cache = MemoryCache()
        fingerprintLookup(clock, FakeFetcher(clock) { searchFor("A", "1970") }, acoustId, cache).lookup(song(1, "A", "Band", null, null))
        assertEquals(false, cache.map.values.single().viaFingerprint)
    }

    @Test
    fun `the key status follows AcoustID's answers`() = runBlocking {
        val clock = FakeClock()
        var answer: () -> HttpResponse = { HttpResponse(400, """{"status":"error","error":{"code":3,"message":"invalid fingerprint"}}""") }
        val acoustId = FakeFetcher(clock, respondPost = { answer() }) { HttpResponse(404, "") }
        val matcher = FingerprintMatcher(
            AcoustIdClient(RateLimitedQueue(acoustId, { clock.now }, { clock.sleep(it) }, minIntervalMs = 340), { "key" }),
            Fingerprinter { "fp" },
            enabled = { true },
        )
        matcher.checkKey("key")
        assertEquals(AcoustIdKeyStatus("key", AcoustIdKeyState.VALID), matcher.keyStatus.value, "invalid fingerprint means the key passed")
        answer = { HttpResponse(400, """{"status":"error","error":{"code":4,"message":"invalid API key"}}""") }
        matcher.checkKey("key")
        assertEquals(AcoustIdKeyState.REJECTED, matcher.keyStatus.value.state)
        answer = { throw IOException("offline") }
        matcher.checkKey("key")
        assertEquals(AcoustIdKeyState.UNREACHABLE, matcher.keyStatus.value.state)
        matcher.checkKey("")
        assertEquals(AcoustIdKeyState.NONE, matcher.keyStatus.value.state)

        // A lookup that gets rejected reports it too.
        answer = { HttpResponse(400, """{"status":"error","error":{"code":4,"message":"invalid API key"}}""") }
        val lookup = MusicBrainzLookup(
            MusicBrainzClient(RateLimitedQueue(FakeFetcher(clock) { searchFor("A", "1970") }, { clock.now }, { clock.sleep(it) })), MemoryCache(), { true }, { 2026 }, { clock.now },
            fingerprints = matcher,
        )
        lookup.lookup(song(1, "A", "Band", null, null))
        assertEquals(AcoustIdKeyStatus("key", AcoustIdKeyState.REJECTED), matcher.keyStatus.value)
    }

    @Test
    fun `turning fingerprint matching on makes older results due again, but not without a key`() = runBlocking {
        val clock = FakeClock()
        val acoustId = FakeFetcher(clock, respondPost = batched { acoustIdFor() }) { HttpResponse(404, "") }
        val mb = FakeFetcher(clock) { searchFor("A", "1970") }
        val cache = MemoryCache()
        val a = song(1, "A", "Band", null, null)
        val noKey = fingerprintLookup(clock, mb, acoustId, cache, key = "")
        assertEquals(MusicBrainzLookup.LOOKUP_VERSION, noKey.currentVersion())
        noKey.lookup(a)
        assertEquals(MusicBrainzLookup.LOOKUP_VERSION, cache.map.values.single().lookupVersion)
        val calls = mb.calls.size
        noKey.lookup(a)
        assertEquals(calls, mb.calls.size, "no key: the result is current")
        fingerprintLookup(clock, mb, acoustId, cache).lookup(a)
        assertEquals(1, acoustId.posts.size, "with a key the song is looked up again, fingerprint first")
        assertEquals(MusicBrainzLookup.LOOKUP_VERSION_FINGERPRINT, cache.map.values.single().lookupVersion)
    }
}
