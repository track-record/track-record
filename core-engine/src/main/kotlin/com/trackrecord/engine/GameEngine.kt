package com.trackrecord.engine

import kotlin.math.abs
import kotlin.random.Random

/**
 * The rule engine: a deterministic state machine for both modes.
 * Every disallowed action is rejected with a reason and leaves the state unchanged.
 */
object GameEngine {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 10

    fun reduce(state: GameState?, action: GameAction): ReduceResult {
        if (action is GameAction.StartGame) return start(action)
        if (state == null) return ReduceResult.Rejected(null, RejectReason.NO_GAME)
        return Reducer(state).apply(action)
    }

    /** Shuffles the pool deterministically: same songs and seed give the same order. */
    fun shufflePool(songs: List<Card>, seed: Long): List<Card> =
        songs.sortedBy { it.songId }.shuffled(Random(seed))

    private fun start(action: GameAction.StartGame): ReduceResult {
        fun reject(reason: RejectReason) = ReduceResult.Rejected(null, reason)
        val ids = action.playerIds
        if (ids.size < MIN_PLAYERS) return reject(RejectReason.TOO_FEW_PLAYERS)
        if (ids.size > MAX_PLAYERS) return reject(RejectReason.TOO_MANY_PLAYERS)
        if (ids.toSet().size != ids.size) return reject(RejectReason.DUPLICATE_PLAYER)
        if (action.settings.validate(action.mode).isNotEmpty()) return reject(RejectReason.INVALID_SETTINGS)

        val tl = action.settings.timeline
        // DECISIONS.md D-005: the pool must cover the starting cards plus one song to play.
        val needed = when (action.mode) {
            GameMode.TIMELINE -> ids.size * tl.startingCards + 1
            GameMode.CLOSEST_YEAR -> 1
        }
        if (action.songs.distinctBy { it.songId }.size < needed) return reject(RejectReason.POOL_TOO_SMALL)

        var pool = shufflePool(action.songs.distinctBy { it.songId }, action.seed)
        val settings = when (action.mode) {
            GameMode.TIMELINE -> action.settings
            GameMode.CLOSEST_YEAR -> action.settings.copy(
                closestYear = action.settings.closestYear.copy(lowestGuessableYear = guessFloor(pool.minOf { it.year })),
            )
        }
        val players = ids.map { id ->
            when (action.mode) {
                GameMode.TIMELINE -> {
                    // DECISIONS.md D-006: each seat in order takes its starting cards from the top.
                    val dealt = pool.take(tl.startingCards)
                    pool = pool.drop(tl.startingCards)
                    PlayerState(
                        id = id,
                        vetoes = tl.startingVetoes,
                        timeline = dealt.fold(emptyList()) { acc, card -> Slots.insertSorted(acc, card) },
                    )
                }
                GameMode.CLOSEST_YEAR -> PlayerState(id = id, skips = action.settings.closestYear.startingSkips)
            }
        }
        return ReduceResult.Accepted(
            GameState(
                mode = action.mode,
                settings = settings,
                seed = action.seed,
                currentYear = action.currentYear,
                players = players,
                pool = pool,
                phase = Phase.AWAITING_DRAW,
            ),
        )
    }

    /**
     * Lowest guessable year in Closest Year: the start of the decade of the earliest song, but never
     * above 2000 (DECISIONS.md D-066). 1984 → 1980, 2011 → 2000.
     */
    fun guessFloor(earliestYear: Int): Int = minOf(2000, earliestYear / 10 * 10)

    /** Seat index (0-based) that guesses first in the given 1-based Closest Year round. */
    fun firstGuesserSeat(round: Int, playerCount: Int, rotate: Boolean): Int =
        if (rotate) (round - 1).mod(playerCount) else 0

