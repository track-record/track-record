package com.trackrecord.engine

import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameAction.SetNamed
import com.trackrecord.engine.GameAction.SetNamedBy
import kotlin.test.Test
import kotlin.test.assertEquals

/** Who can call out song and artist in Timeline (feedback round 19). */
class TimelineNamingTest {
    /** Anna active (wrong slot), Ben and Cara veto; Dan doesn't. Max 5 vetoes, everyone starts with 2. */
    private fun revealed(callers: NamingCallers) = timelineGame(
        listOf("Anna" to listOf(1970, 1980, 1990), "Ben" to listOf(1960), "Cara" to listOf(2010), "Dan" to listOf(2000)),
        song = 1985,
        settings = TimelineSettings.CLASSIC.copy(namingCallers = callers),
    ).actAll(SelectSlot(0), LockIn, PlaceVeto("Cara", 3), PlaceVeto("Ben", 2), Reveal)

    @Test
    fun `active player only - the classic rule`() {
        val s = revealed(NamingCallers.ACTIVE)
        assertEquals(listOf("Anna"), GameEngine.namingCandidates(s))
        assertEquals(RejectReason.NOT_ALLOWED_TO_NAME, s.rejectReason(SetNamedBy("Ben", true)))
        assertEquals(3, s.act(SetNamed(true)).act(Next).p("Anna").vetoes)
    }

    @Test
    fun `vetoers can name too, in veto order, and get their veto back`() {
        val s = revealed(NamingCallers.VETOERS)
        assertEquals(listOf("Anna", "Cara", "Ben"), GameEngine.namingCandidates(s))
        assertEquals(RejectReason.NOT_ALLOWED_TO_NAME, s.rejectReason(SetNamedBy("Dan", true)))
        assertEquals(1, s.p("Cara").vetoes, "spent one on the veto")
        val next = s.act(SetNamedBy("Cara", true)).act(Next)
        assertEquals(2, next.p("Cara").vetoes, "refunded")
        assertEquals(2, next.p("Anna").vetoes)
    }

    @Test
    fun `everyone can name - vetoers listed first, one namer at a time`() {
        val s = revealed(NamingCallers.ALL)
        assertEquals(listOf("Anna", "Cara", "Ben", "Dan"), GameEngine.namingCandidates(s))
        val moved = s.actAll(SetNamedBy("Dan", true), SetNamed(true))
        assertEquals("Anna", moved.round!!.namedById, "marking the active player moves the mark")
        assertEquals(3, moved.act(SetNamedBy("Anna", false)).act(SetNamedBy("Dan", true)).act(Next).p("Dan").vetoes)
    }

    @Test
    fun `a named bonus stays within the max`() {
        val s = revealed(NamingCallers.ALL).let { it.copy(players = it.players.map { p -> if (p.id == "Dan") p.copy(vetoes = 5) else p }) }
        assertEquals(5, s.act(SetNamedBy("Dan", true)).act(Next).p("Dan").vetoes)
    }
}
