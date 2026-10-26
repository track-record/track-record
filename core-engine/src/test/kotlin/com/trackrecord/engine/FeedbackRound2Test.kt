package com.trackrecord.engine

import com.trackrecord.engine.GameAction.AdjustVetoes
import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.SelectSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackRound2Test {
    @Test
    fun `no gap between cards of the same year`() {
        val t = cards(1980, 1980, 1990, 1990, 1990, 2001)
        assertEquals(listOf(0, 2, 5, 6), (0..t.size).filter { Slots.isSelectable(t, it) })
        assertEquals(listOf(0, 1), (0..1).filter { Slots.isSelectable(cards(1980), it) })
    }

    @Test
    fun `slots between equal years are rejected for placing and vetoing`() {
        val s = timelineGame(listOf("Anna" to listOf(1980, 1980, 1990), "Ben" to listOf(1960)), song = 1980)
        assertEquals(RejectReason.INVALID_SLOT, s.rejectReason(SelectSlot(1)))
        val locked = s.actAll(SelectSlot(0), LockIn)
        assertEquals(RejectReason.INVALID_SLOT, locked.rejectReason(PlaceVeto("Ben", 1)))
        locked.act(PlaceVeto("Ben", 2))
    }

    @Test
    fun `nobody can veto without vetoes, limit or free gaps`() {
        val two = listOf("Anna" to listOf(1980), "Ben" to listOf(1960))
        assertTrue(GameEngine.canAnyoneVeto(timelineGame(two, 1990).actAll(SelectSlot(1), LockIn)))
        assertFalse(GameEngine.canAnyoneVeto(timelineGame(two, 1990, vetoes = mapOf("Ben" to 0)).actAll(SelectSlot(1), LockIn)))
        // Ben's one veto per song is used, so nobody else can veto.
        assertFalse(GameEngine.canAnyoneVeto(timelineGame(two, 1990).actAll(SelectSlot(1), LockIn, PlaceVeto("Ben", 0))))
    }

    @Test
    fun `vetoes can be corrected within 0 and max`() {
        val s = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), 1990, TimelineSettings.CLASSIC)
        assertEquals(3, s.act(AdjustVetoes("Anna", 1)).p("Anna").vetoes)
        assertEquals(1, s.act(AdjustVetoes("Anna", -1)).p("Anna").vetoes)
        assertEquals(RejectReason.NOT_ENOUGH_VETOES, s.act(AdjustVetoes("Anna", -2)).rejectReason(AdjustVetoes("Anna", -1)))
        assertEquals(RejectReason.VETOES_AT_MAX, s.act(AdjustVetoes("Anna", 3)).rejectReason(AdjustVetoes("Anna", 1)))
        assertEquals(RejectReason.UNKNOWN_PLAYER, s.rejectReason(AdjustVetoes("Zoe", 1)))
        assertEquals(RejectReason.WRONG_MODE, closestGame(listOf("A", "B"), 1984).rejectReason(AdjustVetoes("A", 1)))
    }
}
