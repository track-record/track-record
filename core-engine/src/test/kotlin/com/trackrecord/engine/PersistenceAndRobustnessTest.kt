package com.trackrecord.engine

import com.trackrecord.engine.GameAction.BuyCard
import com.trackrecord.engine.GameAction.Draw
import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameAction.SetNamed
import com.trackrecord.engine.GameAction.SubmitGuess
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistenceAndRobustnessTest {
    private fun assertRoundTrip(state: GameState) {
        val restored = GameJson.decode(GameJson.encode(state))
        assertEquals(state, restored, "round trip in phase ${state.phase}")
    }

    @Test
    fun `save and restore round trip in every timeline phase, including mid-veto`() {
        val settings = GameSettings(
            timeline = TimelineSettings.CLASSIC.copy(startingVetoes = 4),
            common = CommonSettings(playlistIds = listOf(3, 4), clipLimitSeconds = 30),
        )
        var s = startGame(GameMode.TIMELINE, listOf("Anna", "Ben", "Cara"), (1950..2000).toList(), settings)
        assertRoundTrip(s) // AWAITING_DRAW
        s = s.act(BuyCard("Ben"))
        assertRoundTrip(s) // with lastPurchase
        s = s.actAll(Draw, SelectSlot(0))
        assertRoundTrip(s) // PLACING with a selection
        s = s.actAll(LockIn, PlaceVeto("Cara", 1))
        assertEquals(Phase.VETOING, s.phase)
        assertRoundTrip(s) // mid-veto
        s = s.actAll(Reveal, SetNamed(true))
        assertRoundTrip(s) // REVEALED with an outcome
        s = s.act(Next)
        assertRoundTrip(s)

        // A restored game continues exactly like the original
        val restored = GameJson.decode(GameJson.encode(s))
        assertEquals(s.actAll(Draw, SelectSlot(1), LockIn), restored.actAll(Draw, SelectSlot(1), LockIn))
    }

    @Test
    fun `save and restore round trip in every closest year phase and game over`() {
        var s = startGame(GameMode.CLOSEST_YEAR, listOf("Anna", "Ben"), listOf(1980), GameSettings(closestYear = ClosestYearSettings(winsToWin = 1)))
        assertRoundTrip(s)
        s = s.actAll(Draw, SubmitGuess(1990))
        assertRoundTrip(s) // GUESSING
        s = s.actAll(SubmitGuess(2000), Reveal)
        assertRoundTrip(s) // REVEALED
        s = s.act(Next)
        assertEquals(Phase.GAME_OVER, s.phase)
        assertRoundTrip(s)
    }

    @Test
    fun `settings snapshot round trip`() {
        val settings = GameSettings(TimelineSettings.CLASSIC, ClosestYearSettings(winsToWin = 5), CommonSettings(yearFrom = 1970, yearTo = 1999))
        assertEquals(settings, GameJson.decodeSettings(GameJson.encodeSettings(settings)))
    }

    /** Random actions never crash the engine, and rejected ones never change the state. */
    @Test
    fun `random actions are accepted or rejected, never crash`() {
        val random = Random(1234)
        val ids = listOf("A", "B", "C", "D")
        repeat(200) { game ->
            val mode = if (game % 2 == 0) GameMode.TIMELINE else GameMode.CLOSEST_YEAR
            val settings = GameSettings(
                timeline = TimelineSettings.CLASSIC.copy(cardsToWin = 4, vetoesPerPlayerPerSong = 2),
                closestYear = ClosestYearSettings(winsToWin = 3, distinctGuesses = random.nextBoolean()),
            )
            var s: GameState = startGame(mode, ids, (1..30).map { 1960 + random.nextInt(40) }, settings, seed = game.toLong())
            repeat(300) {
                val action = randomAction(random, ids)
                val before = s
                when (val r = GameEngine.reduce(s, action)) {
                    is ReduceResult.Accepted -> s = r.state
                    is ReduceResult.Rejected -> assertEquals(before, r.state)
                }
                assertInvariants(s)
            }
        }
    }

    private fun randomAction(random: Random, ids: List<String>): GameAction = when (random.nextInt(14)) {
        0, 1 -> Draw
        2 -> SelectSlot(random.nextInt(-1, 6))
        3 -> GameAction.Skip
        4 -> LockIn
        5 -> PlaceVeto(ids.random(random), random.nextInt(-1, 6))
        6 -> GameAction.UndoVeto
        7 -> Reveal
        8 -> GameAction.CorrectYear(random.nextInt(1890, 2030))
        9 -> SetNamed(random.nextBoolean())
        10 -> Next
        11 -> BuyCard(ids.random(random))
        12 -> SubmitGuess(random.nextInt(1890, 2030))
        else -> GameAction.UndoGuess
    }

    private fun assertInvariants(s: GameState) {
        val tl = s.settings.timeline
        for (p in s.players) {
            assertTrue(p.vetoes >= 0, "negative vetoes")
            if (s.mode == GameMode.TIMELINE) {
                assertTrue(p.vetoes <= tl.maxVetoes, "vetoes above max")
                assertEquals(p.timeline.map { it.year }.sorted(), p.timeline.map { it.year }, "timeline unsorted")
            }
        }
        // No song is in two places at once
        val all = s.players.flatMap { it.timeline + it.collection } + s.pool + s.discarded + listOfNotNull(s.round?.song)
        assertEquals(all.size, all.map { it.songId }.toSet().size, "duplicate song")
        assertEquals(30, all.size, "songs lost")
        if (s.phase == Phase.GAME_OVER) assertTrue(s.result != null && s.result.winnerIds.isNotEmpty())
    }
}
