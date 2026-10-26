package com.trackrecord.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class GameMode { TIMELINE, CLOSEST_YEAR }

/**
 * Game phases. Timeline: AWAITING_DRAW → PLACING → VETOING → REVEALED → (Next) AWAITING_DRAW.
 * Closest Year: AWAITING_DRAW → GUESSING → REVEALED → (Next) AWAITING_DRAW. Both end in GAME_OVER.
 * AWAITING_DRAW is the spec's "Next" phase: the previous round is committed, the next song not yet drawn.
 */
@Serializable
enum class Phase { AWAITING_DRAW, PLACING, VETOING, GUESSING, REVEALED, GAME_OVER }

/** A song as the engine sees it: an id and the effective year it is judged against. */
@Serializable
data class Card(val songId: Long, val year: Int)

@Serializable
data class PlayerState(
    val id: String,
    val vetoes: Int = 0,
    /** Timeline mode: revealed cards, sorted by year. */
    val timeline: List<Card> = emptyList(),
    /** Closest Year mode: round wins. */
    val wins: Int = 0,
    /** Closest Year mode: songs won. */
    val collection: List<Card> = emptyList(),
    /** Closest Year mode: skips left. */
    val skips: Int = 0,
    /** Closest Year, Rank and Distance scoring: points (feedback round 36). */
    val points: Int = 0,
)

@Serializable
data class Veto(val playerId: String, val slot: Int)

@Serializable
data class Guess(val playerId: String, val year: Int)

@Serializable
sealed interface TimelineOutcome {
    /** The active player's slot was valid; the card goes there. */
    @Serializable
    @SerialName("kept")
    data class Kept(val playerId: String, val slot: Int) : TimelineOutcome

    /** The earliest valid veto wins; the card goes into the vetoer's own timeline. */
    @Serializable
    @SerialName("stolen")
    data class Stolen(val playerId: String, val slot: Int) : TimelineOutcome

    @Serializable
    @SerialName("discarded")
    data object Discarded : TimelineOutcome
}

@Serializable
data class RoundState(
    /** 1-based round number. */
    val number: Int,
    val song: Card,
    // Timeline mode
    val selectedSlot: Int? = null,
    val lockedSlot: Int? = null,
    val vetoes: List<Veto> = emptyList(),
    val timelineOutcome: TimelineOutcome? = null,
    // Closest Year mode
    val guessOrder: List<String> = emptyList(),
    val guesses: List<Guess> = emptyList(),
    /** Closest Year outcome, set at reveal: the round's winners (several on a tie that both score, feedback round 36). */
    val winnerIds: List<String> = emptyList(),
    /** Closest Year, Rank and Distance scoring: the points each guess earns, set at reveal. */
    val points: Map<String, Int> = emptyMap(),
    /** True once "Wrong year?" changed the song's year in this round. */
    val yearCorrected: Boolean = false,
    /** The player who named song and artist (marked after reveal; one at most), in both modes. */
    val namedById: String? = null,
) {
    /** Closest Year: players who haven't guessed yet, in guess order. */
    val waitingFor: List<String>
        get() = guessOrder.filter { id -> guesses.none { it.playerId == id } }

    /** Whose turn it is to guess; with hidden guesses everyone in [waitingFor] may guess, this is just the first. */
    val currentGuesserId: String?
        get() = waitingFor.firstOrNull()
}

@Serializable
data class Purchase(val playerId: String, val card: Card)

/** A song [playerId] skipped, shown to everyone like a bought card (feedback round 27). */
@Serializable
data class Skipped(val playerId: String, val card: Card)

@Serializable
enum class EndReason { TARGET_REACHED, POOL_EMPTY }

@Serializable
data class GameResult(val winnerIds: List<String>, val reason: EndReason)

@Serializable
data class GameState(
    val mode: GameMode,
    val settings: GameSettings,
    val seed: Long,
    /** The current calendar year, frozen at game start; upper bound for guesses and edits. */
    val currentYear: Int,
    /** Players in seating order. */
    val players: List<PlayerState>,
    /**
     * Players removed mid-game, in removal order (feedback round 36): shown last and greyed out, they no
     * longer play; Rank scoring still counts them as places.
     */
    val removed: List<PlayerState> = emptyList(),
    /** Undrawn songs, top of the pool first. */
    val pool: List<Card>,
    val phase: Phase,
    /** Timeline mode: seat index (0-based) of the active player. */
    val activeSeat: Int = 0,
    /** Number of rounds drawn so far. */
    val roundsPlayed: Int = 0,
    val round: RoundState? = null,
    /** Songs that left the game without going to a player (discarded or skipped). */
    val discarded: List<Card> = emptyList(),
    /** Set by a successful BuyCard so the UI can reveal the bought card; cleared by the next action. */
    val lastPurchase: Purchase? = null,
    /** Set by a successful Skip so the UI can show the skipped song; cleared by the next action. */
    val lastSkip: Skipped? = null,
    val result: GameResult? = null,
) {
    val activePlayer: PlayerState get() = players[activeSeat]

    fun player(id: String): PlayerState? = players.firstOrNull { it.id == id }

    /** A player still playing or removed mid-game (feedback round 36). */
    fun anyPlayer(id: String): PlayerState? = player(id) ?: removed.firstOrNull { it.id == id }

    /** The number of players the game started with, removed ones included. */
    val seats: Int get() = players.size + removed.size

    fun seatOf(id: String): Int = players.indexOfFirst { it.id == id }
}