    /**
     * A Closest Year round's result for the song's [year] (feedback round 36). Guesses are in the order
     * made (with hidden guesses: locked in), so on a tie with [TieBreaker.FIRST_SCORES] the earlier one is ahead.
     * - Closest: the smallest |guess − year| wins; equally close guesses all win, or only the first.
     * - Rank: place p (0 = closest) earns [seats] − 1 − p points. Equally close guesses share the better
     *   place and the next one skips as many (1, 1, 3), or are placed in guess order. Removed players
     *   count as the last places, so the points stay the same all game.
     * - Distance: [ClosestYearSettings.distancePoints] minus the years off, at least 0; the closest win.
     */
    fun scoreRound(guesses: List<Guess>, year: Int, rules: ClosestYearSettings, seats: Int): RoundScore {
        if (guesses.isEmpty()) return RoundScore(emptyList(), emptyMap())
        fun off(g: Guess) = abs(g.year - year)
        val best = guesses.minOf(::off)
        val first = rules.tieBreaker == TieBreaker.FIRST_SCORES
        val closest = guesses.filter { off(it) == best }.map { it.playerId }
        return when (rules.scoring) {
            ClosestScoring.CLOSEST -> RoundScore(if (first) closest.take(1) else closest, emptyMap())
            ClosestScoring.RANK -> {
                // Stable: equally close guesses keep the order they were made in.
                val ranked = guesses.sortedBy(::off)
                val points = ranked.withIndex().associate { (i, g) ->
                    val place = if (first) i else ranked.count { off(it) < off(g) }
                    g.playerId to (seats - 1 - place)
                }
                RoundScore(if (first) closest.take(1) else closest, points)
            }
            ClosestScoring.DISTANCE -> {
                val points = guesses.associate { it.playerId to (rules.distancePoints - off(it)).coerceAtLeast(0) }
                RoundScore(if (rules.distancePoints - best > 0) closest else emptyList(), points)
            }
        }
    }

    /** A player's score: cards (Timeline), wins (Closest) or points (Rank, Distance). */
    fun score(state: GameState, player: PlayerState): Int = when {
        state.mode == GameMode.TIMELINE -> player.timeline.size
        state.settings.closestYear.scoring == ClosestScoring.CLOSEST -> player.wins
        else -> player.points
    }

    /** The score that wins the game. */
    fun target(state: GameState): Int = when (state.mode) {
        GameMode.TIMELINE -> state.settings.timeline.cardsToWin
        GameMode.CLOSEST_YEAR -> state.settings.closestYear.target(state.seats)
    }

    /** Timeline resolution: active slot first, then vetoes in the order placed. */
    fun resolveTimeline(state: GameState, round: RoundState): TimelineOutcome {
        val active = state.activePlayer
        val y = round.song.year
        val locked = round.lockedSlot
        if (locked != null && Slots.isValid(active.timeline, locked, y)) {
            return TimelineOutcome.Kept(active.id, locked)
        }
        val veto = round.vetoes.firstOrNull { Slots.isValid(active.timeline, it.slot, y) }
            ?: return TimelineOutcome.Discarded
        val vetoer = state.player(veto.playerId)!!
        return TimelineOutcome.Stolen(vetoer.id, Slots.sortedInsertIndex(vetoer.timeline, y))
    }

    /**
     * Players other than the active one who could still place a veto, in seat order: they have a veto
     * left, haven't reached the per-song limit, and a free selectable gap exists.
     */
    fun vetoEligible(state: GameState): List<String> {
        val round = state.round ?: return emptyList()
        if (state.mode != GameMode.TIMELINE) return emptyList()
        val timeline = state.activePlayer.timeline
        val taken = round.vetoes.map { it.slot }.toSet() + listOfNotNull(round.lockedSlot)
        val freeGap = (0..timeline.size).any { Slots.isSelectable(timeline, it) && it !in taken }
        if (!freeGap) return emptyList()
        val perSong = state.settings.timeline.vetoesPerPlayerPerSong
        return state.players.filter { p ->
            p.id != state.activePlayer.id && p.vetoes > 0 && round.vetoes.count { it.playerId == p.id } < perSong
        }.map { it.id }
    }

    /** Whether any player other than the active one could still place a veto (see [vetoEligible]). */
    fun canAnyoneVeto(state: GameState): Boolean = vetoEligible(state).isNotEmpty()

    /**
     * Timeline: who may be marked as having named song and artist, in calling order: the active player,
     * then vetoers in veto order, then (everyone may call) the other players in seat order.
     */
    fun namingCandidates(state: GameState): List<String> {
        val round = state.round ?: return emptyList()
        val active = state.activePlayer.id
        val vetoers = round.vetoes.map { it.playerId }.distinct()
        return when (state.settings.timeline.namingCallers) {
            NamingCallers.ACTIVE -> listOf(active)
            NamingCallers.VETOERS -> (listOf(active) + vetoers).distinct()
            NamingCallers.ALL -> (listOf(active) + vetoers + state.players.map { it.id }).distinct()
        }
    }

    /** Players with the highest [score]; ties are shared. Removed players don't win. */
    fun leaders(state: GameState): List<String> = leaders(state, state.players)

    fun leaders(state: GameState, among: List<PlayerState>): List<String> {
        val best = among.maxOf { score(state, it) }
        return among.filter { score(state, it) == best }.map { it.id }
    }
}

/** A Closest Year round's [winners] and, in Rank and Distance scoring, the [points] each guess earns. */
data class RoundScore(val winners: List<String>, val points: Map<String, Int>)

