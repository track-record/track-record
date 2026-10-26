package com.trackrecord.engine

import com.trackrecord.engine.GameAction.BuyCard
import com.trackrecord.engine.GameAction.Draw
import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SelectSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WinTest {
    private val toWin3 = TimelineSettings.CLASSIC.copy(cardsToWin = 3)

    private fun game() = timelineGame(
        listOf("Anna" to listOf(1970, 1980), "Ben" to listOf(1960, 2010)),
        song = 1990,
        settings = toWin3,
        vetoes = mapOf("Anna" to 3, "Ben" to 3),
    )

    @Test
    fun `win by own placement`() {
        val done = game().actAll(SelectSlot(2), LockIn, Reveal, Next)
        assertEquals(Phase.GAME_OVER, done.phase)
        assertEquals(GameResult(listOf("Anna"), EndReason.TARGET_REACHED), done.result)
        assertNull(done.round)
    }

    @Test
    fun `win by a veto steal`() {
        val done = game().actAll(SelectSlot(0), LockIn, PlaceVeto("Ben", 2), Reveal, Next)
        assertEquals(listOf(1960, 1990, 2010), done.years("Ben"))
        assertEquals(GameResult(listOf("Ben"), EndReason.TARGET_REACHED), done.result)
    }

    @Test
    fun `win by a purchase, checked immediately`() {
        val done = game().act(BuyCard("Ben"))
        assertEquals(GameResult(listOf("Ben"), EndReason.TARGET_REACHED), done.result)
        assertEquals(Phase.GAME_OVER, done.phase)
        assertEquals(RejectReason.WRONG_PHASE, done.rejectReason(SelectSlot(0)))
    }

    @Test
    fun `no win below the target`() {
        val done = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), song = 1990, settings = toWin3)
            .actAll(SelectSlot(1), LockIn, Reveal, Next)
        assertEquals(Phase.AWAITING_DRAW, done.phase)
        assertNull(done.result)
    }

    @Test
    fun `starting cards count towards the target`() {
        val settings = GameSettings(timeline = TimelineSettings.FIXED_VETOES.copy(startingCards = 2, cardsToWin = 3))
        // Each player is dealt 2 starting cards; the fifth song is played.
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(
                GameMode.TIMELINE,
                listOf("Anna", "Ben"),
                listOf(Card(1, 1950), Card(2, 2020), Card(3, 1960), Card(4, 2021), Card(5, 1980)),
                settings,
                seed = 7,
                currentYear = CURRENT_YEAR,
            ),
        ).state!!
        assertEquals(2, start.p("Anna").timeline.size)
        assertEquals(2, start.p("Ben").timeline.size)
        assertEquals(1, start.pool.size)

        val drawn = start.act(Draw)
        val y = drawn.round!!.song.year
        val slot = (0..2).first { Slots.isValid(drawn.p("Anna").timeline, it, y) }
        val done = drawn.actAll(SelectSlot(slot), LockIn, Reveal, Next)
        assertEquals(GameResult(listOf("Anna"), EndReason.TARGET_REACHED), done.result)
    }
}
