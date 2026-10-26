package com.trackrecord.remote

import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameState
import com.trackrecord.engine.Phase
import com.trackrecord.engine.TimelineOutcome

/** The reveal's outline (feedback round 19): green won, red lost, yellow/orange someone else's success. */
enum class OutcomeTone { WON, LOST, OTHER }

object OutcomeTones {
    /**
     * The outline for a device with one player, [viewerId] (a guest phone, or the host's phone when all
     * other players are on guest phones):
     * - **WON:** the viewer won the round (kept their card, closest guess) or stole the card with a veto.
     * - **LOST:** the viewer's card was stolen, or their veto failed while someone got the card.
     * - **OTHER:** another player won normally or stole a card the viewer had nothing to do with.
     * - **None:** nobody gets the card or wins the round.
     */
    fun forViewer(state: GameState, viewerId: String): OutcomeTone? {
        val round = state.round ?: return null
        if (state.phase != Phase.REVEALED) return null
        return when (state.mode) {
            GameMode.CLOSEST_YEAR -> when {
                round.winnerIds.isEmpty() -> null
                viewerId in round.winnerIds -> OutcomeTone.WON
                else -> OutcomeTone.OTHER
            }
            GameMode.TIMELINE -> {
                val vetoed = round.vetoes.any { it.playerId == viewerId }
                when (val outcome = round.timelineOutcome) {
                    is TimelineOutcome.Kept -> when {
                        outcome.playerId == viewerId -> OutcomeTone.WON
                        vetoed -> OutcomeTone.LOST
                        else -> OutcomeTone.OTHER
                    }
                    is TimelineOutcome.Stolen -> when {
                        outcome.playerId == viewerId -> OutcomeTone.WON
                        state.activePlayer.id == viewerId || vetoed -> OutcomeTone.LOST
                        else -> OutcomeTone.OTHER
                    }
                    TimelineOutcome.Discarded, null -> null
                }
            }
        }
    }

    /**
     * The host's outline: the one player's view when all others play on guest phones; with several
     * players on the host's phone, green for a normal win, yellow/orange for a steal, none when nobody
     * gets the card (feedback round 19).
     */
    fun forHost(state: GameState, onGuestPhones: Set<String>): OutcomeTone? {
        val local = state.players.map { it.id }.filter { it !in onGuestPhones }
        local.singleOrNull()?.let { return forViewer(state, it) }
        val round = state.round ?: return null
        if (state.phase != Phase.REVEALED) return null
        return when (state.mode) {
            GameMode.CLOSEST_YEAR -> if (round.winnerIds.isNotEmpty()) OutcomeTone.WON else null
            GameMode.TIMELINE -> when (round.timelineOutcome) {
                is TimelineOutcome.Kept -> OutcomeTone.WON
                is TimelineOutcome.Stolen -> OutcomeTone.OTHER
                TimelineOutcome.Discarded, null -> null
            }
        }
    }
}
