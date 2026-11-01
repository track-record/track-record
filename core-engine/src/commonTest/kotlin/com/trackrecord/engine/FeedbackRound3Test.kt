package com.trackrecord.engine

import com.trackrecord.engine.GameAction.AddToPool
import com.trackrecord.engine.GameAction.ApplyLookupYear
import com.trackrecord.engine.GameAction.Draw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackRound3Test {
    @Test
    fun `guess floor is the start of the earliest song's decade, at most 2000`() {
        assertEquals(1980, GameEngine.guessFloor(1984))
        assertEquals(1960, GameEngine.guessFloor(1960))
        assertEquals(2000, GameEngine.guessFloor(2011))
        assertEquals(1990, GameEngine.guessFloor(1999))
    }

    @Test
    fun `closest year start sets the floor from the pool`() {
        assertEquals(1960, startGame(GameMode.CLOSEST_YEAR, listOf("A", "B"), listOf(1983, 1965, 1999)).settings.closestYear.lowestGuessableYear)
        assertEquals(2000, startGame(GameMode.CLOSEST_YEAR, listOf("A", "B"), listOf(2011, 2015)).settings.closestYear.lowestGuessableYear)
        // Timeline keeps whatever was passed.
        assertEquals(1900, startGame(GameMode.TIMELINE, listOf("A", "B"), listOf(2011, 2015, 2016)).settings.closestYear.lowestGuessableYear)
    }

    @Test
    fun `the floor follows a lookup year below it`() {
        val s = startGame(GameMode.CLOSEST_YEAR, listOf("A", "B"), listOf(1983, 1985, 1987))
        assertEquals(1980, s.settings.closestYear.lowestGuessableYear)
        val lowered = s.act(ApplyLookupYear(s.pool.last().songId, 1958))
        assertEquals(1950, lowered.settings.closestYear.lowestGuessableYear)
    }

    @Test
    fun `songs found during the game join the undrawn pool once`() {
        val s = startGame(GameMode.CLOSEST_YEAR, listOf("A", "B"), (1..10).map { 1990 + it }).act(Draw)
        val card = Card(9_999, 1971)
        val added = s.act(AddToPool(card))
        assertEquals(s.pool.size + 1, added.pool.size)
        assertTrue(card in added.pool)
        assertEquals(1970, added.settings.closestYear.lowestGuessableYear)
        assertEquals(RejectReason.SONG_ALREADY_IN_GAME, added.rejectReason(AddToPool(card)))
        assertEquals(RejectReason.SONG_ALREADY_IN_GAME, added.rejectReason(AddToPool(s.round!!.song)))
        // Deterministic for the seed.
        assertEquals(added.pool, s.act(AddToPool(card)).pool)
    }
}
