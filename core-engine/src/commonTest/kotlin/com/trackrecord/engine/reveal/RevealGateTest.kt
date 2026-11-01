package com.trackrecord.engine.reveal

import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.TimelineSettings
import com.trackrecord.engine.act
import com.trackrecord.engine.actAll
import com.trackrecord.engine.timelineGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevealGateTest {
    /** Anna active and locked in; Ben and Cara can veto (2 vetoes each by default). */
    private fun locked(vetoes: Map<String, Int> = emptyMap(), settings: TimelineSettings = TimelineSettings.FIXED_VETOES) =
        timelineGame(
            listOf("Anna" to listOf(1970, 1980, 1990), "Ben" to listOf(1960), "Cara" to listOf(2010)),
            song = 1985,
            vetoes = vetoes,
            settings = settings,
        ).actAll(SelectSlot(1), LockIn)

    private val window = 5_000L

    @Test
    fun `veto eligibility lists other players with a veto left in seat order`() {
        assertEquals(listOf("Ben", "Cara"), GameEngine.vetoEligible(locked()))
        assertEquals(listOf("Cara"), GameEngine.vetoEligible(locked(vetoes = mapOf("Ben" to 0))))
        assertEquals(listOf("Cara"), GameEngine.vetoEligible(locked().act(PlaceVeto("Ben", 2))))
    }

    @Test
    fun `nobody is eligible when every gap is taken`() {
        // Anna has one card: two gaps, one locked, one vetoed.
        val s = timelineGame(listOf("Anna" to listOf(1990), "Ben" to listOf(1960), "Cara" to listOf(2010)), song = 1985)
            .actAll(SelectSlot(0), LockIn, PlaceVeto("Ben", 1))
        assertEquals(emptyList(), GameEngine.vetoEligible(s))
    }

    @Test
    fun `an undecided remote player locks reveal for the veto window`() {
        val s = locked()
        val round = VetoRound(1, lockedAtMs = 1_000)
        val status = RevealGate.status(s, setOf("Ben"), round, window, AutoReveal.INSTANT, nowMs = 3_000)
        assertEquals(3_000, status.lockedForMs)
        assertEquals(listOf("Ben"), status.waitingFor)
        assertNull(status.autoRevealInMs, "Cara (local) could still veto")

        val later = RevealGate.status(s, setOf("Ben"), round, window, AutoReveal.INSTANT, nowMs = 6_000)
        assertFalse(later.locked, "the window is over")
    }

    @Test
    fun `a remote player picking a gap for a veto locks reveal until it is placed`() {
        val s = locked()
        val picking = VetoRound(1, lockedAtMs = 0, vetoing = setOf("Ben"))
        val late = RevealGate.status(s, setOf("Ben"), picking, window, AutoReveal.INSTANT, nowMs = 60_000)
        assertTrue(late.locked, "no time limit while Ben picks")
        assertEquals(listOf("Ben"), late.picking)
        assertNull(late.autoRevealInMs)

        // Placed (the per-song limit of 1 reached): no longer picking, whatever the flag says.
        val placed = s.act(PlaceVeto("Ben", 2))
        val after = RevealGate.status(placed, setOf("Ben"), picking, window, AutoReveal.OFF, nowMs = 60_000)
        assertFalse(after.locked)
        assertEquals(emptyList(), after.picking)

        // A player picking on the host's phone holds it too, so a co-host sees "Veto pending" (feedback round 27).
        assertEquals(listOf("Ben"), RevealGate.status(s, emptySet(), picking, window, AutoReveal.OFF, nowMs = 60_000).picking)
    }

    @Test
    fun `local players never lock reveal`() {
        val status = RevealGate.status(locked(), emptySet(), VetoRound(1, 0), window, AutoReveal.INSTANT, nowMs = 0)
        assertFalse(status.locked)
        assertEquals(emptyList(), status.waitingFor)
    }

    @Test
    fun `reveal unlocks early when every remote player declined or vetoed`() {
        val s = locked()
        val declined = VetoRound(1, 0, declined = setOf("Ben"))
        assertFalse(RevealGate.status(s, setOf("Ben"), declined, window, AutoReveal.OFF, nowMs = 100).locked)

        // A veto with the per-song limit of 1 is a decision too.
        val vetoed = s.act(PlaceVeto("Ben", 2))
        assertFalse(RevealGate.status(vetoed, setOf("Ben"), VetoRound(1, 0), window, AutoReveal.OFF, nowMs = 100).locked)
    }

    @Test
    fun `a remote player who may veto twice is waited for until they decline`() {
        val s = locked(settings = TimelineSettings.FIXED_VETOES.copy(vetoesPerPlayerPerSong = 2)).act(PlaceVeto("Ben", 2))
        assertTrue(RevealGate.status(s, setOf("Ben"), VetoRound(1, 0), window, AutoReveal.OFF, nowMs = 100).locked)
    }

    @Test
    fun `auto-reveal when nobody can veto`() {
        val s = locked(vetoes = mapOf("Ben" to 0, "Cara" to 0))
        val round = RevealGate.advance(s, emptySet(), VetoRound(1, 0), nowMs = 0)
        assertEquals(0L, RevealGate.status(s, emptySet(), round, window, AutoReveal.INSTANT, nowMs = 0).autoRevealInMs)
        assertEquals(5_000L, RevealGate.status(s, emptySet(), round, window, AutoReveal.AFTER_5S, nowMs = 0).autoRevealInMs)
        assertEquals(7_000L, RevealGate.status(s, emptySet(), round, window, AutoReveal.AFTER_10S, nowMs = 3_000).autoRevealInMs)
        assertNull(RevealGate.status(s, emptySet(), round, window, AutoReveal.OFF, nowMs = 0).autoRevealInMs)
    }

    @Test
    fun `auto-reveal when only remote players could veto and all declined`() {
        val s = locked()
        val remote = setOf("Ben", "Cara")
        var round = RevealGate.advance(s, remote, VetoRound(1, 0, declined = setOf("Ben")), nowMs = 1_000)
        assertNull(round.readySinceMs, "Cara hasn't decided")
        round = RevealGate.advance(s, remote, round.copy(declined = setOf("Ben", "Cara")), nowMs = 2_000)
        assertEquals(2_000L, round.readySinceMs)
        assertEquals(3_000L, RevealGate.status(s, remote, round, window, AutoReveal.AFTER_5S, nowMs = 4_000).autoRevealInMs)
    }

    @Test
    fun `no auto-reveal while a local player could veto`() {
        val s = locked()
        val round = RevealGate.advance(s, setOf("Ben"), VetoRound(1, 0, declined = setOf("Ben")), nowMs = 0)
        assertNull(round.readySinceMs)
        assertNull(RevealGate.status(s, setOf("Ben"), round, window, AutoReveal.INSTANT, nowMs = 0).autoRevealInMs)
    }

    @Test
    fun `a local player's don't veto counts for auto-reveal too`() {
        val s = locked()
        val round = RevealGate.advance(s, setOf("Ben"), VetoRound(1, 0, declined = setOf("Ben", "Cara")), nowMs = 0)
        assertEquals(0L, RevealGate.status(s, setOf("Ben"), round, window, AutoReveal.INSTANT, nowMs = 0).autoRevealInMs)
    }

    @Test
    fun `a veto on the host's phone no longer cancels auto-reveal`() {
        val s = locked(vetoes = mapOf("Cara" to 0)).act(PlaceVeto("Ben", 2))
        val round = VetoRound(1, 0)
        assertTrue(RevealGate.ready(s, round))
        assertEquals(0L, RevealGate.status(s, emptySet(), RevealGate.advance(s, emptySet(), round, 0), window, AutoReveal.INSTANT, 0).autoRevealInMs)
    }

    @Test
    fun `the delay restarts when the condition stops holding`() {
        // Ben declined, Cara placed her only veto: nobody else could veto.
        val vetoed = locked().act(PlaceVeto("Cara", 2))
        val remote = setOf("Ben", "Cara")
        val ready = RevealGate.advance(vetoed, remote, VetoRound(1, 0, declined = setOf("Ben")), nowMs = 1_000)
        assertEquals(1_000L, ready.readySinceMs)
        // The veto is undone: Cara could veto again and hasn't decided.
        val undone = vetoed.act(GameAction.UndoVeto)
        val notReady = RevealGate.advance(undone, remote, ready, nowMs = 2_000)
        assertNull(notReady.readySinceMs)
    }

    @Test
    fun `auto-reveal can go ahead without undecided remote players once the veto time is over`() {
        val s = locked()
        val remote = setOf("Ben", "Cara")
        val round = VetoRound(1, lockedAtMs = 0)
        assertFalse(RevealGate.ready(s, round, remote, nowMs = 9_000, ignoreRemoteAfterMs = 10_000), "still within the veto time")
        assertTrue(RevealGate.ready(s, round, remote, nowMs = 10_000, ignoreRemoteAfterMs = 10_000), "the time is over (feedback round 32)")
        assertFalse(RevealGate.ready(s, round, remote, nowMs = 10_000), "off by default")
        assertFalse(RevealGate.ready(s, round.copy(vetoing = setOf("Ben")), remote, nowMs = 60_000, ignoreRemoteAfterMs = 10_000), "a player picking a gap still counts")
        assertFalse(RevealGate.ready(s, round, setOf("Ben"), nowMs = 60_000, ignoreRemoteAfterMs = 10_000), "a player on the host's phone still has to decide")
    }
}
