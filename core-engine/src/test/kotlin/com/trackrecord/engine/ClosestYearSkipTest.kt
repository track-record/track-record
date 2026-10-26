package com.trackrecord.engine

import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SetNamedBy
import com.trackrecord.engine.GameAction.Skip
import com.trackrecord.engine.GameAction.SubmitGuess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Closest Year skips and their presets (Felix, feedback round 11). */
class ClosestYearSkipTest {
    private val three = listOf("Anna", "Ben", "Cara")

    @Test
    fun `players start with the preset's skips`() {
        val skips = startGame(GameMode.CLOSEST_YEAR, three, listOf(1980, 1990, 2000), GameSettings(closestYear = SKIPS))
        assertEquals(listOf(1, 1, 1), skips.players.map { it.skips })
        val none = startGame(GameMode.CLOSEST_YEAR, three, listOf(1980, 1990, 2000), GameSettings(closestYear = NO_SKIPS))
        assertEquals(listOf(0, 0, 0), none.players.map { it.skips })
    }

    @Test
    fun `the first guesser skips before anyone guessed`() {
        val s = closestGame(three, 1984).let { it.copy(players = it.players.map { p -> p.copy(skips = 1) }) }
        val first = s.round!!.guessOrder.first()
        val skipped = s.act(Skip)
        assertEquals(0, skipped.p(first).skips)
        assertEquals(s.round.number, skipped.round!!.number, "same round")
        assertEquals(s.round.guessOrder, skipped.round.guessOrder, "same guess order")
        assertEquals(s.pool.first(), skipped.round.song, "the next song takes its place")
        assertTrue(s.round.song in skipped.discarded, "the skipped song leaves the game")
        assertEquals(Phase.GUESSING, skipped.phase)
    }

    @Test
    fun `no skip after the first guess, without skips, or with an empty pool`() {
        val s = closestGame(three, 1984).let { it.copy(players = it.players.map { p -> p.copy(skips = 1) }) }
        assertEquals(RejectReason.WRONG_PHASE, s.act(SubmitGuess(1990)).rejectReason(Skip))
        assertEquals(RejectReason.NOT_ENOUGH_SKIPS, s.copy(players = s.players.map { it.copy(skips = 0) }).rejectReason(Skip))
        assertEquals(RejectReason.POOL_EMPTY, s.copy(pool = emptyList()).rejectReason(Skip))
    }

    @Test
    fun `one player can have named the song and earns a skip at Next, whoever won`() {
        val s = closestGame(three, 1984, settings = SKIPS)
        val order = s.round!!.guessOrder
        val revealed = s.actAll(SubmitGuess(1990), SubmitGuess(1970), SubmitGuess(2000), Reveal)
        val moved = revealed.actAll(SetNamedBy(order[1], true), SetNamedBy(order[2], true))
        assertEquals(order[2], moved.round!!.namedById, "marking another player moves the mark")
        assertEquals(order[2], moved.act(SetNamedBy(order[1], false)).round!!.namedById, "unmarking someone else changes nothing")
        assertEquals(null, moved.act(SetNamedBy(order[2], false)).round!!.namedById)
        val next = moved.act(Next)
        assertEquals(listOf(0, 0, 1), order.map { next.p(it).skips })
    }

    @Test
    fun `earned skips stop at the max held`() {
        val s = closestGame(three, 1984, settings = SKIPS).let { it.copy(players = it.players.map { p -> p.copy(skips = 2) }) }
        val order = s.round!!.guessOrder
        val next = s.actAll(SubmitGuess(1990), SubmitGuess(1970), SubmitGuess(2000), Reveal, SetNamedBy(order[0], true), Next)
        assertEquals(2, next.p(order[0]).skips, "already at the max of 2")
        assertEquals(RejectReason.SKIPS_AT_MAX, s.rejectReason(GameAction.AdjustSkips("Anna", 1)))
        val unlimited = s.copy(settings = s.settings.copy(closestYear = SKIPS.copy(maxSkips = null)))
        assertEquals(3, unlimited.act(GameAction.AdjustSkips("Anna", 1)).p("Anna").skips)
    }

    @Test
    fun `after an exact guess only those who guessed can be marked as namers`() {
        val s = closestGame(three, 1984, settings = SKIPS)
        val order = s.round!!.guessOrder
        val ended = s.actAll(SubmitGuess(1990), SubmitGuess(1984))
        assertEquals(Phase.REVEALED, ended.phase)
        assertEquals(RejectReason.NOT_A_GUESSER, ended.rejectReason(SetNamedBy(order[2], true)))
        assertEquals(order[1], ended.act(SetNamedBy(order[1], true)).round!!.namedById)
    }

    @Test
    fun `max skips must allow the starting skips`() {
        assertEquals(listOf(SettingsError.MAX_SKIPS), ClosestYearSettings(startingSkips = 3, maxSkips = 2, buyCost = null).validate())
        assertEquals(ClosestYearPreset.CUSTOM, ClosestYearSettings.CLOSEST.copy(maxSkips = 3).preset)
        assertEquals(5, ClosestYearSettings().maxSkips)
    }

    @Test
    fun `skips can be corrected from the scoreboard, not below zero`() {
        val s = closestGame(three, 1984, settings = SKIPS)
        val plus = s.act(GameAction.AdjustSkips("Anna", 1)).act(GameAction.AdjustSkips("Anna", 1))
        assertEquals(2, plus.p("Anna").skips)
        assertEquals(RejectReason.NOT_ENOUGH_SKIPS, s.rejectReason(GameAction.AdjustSkips("Anna", -1)))
        assertEquals(RejectReason.UNKNOWN_PLAYER, s.rejectReason(GameAction.AdjustSkips("Zoe", 1)))
    }

    @Test
    fun `naming is off in the no-skips preset and only after reveal`() {
        val off = closestGame(three, 1984, settings = NO_SKIPS)
        val revealedOff = off.actAll(SubmitGuess(1990), SubmitGuess(1970), SubmitGuess(2000), Reveal)
        assertEquals(RejectReason.FEATURE_DISABLED, revealedOff.rejectReason(SetNamedBy("Anna", true)))
        val on = closestGame(three, 1984, settings = SKIPS)
        assertEquals(RejectReason.WRONG_PHASE, on.rejectReason(SetNamedBy("Anna", true)))
    }

    @Test
    fun `presets match on their values and turn custom when one changes`() {
        assertEquals(ClosestYearPreset.CLOSEST, ClosestYearSettings().preset)
        assertEquals(ClosestYearPreset.RANKING, ClosestYearSettings.RANKING.preset)
        assertEquals(ClosestYearPreset.CUSTOM, ClosestYearSettings.CLOSEST.copy(startingSkips = 2).preset)
        // The engine-set lowest guessable year doesn't change the preset, nor do hidden guesses (feedback round 36).
        assertEquals(ClosestYearPreset.CLOSEST, ClosestYearSettings.CLOSEST.copy(lowestGuessableYear = 1970, hiddenGuesses = true).preset)
        assertEquals(ClosestYearSettings.DISTANCE.copy(lowestGuessableYear = 1970), ClosestYearSettings.CLOSEST.copy(lowestGuessableYear = 1970).withPreset(ClosestYearPreset.DISTANCE))
        assertEquals(listOf(SettingsError.STARTING_SKIPS), ClosestYearSettings(startingSkips = 6, maxSkips = null).validate())
    }
}