private class Reducer(private val s: GameState) {
    private val tl get() = s.settings.timeline
    private val cy get() = s.settings.closestYear

    private fun ok(state: GameState) = ReduceResult.Accepted(state.copy(lastPurchase = null, lastSkip = null))
    private fun reject(reason: RejectReason) = ReduceResult.Rejected(s, reason)

    fun apply(action: GameAction): ReduceResult {
        if (s.phase == Phase.GAME_OVER) return reject(RejectReason.WRONG_PHASE)
        return when (action) {
            is GameAction.StartGame -> error("handled by GameEngine")
            GameAction.Draw -> draw()
            is GameAction.SelectSlot -> timeline { selectSlot(action.slot) }
            GameAction.Skip -> if (s.mode == GameMode.TIMELINE) skip() else skipClosest()
            GameAction.LockIn -> timeline { lockIn() }
            is GameAction.PlaceVeto -> timeline { placeVeto(action.playerId, action.slot) }
            GameAction.UndoVeto -> timeline { undoVeto() }
            is GameAction.SetNamed -> timeline { setNamed(action.named) }
            is GameAction.BuyCard -> if (s.mode == GameMode.TIMELINE) buyCard(action.playerId) else buyClosest(action.playerId)
            is GameAction.AdjustVetoes -> timeline { adjustVetoes(action.playerId, action.delta) }
            is GameAction.SubmitGuess -> closest { submitGuess(action.year, action.playerId) }
            GameAction.UndoGuess -> closest { undoGuess() }
            is GameAction.SetNamedBy -> if (s.mode == GameMode.TIMELINE) setTimelineNamer(action.playerId, action.named) else setNamedBy(action.playerId, action.named)
            is GameAction.AdjustSkips -> closest { adjustSkips(action.playerId, action.delta) }
            GameAction.Reveal -> reveal()
            is GameAction.RemovePlayer -> removePlayer(action.playerId)
            is GameAction.CorrectYear -> correctYear(action.year)
            GameAction.Next -> next()
            is GameAction.SetClipLimit -> setClipLimit(action.seconds)
            is GameAction.SetStartOffset -> setStartOffset(action.seconds)
            is GameAction.ApplyLookupYear -> applyLookupYear(action.songId, action.year)
            is GameAction.AddToPool -> addToPool(action.card)
            GameAction.ReplaceUnplayable -> replaceUnplayable()
        }
    }

    private inline fun timeline(block: () -> ReduceResult) =
        if (s.mode == GameMode.TIMELINE) block() else reject(RejectReason.WRONG_MODE)

    private inline fun closest(block: () -> ReduceResult) =
        if (s.mode == GameMode.CLOSEST_YEAR) block() else reject(RejectReason.WRONG_MODE)

    private fun gameOver(winners: List<String>, reason: EndReason, state: GameState = s) =
        state.copy(phase = Phase.GAME_OVER, round = null, result = GameResult(winners, reason))

    private fun updatePlayer(state: GameState, id: String, f: (PlayerState) -> PlayerState) =
        state.copy(players = state.players.map { if (it.id == id) f(it) else it })

    // ---- Both modes ----

    private fun draw(): ReduceResult {
        if (s.phase != Phase.AWAITING_DRAW) return reject(RejectReason.WRONG_PHASE)
        val song = s.pool.firstOrNull()
            ?: return ok(gameOver(GameEngine.leaders(s), EndReason.POOL_EMPTY))
        val number = s.roundsPlayed + 1
        val round = when (s.mode) {
            GameMode.TIMELINE -> RoundState(number = number, song = song)
            GameMode.CLOSEST_YEAR -> {
                val first = GameEngine.firstGuesserSeat(number, s.players.size, cy.rotateFirstGuesser)
                val order = s.players.indices.map { s.players[(first + it) % s.players.size].id }
                RoundState(number = number, song = song, guessOrder = order)
            }
        }
        return ok(
            s.copy(
                pool = s.pool.drop(1),
                roundsPlayed = number,
                round = round,
                phase = if (s.mode == GameMode.TIMELINE) Phase.PLACING else Phase.GUESSING,
            ),
        )
    }

    private fun reveal(): ReduceResult {
        val round = s.round
        return when (s.mode) {
            GameMode.TIMELINE -> {
                if (s.phase != Phase.VETOING || round == null) return reject(RejectReason.WRONG_PHASE)
                val outcome = GameEngine.resolveTimeline(s, round)
                ok(s.copy(phase = Phase.REVEALED, round = round.copy(timelineOutcome = outcome)))
            }
            GameMode.CLOSEST_YEAR -> {
                if (s.phase != Phase.GUESSING || round == null) return reject(RejectReason.WRONG_PHASE)
                if (round.guesses.size < round.guessOrder.size) return reject(RejectReason.GUESSES_INCOMPLETE)
                ok(revealClosest(round))
            }
        }
    }

