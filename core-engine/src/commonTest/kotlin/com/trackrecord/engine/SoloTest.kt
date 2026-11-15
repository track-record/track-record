package com.trackrecord.engine

import com.trackrecord.engine.GameAction.Draw
import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameAction.StartGame
import com.trackrecord.engine.GameAction.SubmitGuess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One player alone (D-380): Song Order, or Timeline scored by distance. */
class SoloTest {
    private fun start(mode: GameMode, settings: GameSettings, players: List<String> = listOf("Anna")): ReduceResult =
        GameEngine.reduce(null, StartGame(mode, players, (1..40).map { card(1950 + it) }, settings, seed = 7, currentYear = CURRENT_YEAR))

    private fun started(mode: GameMode, settings: GameSettings, players: List<String> = listOf("Anna")): GameState =
        (start(mode, settings, players) as ReduceResult.Accepted).state

    @Test
    fun `Song Order alone plays to the target, every turn the same player's`() {
        val rules = TimelineSettings.CLASSIC.copy(cardsToWin = 4)
        var s = started(GameMode.TIMELINE, GameSettings(timeline = rules))
        assertTrue(s.solo)
        repeat(3) {
            s = s.act(Draw)
            val year = s.round!!.song.year
            s = s.actAll(SelectSlot(s.activePlayer.timeline.count { it.year <= year }), LockIn, Reveal)
            assertEquals("Anna", s.activePlayer.id)
            s = s.act(Next)
        }
        assertEquals(Phase.GAME_OVER, s.phase)
        assertEquals(listOf("Anna"), s.result!!.winnerIds)
        assertEquals(4, s.p("Anna").timeline.size)
    }

    @Test
    fun `Timeline alone is scored by distance, with the guess seen`() {
        assertIs<ReduceResult.Rejected>(start(GameMode.CLOSEST_YEAR, GameSettings(closestYear = ClosestYearSettings.CLOSEST)), "Closest needs others")
        assertIs<ReduceResult.Rejected>(start(GameMode.CLOSEST_YEAR, GameSettings(closestYear = ClosestYearSettings.RANKING)), "Rank needs others")
        assertIs<ReduceResult.Rejected>(start(GameMode.CLOSEST_YEAR, GameSettings(closestYear = ClosestYearSettings.DISTANCE.copy(hiddenGuesses = true))))
        val rules = ClosestYearSettings.DISTANCE.copy(winsToWin = 2)
        var s = started(GameMode.CLOSEST_YEAR, GameSettings(closestYear = rules)).act(Draw)
        val year = s.round!!.song.year
        s = s.actAll(SubmitGuess(year - 2), Reveal)
        assertEquals(mapOf("Anna" to 3), s.round!!.points)
        assertEquals(listOf("Anna"), s.round.winnerIds, "a guess that scores counts as won")
        s = s.act(Next).act(Draw)
        s = s.actAll(SubmitGuess(s.round!!.song.year), Reveal, Next)
        assertEquals(8, s.p("Anna").points)
        s = s.act(Draw)
        s = s.actAll(SubmitGuess(s.round!!.song.year), Reveal, Next)
        assertEquals(Phase.GAME_OVER, s.phase, "10 points: 2 perfect rounds")
    }

    @Test
    fun `the solo rules from any setup, and a game with others never becomes solo`() {
        val any = GameSettings(timeline = TimelineSettings.FIXED_VETOES, closestYear = ClosestYearSettings.RANKING.copy(hiddenGuesses = true))
        val solo = any.forSolo()
        assertEquals(TimelineSettings.CLASSIC, solo.timeline, "Fixed vetoes' jokers can't be spent alone")
        assertEquals(TimelineSettings.CLASSIC.copy(cardsToWin = 7), GameSettings(timeline = TimelineSettings.CLASSIC.copy(cardsToWin = 7)).forSolo().timeline)
        assertTrue(solo.closestYear.soloReady)
        assertEquals(any.closestYear.startingSkips, solo.closestYear.startingSkips, "the other rules stay")

        val alone = started(GameMode.TIMELINE, GameSettings())
        assertEquals(RejectReason.TOO_FEW_PLAYERS, alone.rejectReason(GameAction.RemovePlayer("Anna")))
        val two = started(GameMode.TIMELINE, GameSettings(), listOf("Anna", "Ben"))
        assertFalse(two.solo)
        assertEquals(RejectReason.TOO_FEW_PLAYERS, two.rejectReason(GameAction.RemovePlayer("Ben")))
        val three = started(GameMode.TIMELINE, GameSettings(), listOf("Anna", "Ben", "Cara")).act(GameAction.RemovePlayer("Cara"))
        assertFalse(three.solo)
    }
}
