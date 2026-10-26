package com.trackrecord.app.game

import android.content.Context
import com.trackrecord.app.R
import com.trackrecord.data.db.SavedGameDao
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.library.LibraryStats
import com.trackrecord.data.musicbrainz.CoverArtService
import com.trackrecord.data.musicbrainz.LookupResult
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import com.trackrecord.data.sources.AudioResolver
import com.trackrecord.data.stats.PlayerStatsRepository
import com.trackrecord.engine.Card
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.GameState
import com.trackrecord.engine.Phase
import com.trackrecord.engine.ReduceResult
import com.trackrecord.engine.RejectReason
import com.trackrecord.engine.stats.RoundRecord
import com.trackrecord.engine.stats.RoundStats
import com.trackrecord.playback.GameAudio
import com.trackrecord.playback.TrackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Where an action came from: the host's phone or a remote player's (SPEC v3). */
enum class ActionSource { HOST, REMOTE }

sealed interface StartResult {
    data object Started : StartResult
    data class Rejected(val reason: RejectReason) : StartResult
}

/**
 * Owns the running game for the whole app: applies actions through the engine, draws the next song
 * automatically and drives playback. Lives in [com.trackrecord.app.AppContainer], so the game survives
 * navigation and configuration changes.
 */
class GameController(
    private val context: Context,
    private val library: LibraryRepository,
    val audio: GameAudio,
    private val savedGames: SavedGameDao,
    private val musicBrainz: MusicBrainzLookup,
    private val coverArt: CoverArtService,
    private val onCover: (Long) -> Unit,
    private val currentYear: () -> Int,
    /** Turns a song's audio reference into a playable URI (v2: Deezer previews are fetched here). */
    private val audioResolver: AudioResolver = AudioResolver.LocalOnly,
    /** Auto-reveal and veto time from Settings (SPEC v3). */
    revealSettings: StateFlow<RevealSettings> = MutableStateFlow(RevealSettings()),
    /** Players controlled from a guest's phone (SPEC v3). */
    val remotePlayers: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** The user's playlists by id, for the info sheet (feedback round 36). */
    private val playlistNames: suspend () -> Map<Long, String> = { emptyMap() },
    /** Saved players' stats (feedback round 47); null records nothing. */
    private val stats: PlayerStatsRepository? = null,
    clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    /** Lookups for the next songs in flight; the missing-year search waits for them. */
    private val prefetching = MutableStateFlow(0)
    private var candidateJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _session = MutableStateFlow<GameSession?>(null)
    val session: StateFlow<GameSession?> = _session.asStateFlow()

    /** Veto window and auto-reveal of the current Timeline round. */
    private val revealTimer = RevealTimer(scope, revealSettings, remotePlayers, clock, reveal = { dispatch(GameAction.Reveal) })

    /**
     * Whether the host has a game screen open (and the app in front). Guests can only play while it
     * is; otherwise they see "Game paused" (feedback round 9).
     */
    private val _hostOnGame = MutableStateFlow(false)
    val hostOnGame: StateFlow<Boolean> = _hostOnGame.asStateFlow()

    fun setHostOnGame(on: Boolean) {
        _hostOnGame.value = on
    }

    /** The Reveal button while vetoing (locked, waiting for remote players, auto-reveal countdown). */
    val revealStatus = revealTimer.status

    /** v2: the current song can't be played and wasn't replaced automatically (offline, pool empty). */
    private val _unplayable = MutableStateFlow(false)
    val unplayable: StateFlow<Boolean> = _unplayable.asStateFlow()

    /** Songs replaced in the current round because they couldn't be played. */
    private val _replaced = MutableStateFlow(0)
    val replacedThisRound: StateFlow<Int> = _replaced.asStateFlow()
    private var replacedRound = -1

    /** False until the saved game (if any) has been read after app start. */
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    /** Saves run one at a time, in order, so the stored game is always the latest. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val store = GameStore(savedGames)

    /**
     * The newest session to save; saves that pile up while one is written are skipped, and the JSON
     * is encoded on the save thread, not the main thread (DECISIONS.md D-167).
     */
    private val saves = Channel<GameSession?>(Channel.CONFLATED)

    /** Which round's song is loaded into the player ("round-7:123"). */
    private var loadedKey: String? = null

    init {
        scope.launch(saveDispatcher) { for (session in saves) store.write(session) }
        scope.launch {
            val restoredSession = withContext(saveDispatcher) { store.read() }
            if (_session.value == null) _session.value = restoredSession
            restoredSession?.let { revealTimer.onState(it.state) }
            _restored.value = true
            if (restoredSession != null) startBackgroundWork()
        }
    }

    private val _rejections = MutableSharedFlow<RejectReason>(extraBufferCapacity = 8)
    val rejections: SharedFlow<RejectReason> = _rejections

    /** The pool as New game shows it: its size, the songs that stream from Deezer, and its stats. */
    suspend fun poolInfo(settings: GameSettings): PoolInfo = poolSongs(settings).let { songs ->
        withContext(Dispatchers.Default) {
            PoolInfo(songs.size, songs.count { it.song.source == SongSource.DEEZER }, LibraryStats.of(songs))
        }
    }

    private suspend fun poolSongs(settings: GameSettings) =
        library.playableSongs(settings.common.playlistIds, settings.common.excludedPlaylistIds, settings.common.yearFrom, settings.common.yearTo)

    suspend fun start(mode: GameMode, players: List<GamePlayer>, requested: GameSettings): StartResult {
        // Closest Year: these three are always on (DECISIONS.md D-066); the floor is set by the engine.
        val settings = requested.copy(
            closestYear = requested.closestYear.copy(exactGuessEndsRound = true, rotateFirstGuesser = true, distinctGuesses = true),
        )
        val c = settings.common
        val songs = library.playableSongs(c.playlistIds, c.excludedPlaylistIds, c.yearFrom, c.yearTo)
        val action = GameAction.StartGame(
            mode = mode,
            playerIds = players.map { it.id },
            songs = songs.map { Card(it.id, it.resolved.year!!) },
            settings = settings,
            seed = Random.nextLong(),
            currentYear = currentYear(),
        )
        return when (val r = GameEngine.reduce(null, action)) {
            is ReduceResult.Rejected -> StartResult.Rejected(r.reason)
            is ReduceResult.Accepted -> {
                loadedKey = null
                val used = c.playlistIds.orEmpty() + c.excludedPlaylistIds
                val names = runCatching { playlistNames() }.getOrDefault(emptyMap()).filterKeys { it in used }
                _session.value = GameSession(players, r.state, songs.associate { it.id to SongInfo.from(it) }, names)
                persist()
                onAccepted(null, r.state)
                startBackgroundWork()
                StartResult.Started
            }
        }
    }

    /**
     * Applies an action; a rejection leaves the game unchanged and, for the host's own actions, is
     * reported on [rejections].
     */
    fun dispatch(action: GameAction, source: ActionSource = ActionSource.HOST): Boolean = tryDispatch(action, source) == null

    /** Like [dispatch], returning the rejection reason, or null when the action was accepted. */
    fun tryDispatch(action: GameAction, source: ActionSource = ActionSource.HOST): RejectReason? {
        val s = _session.value ?: return RejectReason.NO_GAME
        return when (val r = GameEngine.reduce(s.state, action)) {
            is ReduceResult.Rejected -> {
                if (source == ActionSource.HOST) _rejections.tryEmit(r.reason)
                r.reason
            }
            is ReduceResult.Accepted -> {
                // A placed veto ends that player's gap picking (feedback round 10).
                if (action is GameAction.PlaceVeto) revealTimer.vetoPlaced(action.playerId)
                _session.value = s.copy(state = r.state)
                // A gap chosen while dragging isn't worth a save; the next action saves it anyway (REVIEW-2 P-06).
                if (action !is GameAction.SelectSlot) persist()
                // A new clip length applies to the song playing now too (D-191).
                if (action is GameAction.SetClipLimit) applyClipLimit(r.state)
                recordStats(s, r.state)
                onAccepted(s.state, r.state)
                null
            }
        }
    }

    /** A remote player tapped "Don't veto" (SPEC v3). */
    fun declineVeto(playerId: String) = revealTimer.decline(playerId)

    /** A player tapped "Veto" and picks a gap ([picking]), on a guest phone or this one, or stopped. */
    fun setVetoPicking(playerId: String, picking: Boolean) = revealTimer.setPicking(playerId, picking)

    /**
     * "Wrong year?": recomputes the round and saves the edit to the library for future games. Returns
     * why not, or null when done (a co-host's correction, D-189, reports it to their phone).
     */
    fun correctYear(year: Int, source: ActionSource = ActionSource.HOST): RejectReason? {
        val songId = _session.value?.state?.round?.song?.songId ?: return RejectReason.NO_GAME
        val rejected = tryDispatch(GameAction.CorrectYear(year), source)
        if (rejected == null) scope.launch { runCatching { library.setManualYear(songId, year) } }
        return rejected
    }

    /** The whole song (or Deezer preview) instead of the clip; only after the reveal, so the notification may name it (D-110). */
    suspend fun playFullSong() {
        val session = _session.value ?: return
        if (session.state.phase != Phase.REVEALED) return
        val info = session.state.round?.song?.songId?.let(session::song) ?: return
        audio.playFullSong(info.title, info.artist)
    }

    suspend fun playAgain(): StartResult {
        val s = _session.value ?: return StartResult.Rejected(RejectReason.NO_GAME)
        // Players removed mid-game stay out (feedback round 36).
        return start(s.state.mode, s.playing, s.state.settings)
    }

    fun endGame() {
        candidateJob?.cancel()
        revealTimer.stop()
        audio.stop()
        loadedKey = null
        _session.value = null
        persist()
    }

    /** A game screen is showing: make sure the current round's song is loaded (e.g. after Continue). */
    fun ensureAudio() {
        val state = _session.value?.state ?: return
        if (state.phase == Phase.GAME_OVER) return
        val round = state.round ?: return
        if (loadedKey != keyOf(round.number, round.song.songId)) loadRound(state)
    }

    /** Saves after every action (in [GameStore]); a finished or ended game is removed, so "Continue" disappears. */
    private fun persist() {
        saves.trySend(_session.value)
    }

    private fun keyOf(round: Int, songId: Long) = "round-$round:$songId"

    /**
     * The drawn song can't be played: replace it with the next pool song (it goes back undrawn, D-081),
     * at most [MAX_AUTO_REPLACE] times per round; after that, or with an empty pool, the host decides.
     */
    private fun onUnplayable() {
        audio.stop()
        val state = _session.value?.state ?: return
        val possible = GameEngine.reduce(state, GameAction.ReplaceUnplayable) is ReduceResult.Accepted
        if (possible && _replaced.value < MAX_AUTO_REPLACE) {
            _replaced.update { it + 1 }
            dispatch(GameAction.ReplaceUnplayable)
        } else {
            _unplayable.value = true
        }
    }

    private fun applyClipLimit(state: GameState) {
        val songId = state.round?.song?.songId ?: return
        val streamed = _session.value?.song(songId)?.streamed == true
        audio.changeClipLimit(state.settings.common.clipLimitSeconds?.let { it * 1000L }?.let { if (streamed) it.coerceAtMost(PREVIEW_MS) else it })
    }

    private val _lobby = MutableStateFlow<List<GamePlayer>?>(null)

    /** The players of the game being set up in New game, or null (guests pick theirs early, feedback round 32). */
    val lobby: StateFlow<List<GamePlayer>?> = _lobby.asStateFlow()

    fun setLobby(players: List<GamePlayer>?) {
        _lobby.value = players
    }

    /**
     * Saved players' stats (feedback round 47): a round when Next commits it, as it stood revealed (year
     * corrections and naming marks included); a game when it ends. Recording again replaces, so a restored game
     * doesn't count twice.
     */
    private fun recordStats(before: GameSession, after: GameState) {
        val repo = stats ?: return
        val step = StatsStep.after(before, after) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                step.round?.let { repo.recordRound(step.game, it, step.mode, step.rounds) }
                repo.recordGame(step.game, step.mode, step.games)
            }
        }
    }

    /** "Replace song" after a playback problem (the host's choice). */
    fun replaceUnplayable() {
        if (dispatch(GameAction.ReplaceUnplayable)) _replaced.update { it + 1 }
    }

    private companion object {
        const val PREFETCH = 2

        /** A Deezer preview's length. */
        const val PREVIEW_MS = 30_000L

        /** Automatic replacements per round before the host is asked (e.g. offline with only streamed songs). */
        const val MAX_AUTO_REPLACE = 3

        /** Missing-year songs looked up per batch; the next-song lookups get the queue in between. */
        const val CANDIDATE_BATCH = 10
    }

    private fun onAccepted(old: GameState?, new: GameState) {
        val revealNow = revealTimer.onState(new)
        if (new.phase == Phase.GAME_OVER) {
            audio.stop()
            return
        }
        AutoStep.after(new, revealNow)?.let {
            dispatch(it)
            return
        }
        val round = new.round ?: return
        if (round.song.songId != old?.round?.song?.songId) loadRound(new)
    }

    private fun loadRound(state: GameState) {
        val round = state.round ?: return
        val info = _session.value?.song(round.song.songId) ?: return
        loadedKey = keyOf(round.number, round.song.songId)
        val common = state.settings.common
        // A Deezer preview is a 30-second extract: no start offset, the clip limit within it (SPEC v2).
        val preview = info.streamed
        val request = TrackRequest(
            uri = info.uri, // resolved below
            mediaId = "round-${round.number}",
            placeholderTitle = context.getString(R.string.placeholder_title),
            placeholderSubtitle = context.getString(R.string.placeholder_round, round.number),
            startOffsetMs = if (preview) 0 else common.startOffsetSeconds * 1000L,
            clipLimitMs = common.clipLimitSeconds?.let { it * 1000L }?.let { if (preview) it.coerceAtMost(PREVIEW_MS) else it },
            durationMs = if (preview) PREVIEW_MS else info.durationMs,
            autoPlay = common.autoPlay,
        )
        if (round.number != replacedRound) {
            replacedRound = round.number
            _replaced.value = 0
        }
        _unplayable.value = false
        val key = loadedKey
        scope.launch {
            val playable = audioResolver.playableUri(info.uri)
            if (loadedKey != key) return@launch // the round moved on meanwhile
            if (playable == null) {
                onUnplayable()
                return@launch
            }
            audio.load(request.copy(uri = playable))
            // The next song's preview URL is fetched now, so the next round starts without waiting.
            state.pool.firstOrNull()?.let { next -> _session.value?.song(next.songId)?.let { audioResolver.prefetch(it.uri) } }
        }
        if (common.musicBrainzLookups) prefetch(state)
    }

    /**
     * While a round plays, look up the current song and the next 2 in the pool so reveal never waits on
     * the network, but only songs never checked before (DECISIONS.md D-048, D-064). A result that
     * arrives after its song's reveal is only cached.
     */
    private fun prefetch(state: GameState) {
        val ids = listOfNotNull(state.round?.song?.songId) + state.pool.take(PREFETCH).map { it.songId }
        prefetching.update { it + 1 }
        scope.launch {
            try {
                val songs = ids.mapNotNull { library.songEntity(it) }
                // One batched lookup for all of them (DECISIONS.md D-071).
                musicBrainz.lookupAll(songs.filter { !musicBrainz.checked(it) }) { song, result ->
                    if (result is LookupResult.Match) applyLookup(song.id, result.year)
                }
                songs.forEach(::fetchCover)
            } finally {
                prefetching.update { it - 1 }
            }
        }
    }

    private fun fetchCover(song: SongEntity) {
        scope.launch { if (coverArt.ensure(song)) onCover(song.id) }
    }

    /**
     * Background work for a running game: covers for the cards already dealt, and the search for
     * songs on the game's playlists that only lack a year. Each one never checked before is looked up;
     * on a match its year is confirmed and the song joins the undrawn pool (DECISIONS.md D-064).
     */
    private fun startBackgroundWork() {
        candidateJob?.cancel()
        val session = _session.value ?: return
        val c = session.state.settings.common
        if (!c.musicBrainzLookups || session.state.phase == Phase.GAME_OVER) return
        candidateJob = scope.launch {
            session.state.players.flatMap { it.timeline }.forEach { card -> library.songEntity(card.songId)?.let(::fetchCover) }
            // Catch up on covers of the game's songs that were matched but never got one (e.g. after an
            // interrupted run); songs without release info are left to "Load cover art" (D-072).
            for (id in session.songs.keys) {
                val song = library.songEntity(id) ?: continue
                val entry = musicBrainz.cached(song) ?: continue
                if (entry.year != null && !entry.coverChecked && (entry.releaseId != null || entry.releaseGroupId != null) && coverArt.coverFile(entry) == null) {
                    fetchCover(song)
                }
            }
            val candidates = library.missingYearCandidates(c.playlistIds, c.excludedPlaylistIds).filter { it.id !in session.songs }
            for (batch in candidates.chunked(CANDIDATE_BATCH)) {
                prefetching.first { it == 0 }
                val now = _session.value ?: return@launch
                if (now.state.phase == Phase.GAME_OVER) return@launch
                var offline = false
                musicBrainz.lookupAll(batch.filter { !musicBrainz.checked(it) }) { song, result ->
                    when (result) {
                        is LookupResult.Match -> addFoundSong(song)
                        LookupResult.NoMatch -> Unit
                        LookupResult.Skipped -> offline = true
                    }
                }
                if (offline) return@launch // offline or lookups off: try again next game
            }
        }
    }

    private suspend fun addFoundSong(song: SongEntity) {
        val lib = library.songNow(song.id) ?: return
        val year = lib.resolved.year ?: return
        val s = _session.value ?: return
        val c = s.state.settings.common
        val from = c.yearFrom
        val to = c.yearTo
        if ((from != null && year < from) || (to != null && year > to)) return
        val accepted = GameEngine.reduce(s.state, GameAction.AddToPool(Card(song.id, year))) as? ReduceResult.Accepted ?: return
        _session.value = s.copy(state = accepted.state, songs = s.songs + (song.id to SongInfo.from(lib)))
        persist()
        fetchCover(song)
    }

    private fun applyLookup(songId: Long, mbYear: Int) {
        val s = _session.value ?: return
        val info = s.song(songId) ?: return
        val base = info.baseYear
        if (info.manualYear != null || base == null || info.musicBrainzYear == mbYear) return
        val state = s.state
        val round = state.round
        val inPool = state.pool.any { it.songId == songId }
        val current = round != null && round.song.songId == songId && state.phase != Phase.REVEALED && state.phase != Phase.GAME_OVER
        if (!inPool && !current) return
        val year = mbYear // MusicBrainz wins over the tags (DECISIONS.md D-055)
        val updatedSongs = s.songs + (songId to info.copy(musicBrainzYear = mbYear))
        val newState = when (val r = GameEngine.reduce(state, GameAction.ApplyLookupYear(songId, year))) {
            is ReduceResult.Accepted -> r.state
            is ReduceResult.Rejected -> return
        }
        _session.value = s.copy(state = newState, songs = updatedSongs)
        persist()
    }
}

