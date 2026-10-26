package com.trackrecord.engine

import com.trackrecord.engine.GameAction.BuyCard
import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameAction.SetNamed
import com.trackrecord.engine.GameAction.Skip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EconomyTest {
    private val classic = TimelineSettings.CLASSIC
    private val fixed = TimelineSettings.FIXED_VETOES

    private fun game(settings: TimelineSettings, vetoes: Map<String, Int> = emptyMap(), pool: List<Int> = listOf(2000, 2001, 2002)) =
        timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), song = 1990, settings = settings, vetoes = vetoes, pool = pool)

    @Test
    fun `naming bonus is capped at the max`() {
        val done = game(classic, vetoes = mapOf("Anna" to 5)).actAll(SelectSlot(1), LockIn, Reveal, SetNamed(true), Next)
        assertEquals(5, done.p("Anna").vetoes)
        val below = game(classic, vetoes = mapOf("Anna" to 4)).actAll(SelectSlot(1), LockIn, Reveal, SetNamed(true), Next)
        assertEquals(5, below.p("Anna").vetoes)
    }

    @Test
    fun `naming bonus counts after a wrong placement`() {
        val done = game(classic).actAll(SelectSlot(0), LockIn, Reveal, SetNamed(true), Next)
        assertEquals(listOf(1980), done.years("Anna"))
        assertEquals(3, done.p("Anna").vetoes)
    }

    @Test
    fun `naming toggle can be turned off again before Next`() {
        val done = game(classic).actAll(SelectSlot(1), LockIn, Reveal, SetNamed(true), SetNamed(false), Next)
        assertEquals(2, done.p("Anna").vetoes)
    }

    @Test
    fun `naming bonus goes to the active player even after a steal`() {
        val done = game(classic).actAll(SelectSlot(0), LockIn, PlaceVeto("Ben", 1), Reveal, SetNamed(true), Next)
        assertEquals(listOf(1960, 1990), done.years("Ben"))
        assertEquals(3, done.p("Anna").vetoes)
        assertEquals(1, done.p("Ben").vetoes)
    }

    @Test
    fun `skip deducts the cost and draws another song`() {
        val start = game(classic)
        val skipped = start.act(Skip)
        assertEquals(1, skipped.p("Anna").vetoes)
        assertEquals(2000, skipped.round!!.song.year)
        assertEquals(listOf(1990), skipped.discarded.map { it.year })
        assertEquals(listOf(2001, 2002), skipped.pool.map { it.year })
        assertEquals(Phase.PLACING, skipped.phase)

        val twice = skipped.act(Skip)
        assertEquals(0, twice.p("Anna").vetoes)
        assertEquals(RejectReason.NOT_ENOUGH_VETOES, twice.rejectReason(Skip))
    }

    @Test
    fun `skip clears the selected slot and is not allowed after lock in`() {
        val s = game(classic).actAll(SelectSlot(1), Skip)
        assertNull(s.round!!.selectedSlot)
        assertEquals(RejectReason.WRONG_PHASE, game(classic).actAll(SelectSlot(1), LockIn).rejectReason(Skip))
    }

    @Test
    fun `skip with an empty pool is rejected`() {
        assertEquals(RejectReason.POOL_EMPTY, game(classic, pool = emptyList()).rejectReason(Skip))
    }

    @Test
    fun `buy deducts the cost and inserts the next pool song sorted`() {
        val bought = game(classic, vetoes = mapOf("Ben" to 3)).act(BuyCard("Ben"))
        assertEquals(0, bought.p("Ben").vetoes)
        assertEquals(listOf(1960, 2000), bought.years("Ben"))
        assertEquals(Purchase("Ben", bought.p("Ben").timeline[1]), bought.lastPurchase)
        assertEquals(listOf(2001, 2002), bought.pool.map { it.year })
        assertEquals(RejectReason.NOT_ENOUGH_VETOES, bought.rejectReason(BuyCard("Ben")))
        // lastPurchase is cleared by the next accepted action
        assertNull(bought.act(SelectSlot(0)).lastPurchase)
    }

    @Test
    fun `buy is allowed before lock in and between rounds, not between lock in and Next`() {
        val rich = mapOf("Anna" to 5, "Ben" to 5)
        val locked = game(classic, rich).actAll(SelectSlot(1), LockIn)
        assertEquals(RejectReason.WRONG_PHASE, locked.rejectReason(BuyCard("Ben")))
        val revealed = locked.act(Reveal)
        assertEquals(RejectReason.WRONG_PHASE, revealed.rejectReason(BuyCard("Ben")))
        val between = revealed.act(Next)
        assertEquals(Phase.AWAITING_DRAW, between.phase)
        assertEquals(listOf(1960, 2000), between.act(BuyCard("Ben")).years("Ben"))
    }

    @Test
    fun `active buyer's selection is cleared`() {
        val s = game(classic, mapOf("Anna" to 3)).actAll(SelectSlot(1), BuyCard("Anna"))
        assertNull(s.round!!.selectedSlot)
        assertEquals(listOf(1980, 2000), s.years("Anna"))
    }

    @Test
    fun `buy with an empty pool is rejected`() {
        assertEquals(RejectReason.POOL_EMPTY, game(classic, mapOf("Ben" to 3), pool = emptyList()).rejectReason(BuyCard("Ben")))
    }

    @Test
    fun `fixed preset never earns, skips or buys`() {
        assertEquals(TimelinePreset.FIXED_VETOES, fixed.preset)
        val s = game(fixed, mapOf("Anna" to 2, "Ben" to 2))
        assertEquals(RejectReason.FEATURE_DISABLED, s.rejectReason(Skip))
        assertEquals(RejectReason.FEATURE_DISABLED, s.rejectReason(BuyCard("Ben")))
        val revealed = s.actAll(SelectSlot(1), LockIn, Reveal)
        assertEquals(RejectReason.FEATURE_DISABLED, revealed.rejectReason(SetNamed(true)))
        assertEquals(2, revealed.act(Next).p("Anna").vetoes)
    }

    @Test
    fun `fixed preset vetoes are spent whether right or wrong`() {
        val done = timelineGame(
            listOf("Anna" to listOf(1980), "Ben" to listOf(1960), "Cara" to listOf(2010)),
            song = 1990,
        ).actAll(SelectSlot(0), LockIn, PlaceVeto("Ben", 1), Reveal, Next)
        assertEquals(1, done.p("Ben").vetoes)
        assertEquals(listOf(1960, 1990), done.years("Ben"))
    }

    @Test
    fun `presets and custom detection`() {
        assertEquals(TimelinePreset.CLASSIC, classic.preset)
        assertEquals(TimelinePreset.CUSTOM, classic.copy(buyCost = 4).preset)
        assertEquals(TimelinePreset.CUSTOM, fixed.copy(cardsToWin = 12).preset)
        assertEquals(emptyList(), classic.validate())
        assertEquals(emptyList(), fixed.validate())
    }

    @Test
    fun `settings ranges are validated`() {
        assertEquals(listOf(SettingsError.MAX_VETOES), fixed.copy(startingVetoes = 3, maxVetoes = 2).validate())
        assertEquals(listOf(SettingsError.SKIP_COST), fixed.copy(skipCost = 4).validate())
        assertEquals(listOf(SettingsError.BUY_COST), fixed.copy(buyCost = 0).validate())
        assertEquals(listOf(SettingsError.CARDS_TO_WIN_NOT_ABOVE_STARTING), fixed.copy(startingCards = 3, cardsToWin = 3).validate())
        assertEquals(listOf(SettingsError.LOWEST_YEAR), ClosestYearSettings(lowestGuessableYear = 1799).validate())
        assertEquals(listOf(SettingsError.CLIP_LIMIT), CommonSettings(clipLimitSeconds = 9).validate())
    }

    @Test
    fun `a cost above the most tokens held is refused and lowered when loaded (review 2 B-11)`() {
        val holdTwo = fixed.copy(maxVetoes = 2, skipCost = 3, buyCost = 3)
        assertEquals(listOf(SettingsError.SKIP_COST, SettingsError.BUY_COST), holdTwo.validate())
        assertEquals(2, holdTwo.withCostsWithinMax().buyCost)
        assertEquals(2, holdTwo.withCostsWithinMax().skipCost)
        assertNull(fixed.copy(startingVetoes = 0, maxVetoes = 0, buyCost = 1).withCostsWithinMax().buyCost, "nothing can be held: off")
        val skips = ClosestYearSettings(maxSkips = 2, buyCost = 3)
        assertEquals(listOf(SettingsError.BUY_COST), skips.validate())
        assertEquals(2, skips.withCostsWithinMax().buyCost)
        assertEquals(3, skips.copy(maxSkips = null).withCostsWithinMax().buyCost, "no limit")
        assertEquals(emptyList(), ClosestYearSettings.CLOSEST.validate())
    }
}
