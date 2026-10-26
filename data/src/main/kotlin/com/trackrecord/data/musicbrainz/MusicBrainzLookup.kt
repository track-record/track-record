package com.trackrecord.data.musicbrainz

import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.engine.musicbrainz.MatchAcceptance
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Cache of lookup results, including "no match". */
interface MbCache {
    suspend fun get(key: String): MbCacheEntity?
    suspend fun put(entry: MbCacheEntity)
}

sealed interface LookupResult {
    data class Match(val year: Int) : LookupResult
    data object NoMatch : LookupResult
    /** Lookups are off (no contact email / disabled) or the network is unavailable. */
    data object Skipped : LookupResult
}

/**
 * Looks songs up on MusicBrainz: gathers candidates from their recording ids, their ISRCs, their audio
 * fingerprint (via AcoustID, when a key is set) and a search on artist + title, accepts only matching
 * ones and takes the earliest date. Results are cached; a "no match" is retried after [noMatchRetryMs].
 *
 * Requests are consolidated (DECISIONS.md D-071, D-075): recording ids (tagged or found by fingerprint)
 * and ISRCs of many songs go into one search each; a song settled by those needs no search; and a
 * search starts narrowed to releases up to the tag year. A request that fails for a passing reason
 * (rate limit, server trouble, timeout) puts its song at the end of the queue (D-074).
 */