/** New game's pool: [streamed] songs need internet; [stats] for the overview below (feedback round 28). */
data class PoolInfo(val size: Int, val streamed: Int, val stats: LibraryStats)

/** What the app does by itself after an accepted action; pure, so it is tested without a phone (REVIEW.md T-03). */
internal object AutoStep {
    /** The next action, or null. [revealNow]: the reveal timer says auto-reveal is due (D-057, D-105). */
    fun after(state: GameState, revealNow: Boolean): GameAction? = when {
        state.phase == Phase.AWAITING_DRAW -> GameAction.Draw
        // Closest Year: once everyone has guessed, straight to the reveal (feedback round 11).
        state.mode == GameMode.CLOSEST_YEAR && state.phase == Phase.GUESSING && state.round?.currentGuesserId == null -> GameAction.Reveal
        // Auto-reveal: nobody (or only remote players who declined) can veto.
        state.phase == Phase.VETOING && revealNow -> GameAction.Reveal
        else -> null
    }
}

/**
 * What an accepted action adds to the saved players' stats (feedback round 47): [rounds] of round [round] when
 * Next committed it, [games] (profile id, won) when the game ended. Pure, so it is tested without a phone.
 */
internal data class StatsStep(
    val game: Long,
    val mode: GameMode,
    val round: Int?,
    val rounds: List<Pair<Long, RoundRecord>>,
    val games: List<Pair<Long, Boolean>>,
) {
    companion object {
        fun after(before: GameSession, after: GameState): StatsStep? {
            val old = before.state
            val profileOf = before.players.mapNotNull { p -> p.profileId?.let { p.id to it } }.toMap()
            if (profileOf.isEmpty()) return null
            val round = old.round
            val committed = old.phase == Phase.REVEALED && round != null && (after.phase == Phase.GAME_OVER || after.round?.number != round.number)
            val rounds = if (committed) RoundStats.of(old).mapNotNull { r -> profileOf[r.playerId]?.let { it to r } } else emptyList()
            val result = after.result.takeIf { old.phase != Phase.GAME_OVER }
            val games = result?.let { res -> after.players.mapNotNull { p -> profileOf[p.id]?.let { it to (p.id in res.winnerIds) } } }.orEmpty()
            if (rounds.isEmpty() && games.isEmpty()) return null
            return StatsStep(old.seed, old.mode, round?.number?.takeIf { rounds.isNotEmpty() }, rounds, games)
        }
    }
}