    private fun revealClosest(round: RoundState): GameState = s.copy(phase = Phase.REVEALED, round = scored(round, s))

    /** [round] with its winners and points for its song's year. */
    private fun scored(round: RoundState, state: GameState): RoundState {
        val result = GameEngine.scoreRound(round.guesses, round.song.year, state.settings.closestYear, state.seats)
        return round.copy(winnerIds = result.winners, points = result.points)
    }

    private fun correctYear(year: Int): ReduceResult {
        val round = s.round
        if (s.phase != Phase.REVEALED || round == null) return reject(RejectReason.WRONG_PHASE)
        if (year !in YEAR_MIN..s.currentYear) return reject(RejectReason.YEAR_OUT_OF_RANGE)
        val corrected = round.copy(song = round.song.copy(year = year), yearCorrected = true)
        val recomputed = when (s.mode) {
            GameMode.TIMELINE -> corrected.copy(timelineOutcome = GameEngine.resolveTimeline(s, corrected))
            GameMode.CLOSEST_YEAR -> scored(corrected, s)
        }
        return ok(s.copy(round = recomputed))
    }

    private fun next(): ReduceResult {
        val round = s.round
        if (s.phase != Phase.REVEALED || round == null) return reject(RejectReason.WRONG_PHASE)
        return when (s.mode) {
            GameMode.TIMELINE -> ok(commitTimeline(round))
            GameMode.CLOSEST_YEAR -> ok(commitClosest(round))
        }
    }

    private fun setClipLimit(seconds: Int?): ReduceResult {
        val common = s.settings.common.copy(clipLimitSeconds = seconds)
        if (common.validate().isNotEmpty()) return reject(RejectReason.INVALID_SETTINGS)
        return ok(s.copy(settings = s.settings.copy(common = common)))
    }

    private fun setStartOffset(seconds: Int): ReduceResult {
        val common = s.settings.common.copy(startOffsetSeconds = seconds)
        if (common.validate().isNotEmpty()) return reject(RejectReason.INVALID_SETTINGS)
        return ok(s.copy(settings = s.settings.copy(common = common)))
    }

    private fun applyLookupYear(songId: Long, year: Int): ReduceResult {
        if (year !in YEAR_MIN..s.currentYear) return reject(RejectReason.YEAR_OUT_OF_RANGE)
        val round = s.round
        if (round != null && round.song.songId == songId && s.phase != Phase.REVEALED) {
            return ok(withFloorFor(year, s.copy(round = round.copy(song = round.song.copy(year = year)))))
        }
        if (s.pool.none { it.songId == songId }) return reject(RejectReason.SONG_NOT_FOUND)
        return ok(withFloorFor(year, s.copy(pool = s.pool.map { if (it.songId == songId) it.copy(year = year) else it })))
    }

    private fun addToPool(card: Card): ReduceResult {
        if (card.year !in YEAR_MIN..s.currentYear) return reject(RejectReason.YEAR_OUT_OF_RANGE)
        val inGame = s.pool + s.discarded + listOfNotNull(s.round?.song) + (s.players + s.removed).flatMap { it.timeline + it.collection }
        if (inGame.any { it.songId == card.songId }) return reject(RejectReason.SONG_ALREADY_IN_GAME)
        val position = Random(s.seed xor card.songId).nextInt(s.pool.size + 1)
        val pool = s.pool.toMutableList().apply { add(position, card) }
        return ok(withFloorFor(card.year, s.copy(pool = pool)))
    }

    /** In Closest Year the lowest guessable year moves down if a song's year falls below it. */
    private fun withFloorFor(year: Int, state: GameState): GameState {
        if (state.mode != GameMode.CLOSEST_YEAR) return state
        val floor = GameEngine.guessFloor(year)
        val cy = state.settings.closestYear
        if (floor >= cy.lowestGuessableYear) return state
        return state.copy(settings = state.settings.copy(closestYear = cy.copy(lowestGuessableYear = floor)))
    }

    /** Feedback round 36: see [GameAction.RemovePlayer]. */
    private fun removePlayer(playerId: String): ReduceResult {
        val seat = s.seatOf(playerId)
        if (seat < 0) return reject(RejectReason.UNKNOWN_PLAYER)
        if (s.players.size <= GameEngine.MIN_PLAYERS) return reject(RejectReason.TOO_FEW_PLAYERS)
        val left = s.copy(players = s.players.filter { it.id != playerId }, removed = s.removed + s.players[seat])
        return ok(
            when (s.mode) {
                GameMode.TIMELINE -> leaveTimeline(left, seat, playerId)
                GameMode.CLOSEST_YEAR -> leaveClosest(left, playerId)
            },
        )
    }

