package com.trackrecord.data.musicbrainz

import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.db.searchedByName
import com.trackrecord.engine.musicbrainz.MatchAcceptance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

/** Cache of lookup results, including "no match". */
interface MbCache {
    suspend fun get(key: String): MbCacheEntity?
    suspend fun put(entry: MbCacheEntity)
}

sealed interface LookupResult {
    data class Match(val year: Int) : LookupResult
    data object NoMatch : LookupResult
    /** Lookups are switched off (or, in a build without a built-in contact, no contact email is set). */
    data object Skipped : LookupResult

    /** The phone couldn't reach the service: no connection (feedback round 47; was part of [Skipped]). */
    data object Offline : LookupResult

    /**
     * [service] kept turning requests away, also at the slowest pace, and the run stopped (feedback round 47):
     * it seems to be unavailable for now, worth trying again later.
     */
    data class Unavailable(val service: LookupService) : LookupResult
}

enum class LookupService { MUSICBRAINZ, ACOUSTID }

/**
 * Looks songs up on MusicBrainz: gathers candidates from their recording ids, their ISRCs, their audio
 * fingerprint (via AcoustID, when a key is set) and a search on artist + title, accepts only matching
 * ones and takes the earliest date. Results are cached; a "no match" is retried after [noMatchRetryMs].
 *
 * Requests are consolidated (DECISIONS.md D-071, D-075): recording ids (tagged or found by fingerprint)
 * and ISRCs of many songs go into one search each; and a search starts narrowed to releases up to the tag
 * year. Every song is also searched by artist and title, since ids name one recording, which may be a
 * remaster, and only the search finds the original release (feedback round 47; D-071 skipped it when the
 * ids agreed with the tags). A request that fails for a passing reason
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
    /** Lookup steps under way at once (feedback round 48); 1 runs them one after another. */
    private val parallel: Int = PARALLEL,
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
        /** No artist: no search possible, done after the id step. */
        var settled = false
        var retries = 0
    }

    /**
     * Looks up several songs with as few requests as possible. [onResult] is called for each song as
     * soon as its result is known (cached ones first). A failed request puts its song at the end of the
     * queue, at most [MAX_RETRIES] times. Offline, or after [MAX_FAILURES_IN_ROW] failed requests in a
     * row, the remaining songs are [LookupResult.Unavailable] (offline: [LookupResult.Offline]) and nothing
     * more is cached for them.
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
    ): Map<Long, LookupResult> {
        // A song another run is looking up right now, at least as thoroughly, isn't asked for twice: this run waits
        // for that result instead (feedback round 48: a game's lookups and a library verification at once).
        val (mine, waiting) = claims.claim(songs.distinctBy { it.id }, audio, byFingerprint)
        val results = LinkedHashMap<Long, LookupResult>()
        val claimOf = mine.associate { (song, claim) -> song.id to claim }
        // The run works on a thread of its own (D-321); results reach the caller on its thread, e.g. a game's main thread.
        val caller = currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.Default
        try {
            results += runLookups(mine.map { it.first }, force, byFingerprint, audio, { song -> withContext(caller) { onFingerprint(song) } }) { song, result ->
                claimOf[song.id]?.result?.complete(result)
                withContext(caller) { onResult(song, result) }
            }
        } finally {
            claims.release(mine)
        }
        val again = mutableListOf<SongEntity>()
        for ((song, claim) in waiting) {
            // The other run stopped before this song (cancelled): this run looks it up itself.
            val result = claim.result.await()
            if (result == null) {
                again += song
                continue
            }
            results[song.id] = result
            onResult(song, result)
        }
        if (again.isNotEmpty()) results += lookupAll(again, force, byFingerprint, audio, onFingerprint, onResult)
        return results
    }

    private val claims = Claims()

    /**
     * The songs being looked up right now, by cache key, each with the result it will have. A claim made by name
     * only doesn't stand in for a fingerprint check.
     */
    private class Claims {
        class Claim(val audio: Boolean, val byFingerprint: Boolean) {
            val result = CompletableDeferred<LookupResult?>()
        }

        private val claims = HashMap<String, Claim>()

        /** Claims the songs nobody looks up as thoroughly yet; the rest come with the claim to wait for. */
        fun claim(songs: List<SongEntity>, audio: Boolean, byFingerprint: Boolean): Pair<List<Pair<SongEntity, Claim>>, List<Pair<SongEntity, Claim>>> =
            synchronized(this) {
                val mine = mutableListOf<Pair<SongEntity, Claim>>()
                val waiting = mutableListOf<Pair<SongEntity, Claim>>()
                for (song in songs) {
                    val key = keyOf(song)
                    val other = claims[key]
                    if (other != null && other.audio >= audio && other.byFingerprint >= byFingerprint) {
                        waiting += song to other
                    } else {
                        val claim = Claim(audio, byFingerprint)
                        if (other == null) claims[key] = claim
                        mine += song to claim
                    }
                }
                mine to waiting
            }

        /** The run ended: songs it didn't get to are given up (null), so those waiting look them up themselves. */
        fun release(mine: List<Pair<SongEntity, Claim>>) = synchronized(this) {
            for ((song, claim) in mine) {
                claim.result.complete(null)
                val key = keyOf(song)
                if (claims[key] === claim) claims.remove(key)
            }
        }
    }

    private suspend fun runLookups(
        songs: List<SongEntity>,
        force: Boolean,
        byFingerprint: Boolean,
        audio: Boolean,
        onFingerprint: suspend (SongEntity) -> Unit,
        onResult: suspend (SongEntity, LookupResult) -> Unit,
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
            val fresh = hit != null && !force && !byFingerprint && hit.lookupVersion >= version && hit.searchedByName
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
        /** The service whose refusals stopped the run; null when it stopped because the phone is offline. */
        var refusedBy: LookupService? = null
        /** The service that refused the last failed request. */
        var lastRefused = LookupService.MUSICBRAINZ

        /** Runs one network step; null if it failed (the caller then requeues, unless [stop] is set). */
        suspend fun <T : Any> net(service: LookupService, step: suspend () -> T): T? = try {
            step().also { failuresInRow = 0 }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AcoustIdKeyException) {
            throw e
        } catch (e: ServiceUnavailableException) {
            lastRefused = service
            if (++failuresInRow >= MAX_FAILURES_IN_ROW) {
                stop = true
                refusedBy = service
            }
            null
        } catch (e: java.io.IOException) {
            stop = true // no connection
            null
        } catch (e: Exception) {
            // An answer that can't be read: the service misbehaves, the phone is online.
            stop = true
            refusedBy = service
            null
        }

        suspend fun requeue(work: Work) {
            // Only refusals requeue: a song refused this often found the service unavailable too.
            if (++work.retries > MAX_RETRIES) done(work.song, LookupResult.Unavailable(lastRefused)) else todo.addLast(work)
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
                    net(LookupService.ACOUSTID) { fingerprints!!.recordingIds(chunk.map { it.song to it.fingerprint!! }) }
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
            val fetched = net(LookupService.MUSICBRAINZ) { client.recordingsByIds(ids) to client.recordingsByIsrcs(isrcs) }
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
                w.accepted = fromTags + fromAudio
                w.fromAudio = fromAudio
            }
            // Songs without an artist can't be searched and are done now. Every song is searched by name too (D-309), so
            // each group's name searches come right after its fast steps: the progress moves on steadily instead of
            // staying at 0 until every song's ISRC or file was handled (feedback rounds 27 and 48), and an interrupted
            // run keeps what it finished.
            val searchNext = mutableListOf<Work>()
            for (w in ready) {
                if (w.settled) done(w.song, store(w, w.accepted!!, version)) else searchNext += w
            }
            searchNext.asReversed().forEach { todo.addFirst(it) }
        }

        /** Name searches, [SEARCH_BATCH] songs per request. */
        suspend fun search(batch: List<Work>) {
            val found = net(LookupService.MUSICBRAINZ) {
                searchAll(batch.map { NameQuery.of(it.song.artist!!, it.song.title, it.song.tagOriginalYear ?: it.song.tagYear, it.accepted!!, ::earliestYear) })
            }
            if (found == null) {
                if (!stop) batch.forEach { requeue(it) }
                return
            }
            batch.zip(found) { w, more -> done(w.song, store(w, w.accepted!! + more, version, searched = true)) }
        }

        // "Check all by fingerprint" works in AcoustID-sized groups, so the first results come within seconds.
        val groupSize = if (byFingerprint) AcoustIdClient.BATCH else GROUP

        /** The next step: a group's fast steps, or a batch of name searches. */
        fun nextStep(): suspend () -> Unit {
            val takeGroup = todo.first().accepted == null
            val limit = if (takeGroup) groupSize else SEARCH_BATCH
            val taken = mutableListOf<Work>()
            while (todo.isNotEmpty() && (todo.first().accepted == null) == takeGroup && taken.size < limit) taken += todo.removeFirst()
            return if (takeGroup) ({ prepare(taken) }) else ({ search(taken) })
        }

        // Up to [parallel] steps at a time, so the queue starts the next request while answers are still on their way
        // (feedback round 48). The steps share this run's lists and counters: they run on one thread and take turns
        // whenever one waits. A step taken after the run stopped does nothing; its songs end like the rest.
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val slots = Semaphore(parallel)
            val stepEnded = Channel<Unit>(Channel.CONFLATED)
            var active = 0
            while (!stop) {
                computeAhead()
                if (todo.isEmpty()) {
                    if (active == 0) break
                    stepEnded.receive() // a running step may put songs back
                    continue
                }
                slots.acquire()
                if (stop || todo.isEmpty()) {
                    slots.release()
                    continue
                }
                val step = nextStep()
                active++
                launch {
                    try {
                        if (!stop) step()
                    } finally {
                        active--
                        slots.release()
                        stepEnded.trySend(Unit)
                    }
                }
            }
        }
        pending.values.forEach { it.cancel() }
        val left = refusedBy?.let { LookupResult.Unavailable(it) } ?: LookupResult.Offline
        unfinished.filter { it.id !in results }.forEach { done(it, left) }
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

    private suspend fun store(w: Work, accepted: List<MbRecording>, version: Int, searched: Boolean = false): LookupResult {
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
                nameSearched = searched,
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

    /**
     * A song's name search. [before]: only recordings first released up to that year are asked for, the tag year or
     * the earliest year already [known] from ids, ISRC or fingerprint, whichever is earlier: later releases can't
     * change the year, and the smaller answer keeps a batch on one page (feedback round 48: a Deezer compilation
     * date as the bound made batches of hits overflow and split down to single songs).
     */
    private data class NameQuery(val artist: String, val title: String, val before: Int?, val known: List<MbRecording>, val knownYear: Int?) {
        /**
         * The search to try when nothing turned up to [before]: only when that bound was the tag year, which may be
         * too early; then up to the known year, or open without one. Null when the bound was the known year.
         */
        val retry: NameQuery? get() = if (before != null && (knownYear == null || before < knownYear)) copy(before = knownYear) else null

        companion object {
            fun of(artist: String, title: String, tagYear: Int?, known: List<MbRecording>, earliest: (List<MbRecording>) -> Int?): NameQuery {
                val knownYear = earliest(known)
                return NameQuery(artist, title, listOfNotNull(tagYear, knownYear).minOrNull(), known, knownYear)
            }
        }
    }

    /**
     * The name searches of several songs, combined into as few requests as possible (D-078). Each song's
     * first search (up to [NameQuery.before]) goes into one OR query; songs that found nothing get their
     * [NameQuery.retry], batched the same way. A batch whose results don't fit on one page is
     * split in halves; a single song is searched as in [searchEarliest]. A complete page holds every
     * hit, so no narrowing is needed for songs answered from a batch.
     */
    private suspend fun searchAll(queries: List<NameQuery>): List<List<MbRecording>> {
        if (queries.size == 1) return queries.map { searchEarliest(it) }
        val page = client.searchMany(queries.map { NameSearch(it.artist, it.title, it.before) })
            ?: return searchAll(queries.subList(0, queries.size / 2)) + searchAll(queries.subList(queries.size / 2, queries.size))
        val found = queries.map { acceptedOf(page, it.artist, it.title).toMutableList() }
        val open = queries.indices.filter { found[it].isEmpty() && queries[it].retry != null }
        if (open.isNotEmpty()) {
            searchAll(open.map { queries[it].retry!! }).forEachIndexed { i, more -> found[open[i]] += more }
        }
        return found
    }

    /**
     * Search on artist + title. With a bound ([NameQuery.before]) the first request already asks only for releases up
     * to that year; if that finds nothing acceptable and the bound was the tag year, [NameQuery.retry] follows. A search page holds at most
     * 100 recordings and the order among equal scores is arbitrary, so while a page is incomplete the
     * search is narrowed to releases before the best year so far, at most [MAX_NARROWING] times (D-069).
     */
    private suspend fun searchEarliest(query: NameQuery): List<MbRecording> {
        val (artist, title, before, known) = query
        val found = mutableListOf<MbRecording>()
        var page = client.search(artist, title, releasedBefore = before)
        found += acceptedOf(page.recordings, artist, title)
        val retry = query.retry
        if (found.isEmpty() && retry != null) {
            page = client.search(artist, title, releasedBefore = retry.before)
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

        /** Steps at once: as many as requests the queue lets wait for answers ([RateLimitedQueue] maxInFlight). */
        private const val PARALLEL = 3

        /** Songs prepared together: fingerprints, then one batched id request. */
        private const val GROUP = MusicBrainzClient.BATCH
        private const val FINGERPRINT_AHEAD = 2 * GROUP

        /** Songs per combined name search; a batch that overflows the page is split (D-078). */
        const val SEARCH_BATCH = 8

        /** How often one song goes back to the end of the queue after a failed request. */
        const val MAX_RETRIES = 3

        /**
         * Failed requests in a row after which MusicBrainz or AcoustID counts as unavailable for this run
         * ([LookupResult.Unavailable]). With the spacing doubling per failure (2, 4, 8 s, then 10 s, D-307) the
         * last five come at the slowest pace, about a minute of refusals (feedback rounds 29, 47).
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
