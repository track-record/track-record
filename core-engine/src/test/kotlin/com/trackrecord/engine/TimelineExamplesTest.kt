package com.trackrecord.engine

import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameAction.SetNamed
import kotlin.test.Test
import kotlin.test.assertEquals

/** The worked examples from SPEC.md: Anna is active; Ben and Cara can veto. */
class TimelineExamplesTest {
    private val ben = listOf(1960)
    private val cara = listOf(2010)

    private fun game(anna: List<Int>, song: Int, settings: TimelineSettings = TimelineSettings.FIXED_VETOES) =
        timelineGame(listOf("Anna" to anna, "Ben" to ben, "Cara" to cara), song, settings)

    @Test
    fun `A - Ben gets 1990 into his own timeline`() {
        val revealed = game(listOf(1972, 1985, 1999), 1990)
            .actAll(SelectSlot(1), LockIn, PlaceVeto("Ben", 2), Reveal)
        assertEquals(TimelineOutcome.Stolen("Ben", 1), revealed.round!!.timelineOutcome)
        val done = revealed.act(Next)
        assertEquals(listOf(1960, 1990), done.years("Ben"))
        assertEquals(listOf(1972, 1985, 1999), done.years("Anna"))
        assertEquals(1, done.p("Ben").vetoes)
    }

    @Test
    fun `B - Anna keeps it, Ben's veto is spent`() {
        val done = game(listOf(1980, 1990, 2001), 1990)
            .actAll(SelectSlot(2), LockIn, PlaceVeto("Ben", 1), Reveal, Next)
        assertEquals(listOf(1980, 1990, 1990, 2001), done.years("Anna"))
        assertEquals(listOf(1960), done.years("Ben"))
        assertEquals(1, done.p("Ben").vetoes)
    }

    @Test
    fun `C - Ben gets it as the earliest valid veto, Cara's veto is spent`() {
        val revealed = game(listOf(1980, 1990, 2001), 1990)
            .actAll(SelectSlot(0), LockIn, PlaceVeto("Ben", 2), PlaceVeto("Cara", 1), Reveal)
        assertEquals("Ben", (revealed.round!!.timelineOutcome as TimelineOutcome.Stolen).playerId)
        val done = revealed.act(Next)
        assertEquals(listOf(1960, 1990), done.years("Ben"))
        assertEquals(listOf(2010), done.years("Cara"))
        assertEquals(1, done.p("Cara").vetoes)
        assertEquals(1, done.p("Ben").vetoes)
    }

    @Test
    fun `D - discarded`() {
        val revealed = game(listOf(1980), 1975).actAll(SelectSlot(1), LockIn, Reveal)
        assertEquals(TimelineOutcome.Discarded, revealed.round!!.timelineOutcome)
        val done = revealed.act(Next)
        assertEquals(listOf(1980), done.years("Anna"))
        assertEquals(listOf(1975), done.discarded.map { it.year })
    }

    @Test
    fun `E - discarded, Anna gets +1 veto with the Classic preset`() {
        val revealed = game(listOf(1980), 1975, TimelineSettings.CLASSIC)
            .actAll(SelectSlot(1), LockIn, Reveal, SetNamed(true))
        assertEquals(TimelineOutcome.Discarded, revealed.round!!.timelineOutcome)
        val done = revealed.act(Next)
        assertEquals(listOf(1980), done.years("Anna"))
        assertEquals(3, done.p("Anna").vetoes)
    }

    @Test
    fun `next seat becomes active after Next`() {
        val done = game(listOf(1980), 1975).actAll(SelectSlot(1), LockIn, Reveal, Next)
        assertEquals(Phase.AWAITING_DRAW, done.phase)
        assertEquals("Ben", done.activePlayer.id)
        val last = done.copy(activeSeat = 2).act(GameAction.Draw).actAll(SelectSlot(0), LockIn, Reveal, Next)
        assertEquals("Anna", last.activePlayer.id)
    }

    @Test
    fun `active player can change the slot until lock in`() {
        val s = game(listOf(1980, 1990), 1985).actAll(SelectSlot(0), SelectSlot(2), SelectSlot(1), LockIn)
        assertEquals(1, s.round!!.lockedSlot)
        assertEquals(RejectReason.WRONG_PHASE, s.rejectReason(SelectSlot(0)))
        assertEquals(RejectReason.NO_SLOT_SELECTED, game(listOf(1980), 1985).rejectReason(LockIn))
        assertEquals(RejectReason.INVALID_SLOT, game(listOf(1980), 1985).rejectReason(SelectSlot(2)))
    }

    @Test
    fun `reveal is enabled only after lock in`() {
        assertEquals(RejectReason.WRONG_PHASE, game(listOf(1980), 1985).rejectReason(Reveal))
    }
}