    /**
     * Timeline without the player at [seat]. Someone else's turn goes on without their vetoes (after the
     * reveal the card may then go to the next valid veto). Their own turn ends: before the reveal the
     * song leaves the game and vetoes are handed back; after it, a stolen card still goes to the vetoer.
     */
    private fun leaveTimeline(state: GameState, seat: Int, playerId: String): GameState {
        val round = state.round
        if (seat != s.activeSeat) {
            val activeSeat = if (seat < s.activeSeat) s.activeSeat - 1 else s.activeSeat
            if (round == null) return state.copy(activeSeat = activeSeat)
            val without = round.copy(vetoes = round.vetoes.filter { it.playerId != playerId }, namedById = round.namedById.takeIf { it != playerId })
            val next = state.copy(activeSeat = activeSeat, round = without)
            return if (s.phase == Phase.REVEALED) next.copy(round = without.copy(timelineOutcome = GameEngine.resolveTimeline(next, without))) else next
        }
        val nextSeat = seat % state.players.size
        if (round == null) return state.copy(activeSeat = nextSeat)
        var next = state
        val outcome = round.timelineOutcome
        if (s.phase == Phase.REVEALED && outcome is TimelineOutcome.Stolen) {
            next = updatePlayer(next, outcome.playerId) { it.copy(timeline = Slots.insertAt(it.timeline, outcome.slot, round.song)) }
        } else {
            next = next.copy(discarded = next.discarded + round.song)
        }
        if (s.phase == Phase.REVEALED) {
            val namer = round.namedById?.takeIf { it != playerId }
            if (tl.namingBonus && namer != null) next = updatePlayer(next, namer) { it.copy(vetoes = minOf(it.vetoes + 1, tl.maxVetoes)) }
        } else {
            round.vetoes.forEach { v -> next = updatePlayer(next, v.playerId) { it.copy(vetoes = it.vetoes + 1) } }
        }
        val winners = next.players.filter { it.timeline.size >= tl.cardsToWin }.map { it.id }
        if (winners.isNotEmpty()) return gameOver(winners, EndReason.TARGET_REACHED, next)
        return next.copy(phase = Phase.AWAITING_DRAW, round = null, activeSeat = nextSeat)
    }

    /** Closest Year without [playerId]: the round goes on without their guess; after the reveal it is scored again. */
    private fun leaveClosest(state: GameState, playerId: String): GameState {
        val round = state.round ?: return state
        val without = round.copy(
            guessOrder = round.guessOrder - playerId,
            guesses = round.guesses.filter { it.playerId != playerId },
            namedById = round.namedById.takeIf { it != playerId },
        )
        return state.copy(round = if (s.phase == Phase.REVEALED) scored(without, state) else without)
    }

    // ---- Timeline mode ----

    private fun selectSlot(slot: Int): ReduceResult {
        val round = s.round
        if (s.phase != Phase.PLACING || round == null) return reject(RejectReason.WRONG_PHASE)
        if (!Slots.isSelectable(s.activePlayer.timeline, slot)) return reject(RejectReason.INVALID_SLOT)
        return ok(s.copy(round = round.copy(selectedSlot = slot)))
    }

    private fun skip(): ReduceResult {
        val round = s.round
        if (s.phase != Phase.PLACING || round == null) return reject(RejectReason.WRONG_PHASE)
        val cost = tl.skipCost ?: return reject(RejectReason.FEATURE_DISABLED)
        if (s.activePlayer.vetoes < cost) return reject(RejectReason.NOT_ENOUGH_VETOES)
        // DECISIONS.md D-007: skipping needs a replacement song; with an empty pool it is rejected.
        val replacement = s.pool.firstOrNull() ?: return reject(RejectReason.POOL_EMPTY)
        val paid = updatePlayer(s, s.activePlayer.id) { it.copy(vetoes = it.vetoes - cost) }
        return ReduceResult.Accepted(
            paid.copy(
                pool = s.pool.drop(1),
                discarded = s.discarded + round.song,
                round = RoundState(number = round.number, song = replacement),
                lastPurchase = null,
                lastSkip = Skipped(s.activePlayer.id, round.song),
            ),
        )
    }

    private fun replaceUnplayable(): ReduceResult {
        val round = s.round
        val open = when (s.mode) {
            GameMode.TIMELINE -> s.phase == Phase.PLACING
            GameMode.CLOSEST_YEAR -> s.phase == Phase.GUESSING
        }
        if (!open || round == null) return reject(RejectReason.WRONG_PHASE)
        val replacement = s.pool.firstOrNull() ?: return reject(RejectReason.POOL_EMPTY)
        return ok(
            s.copy(
                pool = s.pool.drop(1) + round.song,
                round = RoundState(number = round.number, song = replacement, guessOrder = round.guessOrder),
            ),
        )
    }

