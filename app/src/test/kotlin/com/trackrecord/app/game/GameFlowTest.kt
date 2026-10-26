package com.trackrecord.app.game

import com.trackrecord.engine.Card
import com.trackrecord.engine.ClosestYearSettings
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.GameState
import com.trackrecord.engine.Phase
import com.trackrecord.engine.ReduceResult
import com.trackrecord.engine.TimelineSettings
import com.trackrecord.engine.reveal.AutoReveal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The glue between the engine and the screens (REVIEW.md T-03): what happens by itself after an action
 * ([AutoStep]) and the veto window with auto-reveal ([RevealTimer]), on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameFlowTest {
    private fun GameState.then(action: GameAction): GameState = (GameEngine.reduce(this, action) as ReduceResult.Accepted).state

    private fun start(mode: GameMode, settings: GameSettings = GameSettings()): GameState =
        (
            GameEngine.reduce(
                null,
                GameAction.StartGame(mode, listOf("a", "b", "c"), (1L..12L).map { Card(it, 1950 + it.toInt() * 5) }, settings, seed = 1, currentYear = 2026),
            ) as ReduceResult.Accepted
            ).state

    /** A Timeline round locked in: a, b and c can veto, unless [vetoes] is 0. */
    private fun lockedIn(vetoes: Int = 2): GameState {
        val s = start(GameMode.TIMELINE, GameSettings(timeline = TimelineSettings(startingVetoes = vetoes, maxVetoes = 2))).then(GameAction.Draw)
        return s.then(GameAction.SelectSlot(0)).then(GameAction.LockIn)
    }

    // ---- StatsStep (feedback round 47) ----

    private fun session(state: GameState, profiles: Map<String, Long>) =
        GameSession(listOf("a", "b", "c").map { GamePlayer(it, it, it.uppercase(), 0, profileId = profiles[it]) }, state, emptyMap())

    @Test
    fun `stats - a round counts when Next commits it, for saved players only`() {
        val revealed = start(GameMode.CLOSEST_YEAR).then(GameAction.Draw).let { s ->
            val year = s.round!!.song.year
            s.then(GameAction.SubmitGuess(year - 3)).then(GameAction.SubmitGuess(year + 5)).then(GameAction.SubmitGuess(year - 1)).then(GameAction.Reveal)
        }
        val profiles = mapOf("a" to 7L, "c" to 9L)
        assertNull(StatsStep.after(session(revealed, profiles), revealed.then(GameAction.CorrectYear(revealed.round!!.song.year))), "still revealed")
        val step = StatsStep.after(session(revealed, profiles), revealed.then(GameAction.Next))!!
        assertEquals(1, step.round)
        assertEquals(revealed.seed, step.game)
        assertEquals(listOf(7L to 3, 9L to 1), step.rounds.map { (id, r) -> id to r.yearsOff })
        assertEquals(emptyList(), step.games)
        assertNull(StatsStep.after(session(revealed, emptyMap()), revealed.then(GameAction.Next)), "no saved players")
    }

    @Test
    fun `stats - a finished game counts for the saved players still playing, with the winners`() {
        // Two cards to win (below what the settings allow): the first right placement ends the game.
        val s = start(GameMode.TIMELINE).then(GameAction.Draw).let { it.copy(settings = it.settings.copy(timeline = it.settings.timeline.copy(cardsToWin = 2))) }
        val first = s.activePlayer.timeline.first().year
        val slot = if (s.round!!.song.year >= first) 1 else 0
        val revealed = s.then(GameAction.SelectSlot(slot)).then(GameAction.LockIn).then(GameAction.Reveal)
        val over = revealed.then(GameAction.Next)
        assertEquals(Phase.GAME_OVER, over.phase)
        val winner = over.result!!.winnerIds.single()
        val step = StatsStep.after(session(revealed, mapOf("a" to 1L, "b" to 2L, "c" to 3L)), over)!!
        assertEquals(1, step.rounds.size, "the winning turn")
        assertEquals(setOf(winner), step.games.filter { it.second }.map { (id, _) -> "abc"[id.toInt() - 1].toString() }.toSet())
        assertEquals(3, step.games.size)
    }

    // ---- AutoStep ----

    @Test
    fun `a new round is drawn by itself`() {
        assertEquals(GameAction.Draw, AutoStep.after(start(GameMode.TIMELINE), revealNow = false))
        assertNull(AutoStep.after(start(GameMode.TIMELINE).then(GameAction.Draw), revealNow = false), "placing: the player's move")
    }

    @Test
    fun `Closest Year reveals once everyone has guessed`() {
        var s = start(GameMode.CLOSEST_YEAR).then(GameAction.Draw)
        val year = s.round!!.song.year
        for ((i, id) in s.round!!.guessOrder.withIndex()) {
            assertNull(AutoStep.after(s, revealNow = false))
            s = s.then(GameAction.SubmitGuess(year - 10 - i, id))
        }
        assertEquals(GameAction.Reveal, AutoStep.after(s, revealNow = false))
    }

    @Test
    fun `with hidden guesses the reveal waits for the last guess, whoever makes it`() {
        var s = start(GameMode.CLOSEST_YEAR, GameSettings(closestYear = ClosestYearSettings(hiddenGuesses = true))).then(GameAction.Draw)
        val order = s.round!!.guessOrder
        s = s.then(GameAction.SubmitGuess(1980, order.last())).then(GameAction.SubmitGuess(1980, order.first()))
        assertNull(AutoStep.after(s, revealNow = false))
        s = s.then(GameAction.SubmitGuess(1990, order[1]))
        assertEquals(GameAction.Reveal, AutoStep.after(s, revealNow = false))
    }

    @Test
    fun `a Timeline round is revealed when the timer says so`() {
        assertNull(AutoStep.after(lockedIn(), revealNow = false))
        assertEquals(GameAction.Reveal, AutoStep.after(lockedIn(), revealNow = true))
    }

    // ---- RevealTimer ----

    /** The timer as GameController runs it: a reveal ends the vetoing phase, which stops the timer. */
    private class Timer(scope: TestScope, auto: AutoReveal = AutoReveal.INSTANT, despiteRemote: Boolean = false, remote: Set<String> = emptySet()) {
        var reveals = 0
        val remote = MutableStateFlow(remote)
        val timer: RevealTimer = RevealTimer(
            scope.backgroundScope,
            MutableStateFlow(RevealSettings(auto, vetoTimeSeconds = 10, despiteRemote = despiteRemote)),
            this.remote,
            clock = { scope.testScheduler.currentTime },
            reveal = {
                reveals++
                timer.stop()
            },
        )
    }

    @Test
    fun `nobody can veto, so it is revealed at once`() {
        val scope = TestScope()
        val t = Timer(scope)
        assertTrue(t.timer.onState(lockedIn(vetoes = 0)))
    }

    @Test
    fun `players on the host's phone who could veto hold the reveal until they decline`() {
        val scope = TestScope()
        val t = Timer(scope)
        val s = lockedIn()
        assertFalse(t.timer.onState(s))
        val others = GameEngine.vetoEligible(s)
        others.forEach(t.timer::decline)
        assertEquals(1, t.reveals)
    }

    @Test
    fun `a remote player locks Reveal for the veto time, and auto-reveal waits for their decision`() {
        val scope = TestScope()
        val s = lockedIn()
        val (local, remote) = GameEngine.vetoEligible(s).let { it.first() to it.last() }
        val t = Timer(scope, remote = setOf(remote))
        t.timer.onState(s)
        t.timer.decline(local)
        assertEquals(listOf(remote), t.timer.status.value!!.waitingFor)
        assertTrue(t.timer.status.value!!.lockedForMs > 0)
        scope.advanceTimeBy(15_000)
        scope.runCurrent()
        assertEquals(0L, t.timer.status.value!!.lockedForMs)
        assertEquals(0, t.reveals, "undecided remote players hold auto-reveal")
        t.timer.decline(remote)
        assertEquals(1, t.reveals)
    }

    @Test
    fun `despite remote players, auto-reveal comes after the veto time, but not while one picks a gap`() {
        val scope = TestScope()
        val s = lockedIn()
        val (local, remote) = GameEngine.vetoEligible(s).let { it.first() to it.last() }
        val t = Timer(scope, despiteRemote = true, remote = setOf(remote))
        t.timer.onState(s)
        t.timer.decline(local)
        t.timer.setPicking(remote, true)
        scope.advanceTimeBy(15_000)
        scope.runCurrent()
        assertEquals(0, t.reveals, "picking a gap holds the reveal")
        t.timer.setPicking(remote, false)
        scope.advanceTimeBy(500)
        scope.runCurrent()
        assertEquals(1, t.reveals)
    }

    @Test
    fun `auto-reveal after 5 s counts from when everyone decided`() {
        val scope = TestScope()
        val s = lockedIn()
        val t = Timer(scope, auto = AutoReveal.AFTER_5S)
        t.timer.onState(s)
        GameEngine.vetoEligible(s).forEach(t.timer::decline)
        assertEquals(0, t.reveals)
        scope.advanceTimeBy(4_000)
        scope.runCurrent()
        assertEquals(0, t.reveals)
        scope.advanceTimeBy(1_500)
        scope.runCurrent()
        assertEquals(1, t.reveals)
    }

    @Test
    fun `leaving the vetoing phase stops the timer`() {
        val scope = TestScope()
        val t = Timer(scope)
        t.timer.onState(lockedIn())
        t.timer.onState(lockedIn().then(GameAction.Reveal))
        assertNull(t.timer.status.value)
        assertEquals(Phase.REVEALED, lockedIn().then(GameAction.Reveal).phase)
    }
}
