package com.trackrecord.engine

import com.trackrecord.engine.GameAction.ApplyLookupYear
import com.trackrecord.engine.GameAction.CorrectYear
import com.trackrecord.engine.GameAction.Draw
import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameAction.StartGame
import com.trackrecord.engine.GameAction.SubmitGuess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GameFlowTest {
    @Test
    fun `empty pool ends a timeline game, most cards wins`() {
        val s = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), song = 1990, pool = emptyList())
            .actAll(SelectSlot(1), LockIn, Reveal, Next, Draw)
        assertEquals(Phase.GAME_OVER, s.phase)
        assertEquals(GameResult(listOf("Anna"), EndReason.POOL_EMPTY), s.result)
    }

    @Test
    fun `empty pool ends a timeline game with a shared win on ties`() {
        val s = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960), "Cara" to listOf(1950, 2000)), song = 1990, pool = emptyList())
            .actAll(SelectSlot(1), LockIn, Reveal, Next, Draw)
        assertEquals(GameResult(listOf("Anna", "Cara"), EndReason.POOL_EMPTY), s.result)
    }

    @Test
    fun `empty pool ends a closest year game with a shared win on ties`() {
        val s = closestGame(listOf("Anna", "Ben", "Cara"), 1984, pool = emptyList())
            .actAll(SubmitGuess(1990), SubmitGuess(1960), SubmitGuess(1970), Reveal, Next, Draw)
        assertEquals(GameResult(listOf("Anna"), EndReason.POOL_EMPTY), s.result)

        val tied = closestGame(listOf("Anna", "Ben"), 1984, pool = emptyList()).copy(
            players = listOf(PlayerState("Anna", wins = 2), PlayerState("Ben", wins = 3)),
        ).actAll(SubmitGuess(1985), SubmitGuess(1960), Reveal, Next, Draw)
        assertEquals(GameResult(listOf("Anna", "Ben"), EndReason.POOL_EMPTY), tied.result)
    }

    @Test
    fun `start game deals starting cards and vetoes`() {
        val settings = GameSettings(timeline = TimelineSettings.CLASSIC)
        val s = startGame(GameMode.TIMELINE, listOf("Anna", "Ben", "Cara"), (1960..1979).toList(), settings)
        assertEquals(Phase.AWAITING_DRAW, s.phase)
        assertEquals(0, s.activeSeat)
        assertTrue(s.players.all { it.timeline.size == 1 && it.vetoes == 2 })
        assertEquals(17, s.pool.size)
        val all = s.players.flatMap { it.timeline } + s.pool
        assertEquals(20, all.map { it.songId }.toSet().size)
    }

    @Test
    fun `shuffle is deterministic for a seed`() {
        val songs = (1..50).map { Card(it.toLong(), 1950 + it) }
        assertEquals(GameEngine.shufflePool(songs, 99), GameEngine.shufflePool(songs.reversed(), 99))
        assertNotEquals(GameEngine.shufflePool(songs, 99), GameEngine.shufflePool(songs, 100))
    }

    @Test
    fun `start game validation`() {
        fun reason(ids: List<String>, songs: Int, settings: GameSettings = GameSettings(), mode: GameMode = GameMode.TIMELINE): RejectReason {
            val r = GameEngine.reduce(null, StartGame(mode, ids, (1..songs).map { card(1950 + it) }, settings, 1, CURRENT_YEAR))
            assertIs<ReduceResult.Rejected>(r)
            return r.reason
        }
        assertEquals(RejectReason.TOO_FEW_PLAYERS, reason(listOf("A"), 10))
        assertEquals(RejectReason.TOO_MANY_PLAYERS, reason((1..11).map { "P$it" }, 100))
        assertEquals(RejectReason.DUPLICATE_PLAYER, reason(listOf("A", "A"), 10))
        assertEquals(RejectReason.POOL_TOO_SMALL, reason(listOf("A", "B"), 2))
        assertEquals(RejectReason.POOL_TOO_SMALL, reason(listOf("A", "B"), 0, mode = GameMode.CLOSEST_YEAR))
        assertEquals(
            RejectReason.INVALID_SETTINGS,
            reason(listOf("A", "B"), 10, GameSettings(timeline = TimelineSettings(cardsToWin = 50))),
        )
        assertEquals(RejectReason.NO_GAME, (GameEngine.reduce(null, Draw) as ReduceResult.Rejected).reason)
    }

    @Test
    fun `lookup years apply to undrawn songs and the current song before reveal`() {
        val s = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), song = 1990)
        val next = s.pool.first()
        assertEquals(1975, s.act(ApplyLookupYear(next.songId, 1975)).pool.first().year)

        val current = s.round!!.song.songId
        val updated = s.act(ApplyLookupYear(current, 1975))
        assertEquals(1975, updated.round!!.song.year)
        assertEquals(TimelineOutcome.Kept("Anna", 0), updated.actAll(SelectSlot(0), LockIn, Reveal).round!!.timelineOutcome)

        val revealed = s.actAll(SelectSlot(1), LockIn, Reveal)
        assertEquals(RejectReason.SONG_NOT_FOUND, revealed.rejectReason(ApplyLookupYear(current, 1975)))
        assertEquals(RejectReason.SONG_NOT_FOUND, s.rejectReason(ApplyLookupYear(-5, 1975)))
    }

    @Test
    fun `wrong year recomputes the timeline outcome before Next`() {
        val revealed = timelineGame(
            listOf("Anna" to listOf(1980, 1990), "Ben" to listOf(1960), "Cara" to listOf(2010)),
            song = 1985,
        ).actAll(SelectSlot(1), LockIn, PlaceVeto("Ben", 2), Reveal)
        assertEquals(TimelineOutcome.Kept("Anna", 1), revealed.round!!.timelineOutcome)

        val corrected = revealed.act(CorrectYear(1995))
        assertEquals(TimelineOutcome.Stolen("Ben", 1), corrected.round!!.timelineOutcome)
        val done = corrected.act(Next)
        assertEquals(listOf(1960, 1995), done.years("Ben"))

        assertEquals(RejectReason.WRONG_PHASE, timelineGame(listOf("A" to listOf(1980), "B" to emptyList<Int>()), 1990).rejectReason(CorrectYear(1990)))
        assertEquals(RejectReason.YEAR_OUT_OF_RANGE, revealed.rejectReason(CorrectYear(CURRENT_YEAR + 1)))
    }

    @Test
    fun `game over rejects every action`() {
        val over = timelineGame(listOf("Anna" to listOf(1980), "Ben" to listOf(1960)), song = 1990, pool = emptyList())
            .actAll(SelectSlot(1), LockIn, Reveal, Next, Draw)
        assertEquals(RejectReason.WRONG_PHASE, over.rejectReason(Draw))
        assertEquals(RejectReason.WRONG_PHASE, over.rejectReason(Next))
    }
}
