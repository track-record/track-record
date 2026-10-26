package com.trackrecord.engine

import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameAction.UndoVeto
import kotlin.test.Test
import kotlin.test.assertEquals

class VetoTest {
    private fun locked(settings: TimelineSettings = TimelineSettings.FIXED_VETOES, vetoes: Map<String, Int> = emptyMap()) =
        timelineGame(
            listOf("Anna" to listOf(1970, 1980, 1990), "Ben" to listOf(1960), "Cara" to listOf(2010)),
            song = 1985,
            settings = settings,
            vetoes = vetoes,
        ).actAll(SelectSlot(1), LockIn)

    @Test
    fun `never on the active player's slot`() {
        assertEquals(RejectReason.SLOT_TAKEN, locked().rejectReason(PlaceVeto("Ben", 1)))
    }

    @Test
    fun `never on an already vetoed slot`() {
        val s = locked().act(PlaceVeto("Ben", 2))
        assertEquals(RejectReason.SLOT_TAKEN, s.rejectReason(PlaceVeto("Cara", 2)))
    }

    @Test
    fun `active player cannot veto`() {
        assertEquals(RejectReason.ACTIVE_PLAYER_CANNOT_VETO, locked().rejectReason(PlaceVeto("Anna", 2)))
    }

    @Test
    fun `unknown player and invalid slot are rejected`() {
        assertEquals(RejectReason.UNKNOWN_PLAYER, locked().rejectReason(PlaceVeto("Zoe", 2)))
        assertEquals(RejectReason.INVALID_SLOT, locked().rejectReason(PlaceVeto("Ben", 4)))
    }

    @Test
    fun `per player per song limit`() {
        val one = locked().act(PlaceVeto("Ben", 2))
        assertEquals(RejectReason.VETO_LIMIT_REACHED, one.rejectReason(PlaceVeto("Ben", 3)))

        val two = locked(TimelineSettings.FIXED_VETOES.copy(vetoesPerPlayerPerSong = 2))
            .actAll(PlaceVeto("Ben", 2), PlaceVeto("Ben", 3))
        assertEquals(0, two.p("Ben").vetoes)
        assertEquals(RejectReason.SLOT_TAKEN, two.rejectReason(PlaceVeto("Cara", 3)))
    }

    @Test
    fun `needs a veto left`() {
        val s = locked(vetoes = mapOf("Ben" to 0))
        assertEquals(RejectReason.NOT_ENOUGH_VETOES, s.rejectReason(PlaceVeto("Ben", 2)))
    }

    @Test
    fun `spent on placement, undo refunds`() {
        val placed = locked().actAll(PlaceVeto("Ben", 2), PlaceVeto("Cara", 0))
        assertEquals(1, placed.p("Ben").vetoes)
        assertEquals(1, placed.p("Cara").vetoes)

        val undone = placed.act(UndoVeto)
        assertEquals(2, undone.p("Cara").vetoes)
        assertEquals(1, undone.p("Ben").vetoes)
        assertEquals(listOf(Veto("Ben", 2)), undone.round!!.vetoes)

        val all = undone.act(UndoVeto)
        assertEquals(2, all.p("Ben").vetoes)
        assertEquals(RejectReason.NOTHING_TO_UNDO, all.rejectReason(UndoVeto))
    }

    @Test
    fun `none before lock in and none after reveal`() {
        val placing = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), song = 1985)
        assertEquals(RejectReason.WRONG_PHASE, placing.rejectReason(PlaceVeto("Ben", 0)))

        val revealed = locked().actAll(PlaceVeto("Ben", 2), Reveal)
        assertEquals(RejectReason.WRONG_PHASE, revealed.rejectReason(PlaceVeto("Cara", 3)))
        assertEquals(RejectReason.WRONG_PHASE, revealed.rejectReason(UndoVeto))
    }

    @Test
    fun `invalid vetoes lose to a later valid one`() {
        // Song 1985, Anna locks slot 0 (invalid). Ben slot 3 invalid, Cara slot 2 valid.
        val s = timelineGame(
            listOf("Anna" to listOf(1970, 1980, 1990), "Ben" to listOf(1960), "Cara" to listOf(2010)),
            song = 1985,
        ).actAll(SelectSlot(0), LockIn, PlaceVeto("Ben", 3), PlaceVeto("Cara", 2), Reveal)
        assertEquals(TimelineOutcome.Stolen("Cara", 0), s.round!!.timelineOutcome)
    }
}