class MusicBrainzLookup(
    private val client: MusicBrainzClient,
    private val cache: MbCache,
    private val enabled: suspend () -> Boolean,
    private val currentYear: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val noMatchRetryMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val fingerprints: FingerprintMatcher? = null,
) {
    /** Cached result without touching the network; null if never looked up. */
    suspend fun cached(song: SongEntity): MbCacheEntity? = cache.get(keyOf(song))

    /** The method version new results get; with fingerprint matching on, older results are outdated. */
    suspend fun currentVersion(): Int = if (fingerprints?.enabled() == true) LOOKUP_VERSION_FINGERPRINT else LOOKUP_VERSION

    suspend fun lookup(song: SongEntity, force: Boolean = false): LookupResult =
        lookupAll(listOf(song), force)[song.id] ?: LookupResult.Skipped

    /** One song on its way through [lookupAll]. */
    private class Work(val song: SongEntity, val hit: MbCacheEntity?) {
        /** Computed fingerprint, kept when the AcoustID request has to be repeated. */
        var fingerprint: String? = null

        /** Recording ids AcoustID linked to the audio; null until asked. */
        var fingerprintIds: List<String>? = null

        /** Accepted candidates from ids, ISRCs and fingerprint; null until the batched id request ran. */
        var accepted: List<MbRecording>? = null

        /** The accepted candidates that came from the fingerprint. */
        var fromAudio: List<MbRecording> = emptyList()

        /**
         * The extensive check is done (D-194): AcoustID answered for this song's audio, its file couldn't
         * be decoded, or, for a streamed song, which has no file to fingerprint, its ISRC and the name
         * search ran (D-276).
         */
        var fingerprinted = false
        var settled = false
        var retries = 0
    }

    /**
     * Looks up several songs with as few requests as possible. [onResult] is called for each song as
     * soon as its result is known (cached ones first). A failed request puts its song at the end of the
     * queue, at most [MAX_RETRIES] times. Offline, or after [MAX_FAILURES_IN_ROW] failed requests in a
     * row, the remaining songs are [LookupResult.Skipped] and nothing more is cached for them.
     */
    suspend fun lookupAll(
        songs: List<SongEntity>,
        force: Boolean = false,
        /**
         * "Check all by fingerprint" (D-194): every song is looked up again with its fingerprint, also
         * songs with a recording id tag or an earlier match, and the name search always runs as well,
         * so the earliest year from audio and names wins.
         */
        byFingerprint: Boolean = false,
        /** False: by name (and id tags) only, without fingerprints ("Verify by name", feedback round 30). */
        audio: Boolean = true,
        /** A song's audio was fingerprinted (or couldn't be decoded): progress before its result (feedback round 29). */
        onFingerprint: suspend (SongEntity) -> Unit = {},
        onResult: suspend (SongEntity, LookupResult) -> Unit = { _, _ -> },
    ): Map<Long, LookupResult> = coroutineScope {
        val results = LinkedHashMap<Long, LookupResult>()
        suspend fun done(song: SongEntity, result: LookupResult) {
            results[song.id] = result
            onResult(song, result)
        }

        val version = if (audio) currentVersion() else LOOKUP_VERSION
        val todo = ArrayDeque<Work>()
        for (song in songs.distinctBy { it.id }) {
            val hit = cache.get(keyOf(song))
            val fresh = hit != null && !force && !byFingerprint && hit.lookupVersion >= version
            when {
                fresh && hit!!.year != null -> done(song, LookupResult.Match(hit.year!!))
                fresh && clock() - hit!!.checkedAt < noMatchRetryMs -> done(song, LookupResult.NoMatch)
                else -> todo += Work(song, hit)
            }
        }
        if (todo.isEmpty()) return@coroutineScope results
        val unfinished = todo.map { it.song }
        if (!enabled()) {
            unfinished.forEach { done(it, LookupResult.Skipped) }
            return@coroutineScope results
        }

        var useFingerprints = fingerprints != null && version == LOOKUP_VERSION_FINGERPRINT
        if (byFingerprint && !useFingerprints) {
            unfinished.forEach { done(it, LookupResult.Skipped) }
            return@coroutineScope results
        }
        var failuresInRow = 0
        var stop = false

        /** Runs one network step; null if it failed (the caller then requeues, unless [stop] is set). */
        suspend fun <T : Any> net(step: suspend () -> T): T? = try {
            step().also { failuresInRow = 0 }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AcoustIdKeyException) {
            throw e
        } catch (e: ServiceUnavailableException) {
            if (++failuresInRow >= MAX_FAILURES_IN_ROW) stop = true
            null
        } catch (e: Exception) {
            stop = true // offline
            null
        }

        suspend fun requeue(work: Work) {
            if (++work.retries > MAX_RETRIES) done(work.song, LookupResult.Skipped) else todo.addLast(work)
        }

        // Fingerprints are pure computation: they are prepared ahead while the network work goes on.
        val pending = HashMap<Long, Deferred<String?>>()
        fun wantsFingerprint(w: Work) =
            useFingerprints && w.fingerprintIds == null && (byFingerprint || w.song.mbRecordingId == null) && !w.song.artist.isNullOrBlank() &&
                w.song.source == SongSource.LOCAL // previews are only 30 s; streamed songs have an ISRC
        fun computeAhead() {
            todo.asSequence().filter { wantsFingerprint(it) && it.fingerprint == null }.take(FINGERPRINT_AHEAD).forEach { w ->
                pending.getOrPut(w.song.id) { async { fingerprintOf(w.song) } }
            }
        }

        /**
         * Fingerprint ids (AcoustID, [AcoustIdClient.BATCH] songs per request, D-077), then one batched
         * request for the group's recording ids and ISRCs.
         */
        suspend fun prepare(group: List<Work>) {
            val asking = mutableListOf<Work>()
            for (w in group) {
                // Streamed songs have no file to fingerprint: in "Verify by fingerprint" their ISRC and the
                // name search are the extensive check (D-276).
                if (byFingerprint && w.song.source != SongSource.LOCAL) w.fingerprinted = true
                if (!wantsFingerprint(w)) continue
                val ahead = pending.remove(w.song.id)
                val fp = w.fingerprint ?: if (ahead != null) ahead.await() else fingerprintOf(w.song)
                onFingerprint(w.song)
                if (fp == null) {
                    w.fingerprintIds = emptyList() // undecodable file: name search only
                    w.fingerprinted = true
                } else {
                    w.fingerprint = fp
                    asking += w
                }
            }
            val failed = mutableListOf<Work>()
            for (chunk in asking.chunked(AcoustIdClient.BATCH)) {
                if (stop || !useFingerprints) break
                val ids = try {
                    net { fingerprints!!.recordingIds(chunk.map { it.song to it.fingerprint!! }) }
                } catch (e: AcoustIdKeyException) {
                    NetLog.w("AcoustID rejected the API key: ${e.message}")
                    useFingerprints = false // the rest of the run matches by name
                    pending.values.forEach { it.cancel() }
                    break
                }
                if (ids == null) failed += chunk else chunk.zip(ids) { w, found -> w.fingerprintIds = found; w.fingerprinted = true }
            }
            if (stop) return
            failed.forEach { requeue(it) }
            val ready = group.filter { w -> failed.none { it === w } }
            if (ready.isEmpty()) return
            val ids = ready.flatMap { listOfNotNull(it.song.mbRecordingId?.lowercase()) + it.fingerprintIds.orEmpty() }.distinct()
            val isrcs = ready.mapNotNull { it.song.isrc }.distinct()
            val fetched = net { client.recordingsByIds(ids) to client.recordingsByIsrcs(isrcs) }
            if (fetched == null) {
                if (!stop) ready.forEach { requeue(it) }
                return
            }
            val byId = fetched.first.associateBy { it.id.lowercase() }
            for (w in ready) {
                val artist = w.song.artist
                if (artist.isNullOrBlank()) {
                    w.accepted = emptyList()
                    w.settled = true
                    continue
                }
                val fromTags = buildList {
                    w.song.mbRecordingId?.let { id -> byId[id.lowercase()]?.let { addAll(acceptedOf(listOf(it), artist, w.song.title)) } }
                    if (w.song.isrc != null) addAll(acceptedOf(fetched.second, artist, w.song.title))
                }
                val fromAudio = acceptedOf(w.fingerprintIds.orEmpty().mapNotNull { byId[it] }, artist, w.song.title, audio = true)
                val best = earliestYear(fromTags + fromAudio)
                val tagYear = w.song.tagOriginalYear ?: w.song.tagYear
                // Ids that agree with (or beat) the tags settle a song: a search can't change the answer
                // that matters (D-071). A fingerprint match settles it on its own unless the tags name an
                // earlier year (D-075).
                w.settled = !byFingerprint && best != null && (if (tagYear != null) best <= tagYear else fromAudio.isNotEmpty())
                w.accepted = fromTags + fromAudio
                w.fromAudio = fromAudio
            }
            // Settled songs are done now; the rest wait for the name searches, which come after every
            // song's fast steps (D-078). "Check all by fingerprint" searches every song by name too, so
            // there each group is finished first: the progress moves on steadily instead of staying at 0
            // until every file is fingerprinted (feedback round 27).
            val searchNext = mutableListOf<Work>()
            for (w in ready) {
                when {
                    w.settled -> done(w.song, store(w, w.accepted!!, version))
                    byFingerprint -> searchNext += w
                    else -> todo.addLast(w)
                }
            }
            searchNext.asReversed().forEach { todo.addFirst(it) }
        }

        // "Check all by fingerprint" works in AcoustID-sized groups, so the first results come within seconds.
        val groupSize = if (byFingerprint) AcoustIdClient.BATCH else GROUP
        while (todo.isNotEmpty() && !stop) {
            computeAhead()
            if (todo.first().accepted == null) {
                val group = mutableListOf<Work>()
                while (todo.isNotEmpty() && todo.first().accepted == null && group.size < groupSize) group += todo.removeFirst()
                prepare(group)
                continue
            }
            // Name searches, [SEARCH_BATCH] songs per request.
            val batch = mutableListOf<Work>()
            while (todo.isNotEmpty() && todo.first().accepted != null && batch.size < SEARCH_BATCH) batch += todo.removeFirst()
            val found = net {
                searchAll(batch.map { NameQuery(it.song.artist!!, it.song.title, it.song.tagOriginalYear ?: it.song.tagYear, it.accepted!!) })
            }
            if (found == null) {
                if (!stop) batch.forEach { requeue(it) }
                continue
            }
            batch.zip(found) { w, more -> done(w.song, store(w, w.accepted!! + more, version)) }
        }
        pending.values.forEach { it.cancel() }
        unfinished.filter { it.id !in results }.forEach { done(it, LookupResult.Skipped) }
        results
    }

    /** A song's fingerprint; null when the file can't be decoded (a failure never ends the lookup). */
    private suspend fun fingerprintOf(song: SongEntity): String? = try {
        fingerprints?.fingerprint(song)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun store(w: Work, accepted: List<MbRecording>, version: Int): LookupResult {
        val song = w.song
        val hit = w.hit
        val fromAudio = w.fromAudio
        val year = earliestYear(accepted)
        val first = firstRelease(accepted)
        cache.put(
            MbCacheEntity(
                songKey = keyOf(song),
                year = year,
                checkedAt = clock(),
                releaseId = first?.id,
                releaseGroupId = first?.releaseGroup?.id,
                lookupVersion = version,
                viaFingerprint = year != null && earliestYear(fromAudio) == year,
                fingerprintChecked = w.fingerprinted || hit?.fingerprintChecked == true,
            ),
        )
        return if (year != null) LookupResult.Match(year) else LookupResult.NoMatch
    }

    private fun earliestYear(recordings: List<MbRecording>): Int? = recordings.minOfOrNull { it.toCandidate(currentYear()).year!! }

    /** Accepted candidates; [audio] candidates (linked to the song's fingerprint) use the looser names rule (D-076). */
    private fun acceptedOf(recordings: List<MbRecording>, artist: String, title: String, audio: Boolean = false): List<MbRecording> =
        recordings.filter { r ->
            val c = r.toCandidate(currentYear())
            c.year != null && if (audio) MatchAcceptance.acceptsAudioMatch(artist, title, c) else MatchAcceptance.accepts(artist, title, c)
        }

    private data class NameQuery(val artist: String, val title: String, val tagYear: Int?, val known: List<MbRecording>)

    /**
     * The name searches of several songs, combined into as few requests as possible (D-078). Each song's
     * first search (narrowed to its tag year) goes into one OR query; songs with a tag year that found
     * nothing get an open search, batched the same way. A batch whose results don't fit on one page is
     * split in halves; a single song is searched as in [searchEarliest]. A complete page holds every
     * hit, so no narrowing is needed for songs answered from a batch.
     */
    private suspend fun searchAll(queries: List<NameQuery>): List<List<MbRecording>> {
        if (queries.size == 1) return queries.map { searchEarliest(it.artist, it.title, it.tagYear, it.known) }
        val page = client.searchMany(queries.map { NameSearch(it.artist, it.title, it.tagYear) })
            ?: return searchAll(queries.subList(0, queries.size / 2)) + searchAll(queries.subList(queries.size / 2, queries.size))
        val found = queries.map { acceptedOf(page, it.artist, it.title).toMutableList() }
        val open = queries.indices.filter { found[it].isEmpty() && queries[it].tagYear != null }
        if (open.isNotEmpty()) {
            searchAll(open.map { queries[it].copy(tagYear = null) }).forEachIndexed { i, more -> found[open[i]] += more }
        }
        return found
    }

    /**
     * Search on artist + title. With a tag year the first request already asks only for releases up to
     * that year; if that finds nothing acceptable, an open search follows. A search page holds at most
     * 100 recordings and the order among equal scores is arbitrary, so while a page is incomplete the
     * search is narrowed to releases before the best year so far, at most [MAX_NARROWING] times (D-069).
     */
    private suspend fun searchEarliest(artist: String, title: String, tagYear: Int?, known: List<MbRecording>): List<MbRecording> {
        val found = mutableListOf<MbRecording>()
        var page = client.search(artist, title, releasedBefore = tagYear)
        found += acceptedOf(page.recordings, artist, title)
        if (found.isEmpty() && tagYear != null) {
            page = client.search(artist, title)
            found += acceptedOf(page.recordings, artist, title)
        }
        repeat(MAX_NARROWING) {
            if ((page.count ?: 0) <= page.recordings.size) return found
            val best = earliestYear(found + known) ?: (currentYear() + 1)
            page = client.search(artist, title, releasedBefore = best - 1)
            val more = acceptedOf(page.recordings, artist, title)
            if (more.isEmpty()) return found
            found += more
        }
        return found
    }

    /** Whether MusicBrainz was ever asked about this song (a "no match" counts too). */
    suspend fun checked(song: SongEntity): Boolean = cache.get(keyOf(song)) != null

    /** Records that the Cover Art Archive was asked for this song's release. */
    suspend fun markCoverChecked(song: SongEntity) {
        val hit = cache.get(keyOf(song)) ?: return
        cache.put(hit.copy(coverChecked = true))
    }

    companion object {
        private const val MAX_NARROWING = 3

        /** Songs prepared together: fingerprints, then one batched id request. */
        private const val GROUP = MusicBrainzClient.BATCH
        private const val FINGERPRINT_AHEAD = 2 * GROUP

        /** Songs per combined name search; a batch that overflows the page is split (D-078). */
        const val SEARCH_BATCH = 8

        /** How often one song goes back to the end of the queue after a failed request. */
        const val MAX_RETRIES = 3

        /**
         * Failed requests in a row after which MusicBrainz counts as unavailable for this run. With the
         * spacing doubling per failure (2, 4, 8, 16, 30 … s) that is about 2½ minutes of refusals: a phone
         * sharing the limit with others waits it out (feedback round 29).
         */
        const val MAX_FAILURES_IN_ROW = 8

        /** 1 = searches narrowed to earlier releases (feedback round 3). */
        const val LOOKUP_VERSION = 1

        /**
         * Also matched by audio fingerprint, only used while fingerprint matching is on: 2 = D-075,
         * 3 = looser names for audio matches (D-076).
         */
        const val LOOKUP_VERSION_FINGERPRINT = 3

        /** The release with the earliest date among the accepted recordings (undated releases last). */
        fun firstRelease(recordings: List<MbRecording>): MbRelease? =
            recordings.flatMap { it.releases }.filter { it.id.isNotBlank() }.minByOrNull { it.date?.takeIf(String::isNotBlank) ?: "9999" }

        /** Stable across rescans: normalized artist and title plus the tagged ids. */
        fun keyOf(song: SongEntity): String = keyOf(song.artist, song.title, song.mbRecordingId, song.isrc)

        fun keyOf(artist: String?, title: String, mbid: String?, isrc: String?): String =
            listOf(MatchAcceptance.normalize(artist.orEmpty()), MatchAcceptance.normalize(title), mbid.orEmpty(), isrc.orEmpty()).joinToString("|")
    }
}
