package com.trackrecord.app.newgame

import com.trackrecord.app.game.GamePlayer
import com.trackrecord.engine.ClosestScoring
import com.trackrecord.engine.ClosestYearSettings
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.TimelineSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** New game with one player (D-380): the rules that apply alone, and the others kept for a game with more. */
class SoloSetupTest {
    private val anna = GamePlayer("a", "Anna", "AN", 0)
    private val ben = GamePlayer("b", "Ben", "BE", 1)
    private val rules = GameSettings(
        timeline = TimelineSettings.FIXED_VETOES,
        closestYear = ClosestYearSettings.RANKING.copy(hiddenGuesses = true, startingSkips = 2),
    )

    @Test
    fun `alone, the game starts with the rules that apply, and a second player brings the others back`() {
        val alone = NewGameState(mode = GameMode.CLOSEST_YEAR, players = listOf(anna), settings = rules)
        assertTrue(alone.solo)
        assertTrue(alone.enoughPlayers, "one player is enough")
        assertEquals(ClosestScoring.DISTANCE, alone.gameSettings.closestYear.scoring)
        assertFalse(alone.gameSettings.closestYear.hiddenGuesses)
        assertEquals(2, alone.gameSettings.closestYear.startingSkips, "the rest as chosen")
        assertEquals(TimelineSettings.CLASSIC, alone.gameSettings.timeline, "Fixed vetoes' jokers can't be spent alone")

        val two = alone.copy(players = listOf(anna, ben))
        assertFalse(two.solo)
        assertEquals(rules, two.gameSettings)
    }

    @Test
    fun `nobody is not enough`() {
        assertFalse(NewGameState(players = emptyList()).enoughPlayers)
    }
}
