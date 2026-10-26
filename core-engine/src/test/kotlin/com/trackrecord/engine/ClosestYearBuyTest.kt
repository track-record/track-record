package com.trackrecord.engine

import com.trackrecord.engine.GameAction.BuyCard
import com.trackrecord.engine.GameAction.Skip
import com.trackrecord.engine.GameAction.SubmitGuess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Closest Year buys, the Skips & Buys preset and the skipped song's info (feedback round 27). */
class ClosestYearBuyTest {
    private val three = listOf("Anna", "Ben", "Cara")

    private fun withSkips(settings: ClosestYearSettings, skips: Int) =
        closestGame(three, 1984, settings = settings).let { it.copy(players = it.players.map { p -> p.copy(skips = skips) }) }

    @Test
    fun `buying a card spends skips and adds the next song to the buyer's cards`() {
        val s = withSkips(ClosestYearSettings.CLOSEST, 3)
        val bought = s.act(BuyCard("Ben"))
        assertEquals(0, bought.p("Ben").skips)
        assertEquals(1, bought.p("Ben").wins, "counts towards the target")
        assertEquals(listOf(s.pool.first()), bought.p("Ben").collection)
        assertEquals(s.pool.drop(1), bought.pool)
        assertEquals(Purchase("Ben", s.pool.first()), bought.lastPurchase)
        assertEquals(s.round, bought.round, "the round goes on")
        assertNull(bought.act(SubmitGuess(1990)).lastPurchase, "cleared by the next action")
    }

    @Test
    fun `no buying when off, without enough skips, or after the reveal`() {
        assertEquals(RejectReason.FEATURE_DISABLED, withSkips(SKIPS, 3).rejectReason(BuyCard("Ben")))
        assertEquals(RejectReason.NOT_ENOUGH_SKIPS, withSkips(ClosestYearSettings.CLOSEST, 2).rejectReason(BuyCard("Ben")))
        val revealed = withSkips(ClosestYearSettings.CLOSEST, 3).actAll(SubmitGuess(1984))
        assertEquals(RejectReason.WRONG_PHASE, revealed.rejectReason(BuyCard("Ben")))
    }

    @Test
    fun `a bought card can win the game`() {
        val s = withSkips(ClosestYearSettings.CLOSEST.copy(winsToWin = 1), 3)
        val over = s.act(BuyCard("Cara"))
        assertEquals(Phase.GAME_OVER, over.phase)
        assertEquals(listOf("Cara"), over.result!!.winnerIds)
    }

    @Test
    fun `the Closest preset`() {
        val p = ClosestYearSettings.CLOSEST
        assertEquals(listOf(1, 5, 3), listOf(p.startingSkips, p.maxSkips, p.buyCost))
        assertEquals(ClosestYearPreset.CLOSEST, NO_SKIPS.withPreset(ClosestYearPreset.CLOSEST).preset)
        assertNull(SKIPS.buyCost, "Skips has no buying")
        assertEquals(listOf(SettingsError.BUY_COST), p.copy(buyCost = 6).validate())
    }

    @Test
    fun `a skip tells who skipped which song, in both modes`() {
        val closest = withSkips(SKIPS, 1)
        val first = closest.round!!.guessOrder.first()
        assertEquals(Skipped(first, closest.round.song), closest.act(Skip).lastSkip)
        assertNull(closest.act(Skip).act(SubmitGuess(1990)).lastSkip, "cleared by the next action")

        val timeline = timelineGame(listOf("Anna" to listOf(1970), "Ben" to listOf(1990)), song = 1984, settings = TimelineSettings.CLASSIC)
        assertEquals(Skipped(timeline.activePlayer.id, timeline.round!!.song), timeline.act(Skip).lastSkip)
    }
}