    private fun lockIn(): ReduceResult {
        val round = s.round
        if (s.phase != Phase.PLACING || round == null) return reject(RejectReason.WRONG_PHASE)
        val slot = round.selectedSlot ?: return reject(RejectReason.NO_SLOT_SELECTED)
        return ok(s.copy(phase = Phase.VETOING, round = round.copy(lockedSlot = slot)))
    }

    private fun placeVeto(playerId: String, slot: Int): ReduceResult {
        val round = s.round
        if (s.phase != Phase.VETOING || round == null) return reject(RejectReason.WRONG_PHASE)
        val player = s.player(playerId) ?: return reject(RejectReason.UNKNOWN_PLAYER)
        if (player.id == s.activePlayer.id) return reject(RejectReason.ACTIVE_PLAYER_CANNOT_VETO)
        if (!Slots.isSelectable(s.activePlayer.timeline, slot)) return reject(RejectReason.INVALID_SLOT)
        if (slot == round.lockedSlot || round.vetoes.any { it.slot == slot }) return reject(RejectReason.SLOT_TAKEN)
        if (player.vetoes < 1) return reject(RejectReason.NOT_ENOUGH_VETOES)
        if (round.vetoes.count { it.playerId == playerId } >= tl.vetoesPerPlayerPerSong) {
            return reject(RejectReason.VETO_LIMIT_REACHED)
        }
        val paid = updatePlayer(s, playerId) { it.copy(vetoes = it.vetoes - 1) }
        return ok(paid.copy(round = round.copy(vetoes = round.vetoes + Veto(playerId, slot))))
    }

    private fun undoVeto(): ReduceResult {
        val round = s.round
        if (s.phase != Phase.VETOING || round == null) return reject(RejectReason.WRONG_PHASE)
        val last = round.vetoes.lastOrNull() ?: return reject(RejectReason.NOTHING_TO_UNDO)
        val refunded = updatePlayer(s, last.playerId) { it.copy(vetoes = it.vetoes + 1) }
        return ok(refunded.copy(round = round.copy(vetoes = round.vetoes.dropLast(1))))
    }

    /** Manual correction from the scoreboard, e.g. a mis-ticked naming bonus (DECISIONS.md D-058). */
    private fun adjustVetoes(playerId: String, delta: Int): ReduceResult {
        val player = s.player(playerId) ?: return reject(RejectReason.UNKNOWN_PLAYER)
        val target = player.vetoes + delta
        if (target < 0) return reject(RejectReason.NOT_ENOUGH_VETOES)
        if (target > tl.maxVetoes) return reject(RejectReason.VETOES_AT_MAX)
        return ok(updatePlayer(s, playerId) { it.copy(vetoes = target) })
    }

    /** The active player named song and artist (or not). */
    private fun setNamed(named: Boolean): ReduceResult = setTimelineNamer(s.activePlayer.id, named)

    /** Timeline naming (feedback round 19): one namer among those [TimelineSettings.namingCallers] allows. */
    private fun setTimelineNamer(playerId: String, named: Boolean): ReduceResult {
        val round = s.round
        if (s.phase != Phase.REVEALED || round == null) return reject(RejectReason.WRONG_PHASE)
        if (!tl.namingBonus) return reject(RejectReason.FEATURE_DISABLED)
        if (s.player(playerId) == null) return reject(RejectReason.UNKNOWN_PLAYER)
        if (named && playerId !in GameEngine.namingCandidates(s)) return reject(RejectReason.NOT_ALLOWED_TO_NAME)
        val namer = if (named) playerId else round.namedById.takeIf { it != playerId }
        return ok(s.copy(round = round.copy(namedById = namer)))
    }

