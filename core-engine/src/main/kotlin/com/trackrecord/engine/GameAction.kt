package com.trackrecord.engine

import kotlinx.serialization.Serializable

@Serializable
sealed interface GameAction {
    /**
     * Starts a new game. [songs] is the pool before shuffling (filters already applied);
     * [playerIds] are in seating order.
     */
    @Serializable
    data class StartGame(
        val mode: GameMode,
        val playerIds: List<String>,
        val songs: List<Card>,
        val settings: GameSettings,
        val seed: Long,
        val currentYear: Int,
    ) : GameAction

    @Serializable data object Draw : GameAction

    // Timeline mode
    @Serializable data class SelectSlot(val slot: Int) : GameAction
    @Serializable data object Skip : GameAction
    @Serializable data object LockIn : GameAction
    @Serializable data class PlaceVeto(val playerId: String, val slot: Int) : GameAction
    @Serializable data object UndoVeto : GameAction
    /** Timeline, after reveal: the active player named song and artist (or not); short for [SetNamedBy]. */
    @Serializable data class SetNamed(val named: Boolean) : GameAction
    @Serializable data class BuyCard(val playerId: String) : GameAction

    /** Scoreboard correction: add (+1) or remove (−1) vetoes, within 0..max vetoes held. */
    @Serializable data class AdjustVetoes(val playerId: String, val delta: Int) : GameAction

    // Closest Year mode
    /**
     * A guess by [playerId], or by whoever's turn it is when null. With hidden guesses anyone who hasn't
     * guessed may guess, in any order (feedback round 36).
     */
    @Serializable data class SubmitGuess(val year: Int, val playerId: String? = null) : GameAction
    @Serializable data object UndoGuess : GameAction

    /**
     * After reveal: [playerId] named song and artist (or not). Only one player can have named it:
     * marking another one moves the mark. Closest Year: players who guessed (feedback rounds 12, 13);
     * Timeline: the players allowed by [TimelineSettings.namingCallers] (feedback round 19).
     */
    @Serializable data class SetNamedBy(val playerId: String, val named: Boolean) : GameAction

    /** Closest Year scoreboard correction: add (+1) or remove (−1) skips, never below 0. */
    @Serializable data class AdjustSkips(val playerId: String, val delta: Int) : GameAction

    // Both modes
    @Serializable data object Reveal : GameAction

    /**
     * Mid-game: [playerId] stops playing (feedback round 36). Their cards and score stay on the
     * scoreboard, greyed out; at least [GameEngine.MIN_PLAYERS] players must remain. A round the player
     * was taking part in goes on without them, or ends if it was their turn in Timeline.
     */
    @Serializable data class RemovePlayer(val playerId: String) : GameAction
    @Serializable data class CorrectYear(val year: Int) : GameAction
    @Serializable data object Next : GameAction

    /**
     * Mid-game: the clip length limit in seconds (null: none), from the next song on and for the song
     * playing now (feedback round 25; D-191). Same range as the setting.
     */
    @Serializable data class SetClipLimit(val seconds: Int?) : GameAction

    /** Mid-game: the start offset in seconds, from the next song on (feedback round 34). Same range as the setting. */
    @Serializable data class SetStartOffset(val seconds: Int) : GameAction

    /**
     * A MusicBrainz lookup finished for a song that hasn't been revealed yet: [year] is the new
     * effective year. Applies to undrawn pool songs and to the current song before reveal.
     */
    @Serializable data class ApplyLookupYear(val songId: Long, val year: Int) : GameAction

    /**
     * A song that had no year got one from MusicBrainz during the game: it joins the undrawn pool at a
     * seed-determined random position (DECISIONS.md D-064).
     */
    @Serializable data class AddToPool(val card: Card) : GameAction

    /**
     * v2: the current song can't be played (a streamed song while offline, or one Deezer removed). The
     * next pool song takes its place in the same round, nothing is paid or scored, and the unplayable
     * song goes back to the end of the pool (SPEC v2, Playback; DECISIONS.md D-081). Allowed before
     * lock-in (Timeline) or reveal (Closest Year).
     */
    @Serializable data object ReplaceUnplayable : GameAction
}

enum class RejectReason {
    NO_GAME,
    WRONG_PHASE,
    WRONG_MODE,
    TOO_FEW_PLAYERS,
    TOO_MANY_PLAYERS,
    DUPLICATE_PLAYER,
    INVALID_SETTINGS,
    POOL_TOO_SMALL,
    POOL_EMPTY,
    UNKNOWN_PLAYER,
    ACTIVE_PLAYER_CANNOT_VETO,
    INVALID_SLOT,
    SLOT_TAKEN,
    NO_SLOT_SELECTED,
    NOT_ENOUGH_VETOES,
    NOT_ENOUGH_SKIPS,
    SKIPS_AT_MAX,
    NOT_A_GUESSER,
    NOT_ALLOWED_TO_NAME,
    VETOES_AT_MAX,
    VETO_LIMIT_REACHED,
    NOTHING_TO_UNDO,
    FEATURE_DISABLED,
    YEAR_OUT_OF_RANGE,
    DUPLICATE_GUESS,
    GUESSES_INCOMPLETE,
    ALL_GUESSED,
    SONG_NOT_FOUND,
    SONG_ALREADY_IN_GAME,
}

sealed interface ReduceResult {
    val state: GameState?

    data class Accepted(override val state: GameState) : ReduceResult

    /** The action was not allowed; [state] is unchanged. */
    data class Rejected(override val state: GameState?, val reason: RejectReason) : ReduceResult
}
