package com.trackrecord.data.musicbrainz

import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Downloads front covers from the Cover Art Archive (MusicBrainz's image service). */
class CoverArtClient(private val userAgent: suspend () -> String) {
    private val client = Http.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Without an overall limit one stalled download blocked every later cover (D-072).
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()

    /**
     * The 500 px front cover of a release (or release group), or null if there is none (HTTP 404).
     * Any other failure throws, so the song is tried again later instead of being marked coverless.
     */
    suspend fun front(kind: String, mbid: String): ByteArray? = mutex.withLock {
        require((kind == "release" || kind == "release-group") && MBID.matches(mbid)) { "not a MusicBrainz id: $mbid" }
        val ua = userAgent()
        val started = System.currentTimeMillis()
        try {
            val bytes = withContext(Dispatchers.IO) {
                val request = Request.Builder().url("https://coverartarchive.org/$kind/$mbid/front-500").header("User-Agent", ua).build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> response.body.bytes()
                        response.code == 404 -> null
                        else -> throw java.io.IOException("Cover Art Archive HTTP ${response.code}")
                    }
                }
            }
            NetLog.i("cover $kind/$mbid -> ${if (bytes == null) "none" else "${bytes.size} B"} in ${System.currentTimeMillis() - started} ms")
            bytes
        } catch (e: Exception) {
            NetLog.w("cover $kind/$mbid failed after ${System.currentTimeMillis() - started} ms: $e")
            throw e
        } finally {
            delay(SPACING_MS) // be polite: one image at a time
        }
    }

    private companion object {
        const val SPACING_MS = 500L
    }
}

/** MusicBrainz ids are UUIDs; anything else never becomes a URL or a file name (DECISIONS.md D-168). */
internal val MBID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/**
 * Cover art of a song's first release, stored as `<releaseId>.jpg` (or the release group's id) in
 * [dir]. Covers are only fetched for songs with a MusicBrainz match.
 */
class CoverArtService(
    private val dir: File,
    private val lookup: MusicBrainzLookup,
    private val client: CoverArtClient,
    private val enabled: suspend () -> Boolean,
) {
    fun coverFile(entry: MbCacheEntity?): File? {
        if (entry == null) return null
        return listOfNotNull(entry.releaseId, entry.releaseGroupId).filter(MBID::matches).map { File(dir, "$it.jpg") }.firstOrNull { it.exists() }
    }

    /**
     * Covers for many songs: matches cached before release ids existed are first looked up again in one
     * batch (D-071), then each cover is fetched. A download that fails for another reason than "no cover"
     * goes to the end of the list, at most [MAX_RETRIES] times (D-074). [onCover] is called once per song.
     */
    suspend fun ensureAll(songs: List<SongEntity>, retryChecked: Boolean = false, onCover: (SongEntity, Boolean) -> Unit) {
        if (!enabled()) return
        val needRelease = songs.filter { s ->
            lookup.cached(s)?.let { it.year != null && it.releaseId == null && it.releaseGroupId == null && !it.coverChecked } == true
        }
        if (needRelease.isNotEmpty()) lookup.lookupAll(needRelease, force = true)
        val queue = ArrayDeque(songs.map { it to 0 })
        var failuresInRow = 0
        while (queue.isNotEmpty()) {
            val (song, retries) = queue.removeFirst()
            // Offline or the archive is down: report the rest as coverless for now (nothing is marked).
            if (failuresInRow >= MAX_FAILURES_IN_ROW) { onCover(song, false); continue }
            val outcome = fetch(song, retryChecked)
            failuresInRow = if (outcome == Outcome.FAILED) failuresInRow + 1 else 0
            when (outcome) {
                Outcome.COVER -> onCover(song, true)
                Outcome.NONE -> onCover(song, false)
                Outcome.FAILED -> if (retries < MAX_RETRIES) queue.addLast(song to retries + 1) else onCover(song, false)
            }
        }
    }

    /**
     * Makes sure a matched song's cover was fetched. Matches cached before releases were stored are
     * looked up again once to learn their first release. Returns true when a cover file exists.
     */
    suspend fun ensure(song: SongEntity, retryChecked: Boolean = false): Boolean = fetch(song, retryChecked) == Outcome.COVER

    private enum class Outcome { COVER, NONE, FAILED }

    private suspend fun fetch(song: SongEntity, retryChecked: Boolean): Outcome {
        if (!enabled()) return Outcome.NONE
        var entry = lookup.cached(song) ?: return Outcome.NONE
        if (entry.year == null) return Outcome.NONE
        coverFile(entry)?.let { return Outcome.COVER }
        if (entry.coverChecked && !retryChecked) return Outcome.NONE
        if (entry.releaseId?.let(MBID::matches) != true && entry.releaseGroupId?.let(MBID::matches) != true) {
            if (lookup.lookup(song, force = true) !is LookupResult.Match) return Outcome.NONE
            entry = lookup.cached(song) ?: return Outcome.NONE
        }
        val release = entry.releaseId?.takeIf(MBID::matches)
        val group = entry.releaseGroupId?.takeIf(MBID::matches)
        val name = release ?: group ?: return Outcome.NONE
        // One download at a time, checked again once it's this one's turn: when a game and "Load cover art" want
        // the same cover, it is fetched once (feedback round 48).
        val bytes = downloads.withLock {
            if (File(dir, "$name.jpg").exists()) return Outcome.COVER
            if (name in noCover) null else {
                try {
                    release?.let { client.front("release", it) } ?: group?.let { client.front("release-group", it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Outcome.FAILED // not marked as checked: tried again later
                }.also { bytes ->
                    if (bytes != null) {
                        dir.mkdirs()
                        File(dir, "$name.jpg").writeBytes(bytes)
                    } else {
                        noCover += name
                    }
                }
            }
        }
        lookup.markCoverChecked(song)
        return if (bytes != null) Outcome.COVER else Outcome.NONE
    }

    private val downloads = Mutex()

    /** Releases the archive has no cover for, asked this session: not asked again until the app restarts. */
    private val noCover = HashSet<String>()

    private companion object {
        const val MAX_RETRIES = 3
        const val MAX_FAILURES_IN_ROW = 5
    }
}
