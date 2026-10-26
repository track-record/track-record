package com.trackrecord.app.library

import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.musicbrainz.CoverArtService
import com.trackrecord.data.musicbrainz.LookupResult
import com.trackrecord.data.musicbrainz.LookupService
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class BulkTask {
    /** "Verify by name": playable songs never checked, by name and id tags only (feedback round 30). */
    VERIFY,

    /** "Check missing years": songs without a usable year. */
    FIND_MISSING,

    /** Matched songs whose first-release cover hasn't been fetched yet. */
    LOAD_COVERS,

    /**
     * "Verify by fingerprint": every song not checked that way yet, also by name; the earliest year wins
     * (D-194). Streamed songs can't be fingerprinted and are checked by ISRC and name (D-276).
     */
    FINGERPRINT_ALL,
}

sealed interface VerifyState {
    data object Idle : VerifyState

    /**
     * [total] 0 while the songs are still being picked; [fingerprinted] counts "Verify by fingerprint"'s
     * first step, of [toFingerprint] local songs (streamed songs have none, D-276).
     */
    data class Running(val task: BulkTask, val done: Int, val total: Int, val matched: Int, val fingerprinted: Int = 0, val toFingerprint: Int = 0) : VerifyState

    /**
     * A finished run (feedback round 29): [failed] songs couldn't be checked (offline or a busy service),
     * [newYears] songs without a year got one, and [earlier] got an earlier year than before.
     */
    data class Finished(
        val task: BulkTask,
        val total: Int,
        val matched: Int,
        val skipped: Boolean,
        val noArtist: Int,
        val failed: Int = 0,
        val newYears: Int = 0,
        val earlier: Int = 0,
        /** The service that kept refusing and stopped the run (feedback round 47), or null. */
        val unavailable: LookupService? = null,
        /** Stopped because the phone is offline; [skipped] alone: lookups are switched off (feedback round 47). */
        val offline: Boolean = false,
    ) : VerifyState
}

/**
 * Bulk MusicBrainz work through the shared queue: "Verify library", "Find missing years" and "Load cover
 * art". Lookups are batched (DECISIONS.md D-071); covers download alongside, since the Cover Art Archive
 * does not count against MusicBrainz's one-request-per-second limit.
 */
class LibraryVerifier(
    private val library: LibraryRepository,
    private val lookup: MusicBrainzLookup,
    private val coverArt: CoverArtService,
    private val onCover: (Long) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<VerifyState>(VerifyState.Idle)
    val state: StateFlow<VerifyState> = _state.asStateFlow()
    private val coverMutex = Mutex()

    fun start(task: BulkTask) {
        if (job?.isActive == true) return
        // Shown at once, while the songs are picked (feedback round 29: covers looked unresponsive).
        _state.value = VerifyState.Running(task, 0, 0, 0)
        job = scope.launch {
            val candidates = when (task) {
                BulkTask.VERIFY -> library.unverifiedSongs(MusicBrainzLookup.LOOKUP_VERSION)
                BulkTask.FIND_MISSING -> library.missingYearSongs()
                // The cached lookup comes with each song: no database query per song.
                BulkTask.LOAD_COVERS -> library.matchedSongs().filter { coverArt.coverFile(it.lookup) == null }.map { it.song }
                BulkTask.FINGERPRINT_ALL -> library.fingerprintUncheckedSongs()
            }
            // Without an artist tag no candidate can be accepted, so those songs are not sent.
            val (songs, noArtist) = candidates.partition { !it.artist.isNullOrBlank() }
            when (task) {
                BulkTask.LOAD_COVERS -> loadCovers(songs, noArtist.size)
                else -> lookUp(task, songs, noArtist.size)
            }
        }
    }

    private suspend fun lookUp(task: BulkTask, songs: List<SongEntity>, noArtist: Int) {
        var done = 0
        var matched = 0
        var failed = 0
        var fingerprinted = 0
        var unavailable: LookupService? = null
        var offline = false
        val before = library.resolvedYears()
        val toFingerprint = if (task == BulkTask.FINGERPRINT_ALL) songs.count { it.source == SongSource.LOCAL } else 0
        _state.value = VerifyState.Running(task, 0, songs.size, 0, toFingerprint = toFingerprint)
        lookup.lookupAll(
            songs,
            byFingerprint = task == BulkTask.FINGERPRINT_ALL,
            audio = task != BulkTask.VERIFY,
            onFingerprint = {
                fingerprinted++
                _state.value = VerifyState.Running(task, done, songs.size, matched, fingerprinted, toFingerprint)
            },
        ) { song, result ->
            done++
            when (result) {
                is LookupResult.Match -> {
                    matched++
                    scope.launch { coverMutex.withLock { if (coverArt.ensure(song)) onCover(song.id) } }
                }
                LookupResult.NoMatch -> Unit
                LookupResult.Skipped -> failed++
                LookupResult.Offline -> {
                    failed++
                    offline = true
                }
                is LookupResult.Unavailable -> {
                    failed++
                    unavailable = result.service
                }
            }
            _state.value = VerifyState.Running(task, done, songs.size, matched, fingerprinted, toFingerprint)
        }
        // What changed, from the years before and after (feedback round 29).
        val after = library.resolvedYears()
        val ids = songs.map { it.id }
        _state.value = VerifyState.Finished(
            task, songs.size, matched, failed > 0, noArtist,
            failed = failed,
            newYears = ids.count { before[it] == null && after[it] != null },
            earlier = ids.count { id -> val b = before[id]; val a = after[id]; b != null && a != null && a < b },
            unavailable = unavailable,
            offline = offline,
        )
    }

    private suspend fun loadCovers(songs: List<SongEntity>, noArtist: Int) {
        var done = 0
        var found = 0
        _state.value = VerifyState.Running(BulkTask.LOAD_COVERS, 0, songs.size, 0)
        coverMutex.withLock {
            // Also retries songs marked coverless earlier: before D-072 a server error counted as "no cover".
            coverArt.ensureAll(songs, retryChecked = true) { song, hasCover ->
                done++
                if (hasCover) { found++; onCover(song.id) }
                _state.value = VerifyState.Running(BulkTask.LOAD_COVERS, done, songs.size, found)
            }
        }
        _state.value = VerifyState.Finished(BulkTask.LOAD_COVERS, songs.size, found, false, noArtist)
    }

    fun cancel() {
        job?.cancel()
        _state.value = VerifyState.Idle
    }
}
