package com.trackrecord.engine.stats

import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameState
import com.trackrecord.engine.NamingCallers
import com.trackrecord.engine.Phase
import com.trackrecord.engine.TimelineOutcome
import kotlin.math.abs

/**
 * What one round says about one player, for the player stats (feedback round 47). [played]: their turn in
 * Song Order, their guess in Timeline (Closest Year). [yearsOff]: how far their guess was from the song's
 * year (Closest Year only). [namingChance]: the rules let them name the song and no player calling
 * before them did; [named]: they did. [won]: the card went to them in Song Order (their turn, or their veto), or
 * they were among the round's winners in Timeline (feedback round 48).
 */
data class RoundRecord(
    val playerId: String,
    val year: Int,
    val played: Boolean,
    val yearsOff: Int?,
    val namingChance: Boolean,
    val named: Boolean,
    val won: Boolean = false,
)

object RoundStats {
    /** The records of a revealed round, as it stands when Next commits it; players with nothing to record are left out. */
    fun of(state: GameState): List<RoundRecord> {
        val round = state.round ?: return emptyList()
        if (state.phase != Phase.REVEALED) return emptyList()
        val year = round.song.year
        val chances = namingChances(state)
        val playing = state.players.map { it.id }
        return playing.mapNotNull { id ->
            val guess = round.guesses.firstOrNull { it.playerId == id }
            val played = when (state.mode) {
                GameMode.TIMELINE -> id == state.activePlayer.id
                GameMode.CLOSEST_YEAR -> guess != null
            }
            val chance = id in chances
            val won = when (state.mode) {
                GameMode.TIMELINE -> when (val outcome = round.timelineOutcome) {
                    is TimelineOutcome.Kept -> outcome.playerId == id
                    is TimelineOutcome.Stolen -> outcome.playerId == id
                    TimelineOutcome.Discarded, null -> false
                }
                GameMode.CLOSEST_YEAR -> id in round.winnerIds
            }
            if (!played && !chance && !won) return@mapNotNull null
            RoundRecord(id, year, played, guess?.let { abs(it.year - year) }, chance, chance && round.namedById == id, won)
        }
    }

    /**
     * Who had a chance to name the song. Players call in tiers: in Song Order the active player first, then each
     * vetoer in veto order, then (everyone may call) all others together; in Timeline everyone who guessed, together.
     * Without a namer every tier had its chance; with one, the tiers before the namer's and the namer.
     */
    fun namingChances(state: GameState): Set<String> {
        val round = state.round ?: return emptySet()
        val tiers: List<List<String>> = when (state.mode) {
            GameMode.TIMELINE -> {
                if (!state.settings.timeline.namingBonus) return emptySet()
                val candidates = GameEngine.namingCandidates(state)
                val active = state.activePlayer.id
                val vetoers = round.vetoes.map { it.playerId }.distinct().filter { it != active }
                val others = candidates - setOf(active) - vetoers.toSet()
                val callers = when (state.settings.timeline.namingCallers) {
                    NamingCallers.ACTIVE -> emptyList()
                    else -> vetoers.map { listOf(it) }
                }
                listOf(listOf(active)) + callers + listOfNotNull(others.takeIf { it.isNotEmpty() })
            }
            GameMode.CLOSEST_YEAR -> {
                if (!state.settings.closestYear.namingEarnsSkip) return emptySet()
                listOf(round.guesses.map { it.playerId })
            }
        }.map { tier -> tier.filter { state.player(it) != null } }
        val namer = round.namedById
        val namerTier = tiers.indexOfFirst { namer in it }
        if (namer == null || namerTier < 0) return tiers.flatten().toSet()
        return (tiers.take(namerTier).flatten() + namer).toSet()
    }
}