    private fun buyCard(playerId: String): ReduceResult {
        if (s.phase != Phase.AWAITING_DRAW && s.phase != Phase.PLACING) return reject(RejectReason.WRONG_PHASE)
        val cost = tl.buyCost ?: return reject(RejectReason.FEATURE_DISABLED)
        val player = s.player(playerId) ?: return reject(RejectReason.UNKNOWN_PLAYER)
        if (player.vetoes < cost) return reject(RejectReason.NOT_ENOUGH_VETOES)
        // DECISIONS.md D-007: buying needs a song; with an empty pool it is rejected.
        val card = s.pool.firstOrNull() ?: return reject(RejectReason.POOL_EMPTY)
        var next = updatePlayer(s, playerId) {
            it.copy(vetoes = it.vetoes - cost, timeline = Slots.insertSorted(it.timeline, card))
        }.copy(pool = s.pool.drop(1))
        // DECISIONS.md D-008: the buyer's slot indices shift, so an active buyer's selection is cleared.
        val round = next.round
        if (round != null && playerId == s.activePlayer.id) {
            next = next.copy(round = round.copy(selectedSlot = null))
        }
        val buyer = next.player(playerId)!!
        if (buyer.timeline.size >= tl.cardsToWin) {
            next = gameOver(listOf(playerId), EndReason.TARGET_REACHED, next)
        }
        return ReduceResult.Accepted(next.copy(lastPurchase = Purchase(playerId, card), lastSkip = null))
    }

    private fun commitTimeline(round: RoundState): GameState {
        var next = s
        when (val outcome = round.timelineOutcome!!) {
            is TimelineOutcome.Kept -> next = updatePlayer(next, outcome.playerId) {
                it.copy(timeline = Slots.insertAt(it.timeline, outcome.slot, round.song))
            }
            is TimelineOutcome.Stolen -> next = updatePlayer(next, outcome.playerId) {
                it.copy(timeline = Slots.insertAt(it.timeline, outcome.slot, round.song))
            }
            TimelineOutcome.Discarded -> next = next.copy(discarded = next.discarded + round.song)
        }
        // The namer gets a veto (a vetoer: their veto back), up to the max (feedback round 19).
        val namer = round.namedById
        if (tl.namingBonus && namer != null) {
            next = updatePlayer(next, namer) {
                it.copy(vetoes = minOf(it.vetoes + 1, tl.maxVetoes))
            }
        }
        val winners = next.players.filter { it.timeline.size >= tl.cardsToWin }.map { it.id }
        if (winners.isNotEmpty()) return gameOver(winners, EndReason.TARGET_REACHED, next)
        return next.copy(
            phase = Phase.AWAITING_DRAW,
            round = null,
            activeSeat = (s.activeSeat + 1) % s.players.size,
        )
    }

    // ---- Closest Year mode ----

    private fun submitGuess(year: Int, playerId: String?): ReduceResult {
        val round = s.round
        if (s.phase != Phase.GUESSING || round == null) return reject(RejectReason.WRONG_PHASE)
        val waiting = round.waitingFor
        if (waiting.isEmpty()) return reject(RejectReason.ALL_GUESSED)
        // In turn, or with hidden guesses anyone who hasn't guessed (feedback round 36).
        val guesser = playerId ?: waiting.first()
        if (s.player(guesser) == null) return reject(RejectReason.UNKNOWN_PLAYER)
        if (guesser !in waiting || (!cy.hiddenGuesses && guesser != waiting.first())) return reject(RejectReason.NOT_A_GUESSER)
        if (year !in cy.lowestGuessableYear..s.currentYear) return reject(RejectReason.YEAR_OUT_OF_RANGE)
        if (cy.guessesDistinct && round.guesses.any { it.year == year }) return reject(RejectReason.DUPLICATE_GUESS)
        val updated = round.copy(guesses = round.guesses + Guess(guesser, year))
        if (cy.exactEndsRound && year == round.song.year) {
            return ok(revealClosest(updated))
        }
        return ok(s.copy(round = updated))
    }

    /**
     * Closest Year skip (feedback round 11): before anyone guessed, the first guesser spends a skip;
     * the song leaves the game and the next one takes its place in the same round, same guess order.
     */
    private fun skipClosest(): ReduceResult {
        val round = s.round
        if (s.phase != Phase.GUESSING || round == null || round.guesses.isNotEmpty()) return reject(RejectReason.WRONG_PHASE)
        val guesser = round.guessOrder.firstOrNull()?.let { s.player(it) } ?: return reject(RejectReason.WRONG_PHASE)
        if (guesser.skips < 1) return reject(RejectReason.NOT_ENOUGH_SKIPS)
        val replacement = s.pool.firstOrNull() ?: return reject(RejectReason.POOL_EMPTY)
        val paid = updatePlayer(s, guesser.id) { it.copy(skips = it.skips - 1) }
        return ReduceResult.Accepted(
            paid.copy(
                pool = s.pool.drop(1),
                discarded = s.discarded + round.song,
                round = RoundState(number = round.number, song = replacement, guessOrder = round.guessOrder),
                lastPurchase = null,
                lastSkip = Skipped(guesser.id, round.song),
            ),
        )
    }

