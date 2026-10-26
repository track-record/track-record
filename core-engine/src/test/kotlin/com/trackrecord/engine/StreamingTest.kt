package com.trackrecord.engine

import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.ReplaceUnplayable
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameAction.SubmitGuess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** v2: a drawn streamed song that can't be played is replaced (SPEC v2, Playback; DECISIONS.md D-081). */
class StreamingTest {
    @Test
    fun `an unplayable song is replaced by the next pool song and goes back undrawn, free of charge`() {
        val s = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), song = 1990, settings = TimelineSettings.CLASSIC)
            .act(SelectSlot(1))
        val unplayable = s.round!!.song
        val replaced = s.act(ReplaceUnplayable)
        assertEquals(2000, replaced.round!!.song.year)
        assertEquals(1, replaced.round!!.number, "still the same round")
        assertNull(replaced.round!!.selectedSlot)
        assertEquals(listOf(2001, 2002, 1990), replaced.pool.map { it.year })
        assertEquals(unplayable, replaced.pool.last())
        assertEquals(s.p("Anna").vetoes, replaced.p("Anna").vetoes, "no cost")
        assertEquals(s.discarded, replaced.discarded)
        assertEquals(Phase.PLACING, replaced.phase)
    }

    @Test
    fun `closest year keeps the guess order and clears the guesses`() {
        val s = closestGame(listOf("Anna", "Ben"), year = 1990).act(SubmitGuess(1985))
        val replaced = s.act(ReplaceUnplayable)
        assertEquals(2000, replaced.round!!.song.year)
        assertEquals(s.round!!.guessOrder, replaced.round!!.guessOrder)
        assertEquals(emptyList(), replaced.round!!.guesses)
        assertEquals(Phase.GUESSING, replaced.phase)
    }

    @Test
    fun `replacing needs another song and an unrevealed round before lock in`() {
        val lonely = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), song = 1990, pool = emptyList())
        assertEquals(RejectReason.POOL_EMPTY, lonely.rejectReason(ReplaceUnplayable))
        val locked = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), song = 1990).actAll(SelectSlot(1), LockIn)
        assertEquals(RejectReason.WRONG_PHASE, locked.rejectReason(ReplaceUnplayable))
    }
}
