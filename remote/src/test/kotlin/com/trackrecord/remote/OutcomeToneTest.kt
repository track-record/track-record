package com.trackrecord.remote

import com.trackrecord.engine.Card
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.GameState
import com.trackrecord.engine.ReduceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutcomeToneTest {
    private fun GameState.act(a: GameAction) = (GameEngine.reduce(this, a) as ReduceResult.Accepted).state

    /** Anna (1970), Ben (1990), Cara (2010); 1985 is drawn, Anna is active. */
    private fun timeline(): GameState {
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(
                GameMode.TIMELINE, listOf("anna", "ben", "cara"),
                listOf(Card(1, 1970), Card(2, 1990), Card(3, 2010), Card(4, 1985), Card(5, 2001)),
                GameSettings(), seed = 1, currentYear = 2026,
            ),
        ) as ReduceResult.Accepted
        val dealt = start.state.copy(
            players = start.state.players.mapIndexed { i, p -> p.copy(timeline = listOf(Card(i + 1L, listOf(1970, 1990, 2010)[i]))) },
            pool = listOf(Card(4, 1985), Card(5, 2001)),
        )
        return dealt.act(GameAction.Draw)
    }

    @Test
    fun `a steal is green for the thief, red for the victim, orange for everyone else`() {
        // Anna places before 1970 (wrong); Ben vetoes after 1970 (right).
        val s = timeline().act(GameAction.SelectSlot(0)).act(GameAction.LockIn).act(GameAction.PlaceVeto("ben", 1)).act(GameAction.Reveal)
        assertEquals(OutcomeTone.WON, OutcomeTones.forViewer(s, "ben"))
        assertEquals(OutcomeTone.LOST, OutcomeTones.forViewer(s, "anna"))
        assertEquals(OutcomeTone.OTHER, OutcomeTones.forViewer(s, "cara"))
    }

    @Test
    fun `a kept card is green for the keeper, red for a failed veto, orange for others`() {
        val s = timeline().act(GameAction.SelectSlot(1)).act(GameAction.LockIn).act(GameAction.PlaceVeto("cara", 0)).act(GameAction.Reveal)
        assertEquals(OutcomeTone.WON, OutcomeTones.forViewer(s, "anna"))
        assertEquals(OutcomeTone.LOST, OutcomeTones.forViewer(s, "cara"))
        assertEquals(OutcomeTone.OTHER, OutcomeTones.forViewer(s, "ben"))
    }

    @Test
    fun `nobody gets it - no outline for anyone`() {
        // Anna places the 1985 song before 1970 (wrong) and nobody vetoes: the card is discarded.
        val s = timeline().act(GameAction.SelectSlot(0)).act(GameAction.LockIn).act(GameAction.Reveal)
        assertNull(OutcomeTones.forViewer(s, "anna"))
        assertNull(OutcomeTones.forViewer(s, "ben"))
        assertNull(OutcomeTones.forHost(s, emptySet()))
    }

    @Test
    fun `the host shows one local player's view, else green for a normal win and orange for a steal`() {
        val stolen = timeline().act(GameAction.SelectSlot(0)).act(GameAction.LockIn).act(GameAction.PlaceVeto("ben", 1)).act(GameAction.Reveal)
        assertEquals(OutcomeTone.LOST, OutcomeTones.forHost(stolen, setOf("ben", "cara")), "Anna is the only one on the host")
        assertEquals(OutcomeTone.OTHER, OutcomeTones.forHost(stolen, setOf("ben")), "Anna and Cara both play on the host")
        val kept = timeline().act(GameAction.SelectSlot(1)).act(GameAction.LockIn).act(GameAction.Reveal)
        assertEquals(OutcomeTone.WON, OutcomeTones.forHost(kept, emptySet()))
    }

    @Test
    fun `closest year is green for the winner, orange for the others`() {
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(GameMode.CLOSEST_YEAR, listOf("anna", "ben"), listOf(Card(4, 1985), Card(5, 2001)), GameSettings(), seed = 3, currentYear = 2026),
        ) as ReduceResult.Accepted
        val drawn = start.state.act(GameAction.Draw)
        val year = drawn.round!!.song.year
        val revealed = drawn.act(GameAction.SubmitGuess(year + 1)).act(GameAction.SubmitGuess(year + 9)).act(GameAction.Reveal)
        val winner = revealed.round!!.winnerIds.first()
        val loser = revealed.players.first { it.id != winner }.id
        assertEquals(OutcomeTone.WON, OutcomeTones.forViewer(revealed, winner))
        assertEquals(OutcomeTone.OTHER, OutcomeTones.forViewer(revealed, loser))
    }
}