    /**
     * Closest Year: buy a card for [ClosestYearSettings.buyCost] skips (feedback round 27), any time before
     * the reveal. The next pool song joins the buyer's cards and counts towards the target like a win; in
     * Rank and Distance it is worth a perfect round's points and only shown (feedback round 36).
     */
    private fun buyClosest(playerId: String): ReduceResult {
        if (s.phase != Phase.AWAITING_DRAW && s.phase != Phase.GUESSING) return reject(RejectReason.WRONG_PHASE)
        val cost = cy.buyCost ?: return reject(RejectReason.FEATURE_DISABLED)
        val player = s.player(playerId) ?: return reject(RejectReason.UNKNOWN_PLAYER)
        if (player.skips < cost) return reject(RejectReason.NOT_ENOUGH_SKIPS)
        val card = s.pool.firstOrNull() ?: return reject(RejectReason.POOL_EMPTY)
        var next = updatePlayer(s, playerId) {
            if (cy.scoring == ClosestScoring.CLOSEST) {
                it.copy(skips = it.skips - cost, wins = it.wins + 1, collection = it.collection + card)
            } else {
                it.copy(skips = it.skips - cost, points = it.points + cy.perfectRound(s.seats))
            }
        }.copy(pool = s.pool.drop(1))
        if (cy.scoring != ClosestScoring.CLOSEST) next = next.copy(discarded = next.discarded + card)
        if (GameEngine.score(next, next.player(playerId)!!) >= GameEngine.target(next)) next = gameOver(listOf(playerId), EndReason.TARGET_REACHED, next)
        return ReduceResult.Accepted(next.copy(lastPurchase = Purchase(playerId, card), lastSkip = null))
    }

    private fun setNamedBy(playerId: String, named: Boolean): ReduceResult {
        val round = s.round
        if (s.phase != Phase.REVEALED || round == null) return reject(RejectReason.WRONG_PHASE)
        if (!cy.namingEarnsSkip) return reject(RejectReason.FEATURE_DISABLED)
        if (s.player(playerId) == null) return reject(RejectReason.UNKNOWN_PLAYER)
        // A round ended early by an exact guess: only those who guessed had a turn (feedback round 13).
        if (named && round.guesses.none { it.playerId == playerId }) return reject(RejectReason.NOT_A_GUESSER)
        val namedById = if (named) playerId else round.namedById.takeIf { it != playerId }
        return ok(s.copy(round = round.copy(namedById = namedById)))
    }

    /** Manual correction from the scoreboard, like vetoes (feedback round 12), within the max held (round 13). */
    private fun adjustSkips(playerId: String, delta: Int): ReduceResult {
        val player = s.player(playerId) ?: return reject(RejectReason.UNKNOWN_PLAYER)
        val target = player.skips + delta
        if (target < 0) return reject(RejectReason.NOT_ENOUGH_SKIPS)
        val max = cy.maxSkips
        if (max != null && delta > 0 && target > max) return reject(RejectReason.SKIPS_AT_MAX)
        return ok(updatePlayer(s, playerId) { it.copy(skips = target) })
    }

    private fun undoGuess(): ReduceResult {
        val round = s.round
        if (s.phase != Phase.GUESSING || round == null) return reject(RejectReason.WRONG_PHASE)
        if (round.guesses.isEmpty()) return reject(RejectReason.NOTHING_TO_UNDO)
        return ok(s.copy(round = round.copy(guesses = round.guesses.dropLast(1))))
    }

    private fun commitClosest(round: RoundState): GameState {
        var next = s
        if (cy.scoring == ClosestScoring.CLOSEST && round.winnerIds.isNotEmpty()) {
            // On a tie that both score, each winner gets the win and the song (feedback round 36).
            round.winnerIds.forEach { id -> next = updatePlayer(next, id) { it.copy(wins = it.wins + 1, collection = it.collection + round.song) } }
        } else {
            next = next.copy(discarded = next.discarded + round.song)
            round.points.forEach { (id, points) -> next = updatePlayer(next, id) { it.copy(points = it.points + points) } }
        }
        // Naming song and artist earns a skip, whoever won the round (feedback rounds 11 and 12).
        if (cy.namingEarnsSkip) {
            val max = cy.maxSkips ?: Int.MAX_VALUE
            round.namedById?.let { id -> next = updatePlayer(next, id) { it.copy(skips = minOf(it.skips + 1, max)) } }
        }
        // Whoever reached the target; if several did, the highest score wins (shared on a tie).
        val target = GameEngine.target(next)
        val reached = next.players.filter { GameEngine.score(next, it) >= target }
        if (reached.isNotEmpty()) return gameOver(GameEngine.leaders(next, reached), EndReason.TARGET_REACHED, next)
        return next.copy(phase = Phase.AWAITING_DRAW, round = null)
    }

    companion object {
        const val YEAR_MIN = 1900
    }
}
