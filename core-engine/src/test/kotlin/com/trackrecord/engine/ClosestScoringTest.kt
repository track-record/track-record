package com.trackrecord.engine

import com.trackrecord.engine.GameAction.BuyCard
import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SubmitGuess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Closest Year's scoring modes, tie breaker and hidden guesses (feedback round 36). */
class ClosestScoringTest {
    private val five = listOf("Anna", "Ben", "Cara", "Dan", "Eve")

    private fun guesses(vararg years: Int) = years.map { SubmitGuess(it) }.toTypedArray()

    private val rank = ClosestYearSettings.RANKING
    private val distance = ClosestYearSettings.DISTANCE

    @Test
    fun `rank gives n - 1 points to the closest down to 0 for the farthest`() {
        val s = closestGame(five, 1984, rank).actAll(*guesses(1990, 1983, 2000, 1970, 1986), Reveal)
        assertEquals(mapOf("Ben" to 4, "Eve" to 3, "Anna" to 2, "Dan" to 1, "Cara" to 0), s.round!!.points)
        assertEquals(listOf("Ben"), s.round.winnerIds)
        val next = s.act(Next)
        assertEquals(listOf(2, 4, 0, 1, 3), five.map { next.p(it).points })
        assertTrue(five.all { next.p(it).collection.isEmpty() }, "no cards in Rank")
    }

    @Test
    fun `rank ties both score the better place and the next place is skipped`() {
        // Anna and Ben are both 2 off; Cara is third.
        val s = closestGame(five, 1984, rank).actAll(*guesses(1986, 1982, 1990, 1970, 2010), Reveal)
        assertEquals(mapOf("Anna" to 4, "Ben" to 4, "Cara" to 2, "Dan" to 1, "Eve" to 0), s.round!!.points)
        assertEquals(listOf("Anna", "Ben"), s.round.winnerIds)
    }

    @Test
    fun `rank ties with first scores are placed in guess order`() {
        val first = rank.copy(tieBreaker = TieBreaker.FIRST_SCORES)
        val s = closestGame(five, 1984, first).actAll(*guesses(1986, 1982, 1990, 1970, 2010), Reveal)
        assertEquals(mapOf("Anna" to 4, "Ben" to 3, "Cara" to 2, "Dan" to 1, "Eve" to 0), s.round!!.points)
        assertEquals(listOf("Anna"), s.round.winnerIds)
    }

    @Test
    fun `distance gives x minus the years off, never below 0`() {
        val s = closestGame(five, 1984, distance).actAll(*guesses(1984, 1986, 1989, 1990, 1960), Reveal)
        assertEquals(mapOf("Anna" to 5, "Ben" to 3, "Cara" to 0, "Dan" to 0, "Eve" to 0), s.round!!.points)
        assertEquals(listOf("Anna"), s.round.winnerIds)
        val far = closestGame(five, 1984, distance).actAll(*guesses(1960, 1961, 1962, 1963, 1964), Reveal)
        assertEquals(emptyList(), far.round!!.winnerIds, "nobody scored")
    }

    @Test
    fun `an exact guess doesn't end the round in rank and distance`() {
        for (rules in listOf(rank, distance)) {
            val s = closestGame(five, 1984, rules).act(SubmitGuess(1984))
            assertEquals(Phase.GUESSING, s.phase)
        }
    }

    @Test
    fun `the target is wins to win times a perfect round`() {
        val ranked = closestGame(five, 1984, rank.copy(winsToWin = 10))
        assertEquals(40, GameEngine.target(ranked))
        assertEquals(50, GameEngine.target(closestGame(five, 1984, distance.copy(winsToWin = 10))))
        assertEquals(10, GameEngine.target(closestGame(five, 1984, ClosestYearSettings.CLOSEST.copy(winsToWin = 10))))
    }

    @Test
    fun `reaching the target in rank ends the game with the highest score`() {
        // 2 players: 1 point for the closest, target 1 × 1.
        val s = closestGame(listOf("Anna", "Ben"), 1984, rank.copy(winsToWin = 1)).actAll(*guesses(1990, 1985), Reveal, Next)
        assertEquals(Phase.GAME_OVER, s.phase)
        assertEquals(listOf("Ben"), s.result!!.winnerIds)
    }

    @Test
    fun `a buy is worth a perfect round and shows the song without collecting it`() {
        val s = closestGame(five, 1984, rank).let { it.copy(players = it.players.map { p -> p.copy(skips = 3) }) }
        val bought = s.act(BuyCard("Cara"))
        assertEquals(4, bought.p("Cara").points)
        assertTrue(bought.p("Cara").collection.isEmpty())
        assertEquals(bought.lastPurchase!!.card, bought.discarded.last())
        val d = closestGame(five, 1984, distance).let { it.copy(players = it.players.map { p -> p.copy(skips = 3) }) }
        assertEquals(5, d.act(BuyCard("Cara")).p("Cara").points)
    }

    @Test
    fun `hidden guesses come in any order, may repeat and don't end the round when exact`() {
        val hidden = ClosestYearSettings.CLOSEST.copy(hiddenGuesses = true)
        val s = closestGame(five.take(3), 1984, hidden)
        val order = s.round!!.guessOrder
        val guessed = s.actAll(SubmitGuess(1984, order[2]), SubmitGuess(1984, order[0]))
        assertEquals(Phase.GUESSING, guessed.phase, "an exact hit doesn't end the round")
        assertEquals(listOf(order[1]), guessed.round!!.waitingFor)
        assertEquals(RejectReason.NOT_A_GUESSER, guessed.rejectReason(SubmitGuess(1990, order[0])), "one guess each")
        val revealed = guessed.actAll(SubmitGuess(1990, order[1]), Reveal)
        assertEquals(listOf(order[2], order[0]), revealed.round!!.winnerIds, "both score on the tie")
        val first = closestGame(five.take(3), 1984, hidden.copy(tieBreaker = TieBreaker.FIRST_SCORES))
            .actAll(SubmitGuess(1984, order[2]), SubmitGuess(1984, order[0]), SubmitGuess(1990, order[1]), Reveal)
        assertEquals(listOf(order[2]), first.round!!.winnerIds, "the one locked in first")
    }

    @Test
    fun `guesses in turn can't be made out of turn`() {
        val s = closestGame(five, 1984)
        assertEquals(RejectReason.NOT_A_GUESSER, s.rejectReason(SubmitGuess(1990, s.round!!.guessOrder[1])))
        assertEquals(RejectReason.UNKNOWN_PLAYER, s.rejectReason(SubmitGuess(1990, "Zoe")))
        s.act(SubmitGuess(1990, s.round.guessOrder[0]))
    }

    @Test
    fun `preset values`() {
        assertEquals(ClosestScoring.RANK, ClosestYearSettings.RANKING.scoring)
        assertEquals(5, ClosestYearSettings.DISTANCE.distancePoints)
        assertEquals(ClosestYearPreset.DISTANCE, ClosestYearSettings.DISTANCE.preset)
        assertEquals(ClosestYearPreset.CUSTOM, ClosestYearSettings.DISTANCE.copy(distancePoints = 6).preset)
        // Values the scoring doesn't use don't make it Custom.
        assertEquals(ClosestYearPreset.RANKING, ClosestYearSettings.RANKING.copy(distancePoints = 9).preset)
        assertEquals(ClosestYearPreset.DISTANCE, ClosestYearSettings.DISTANCE.copy(tieBreaker = TieBreaker.FIRST_SCORES).preset)
        assertEquals(listOf(SettingsError.DISTANCE_POINTS), ClosestYearSettings(distancePoints = 0).validate())
    }

    // ---- Removing a player ----

    @Test
    fun `a removed player stays countable in rank and is never ranked`() {
        val s = closestGame(five, 1984, rank).act(GameAction.RemovePlayer("Eve"))
        assertEquals(4, s.players.size)
        assertEquals(listOf("Eve"), s.removed.map { it.id })
        assertTrue("Eve" !in s.round!!.guessOrder)
        val revealed = s.actAll(*guesses(1990, 1983, 2000, 1970), Reveal)
        assertEquals(mapOf("Ben" to 4, "Anna" to 3, "Dan" to 2, "Cara" to 1), revealed.round!!.points)
        assertEquals(40, GameEngine.target(revealed))
    }

    @Test
    fun `removing a guesser after the reveal scores the round again`() {
        val revealed = closestGame(five.take(3), 1984).actAll(*guesses(1990, 1985, 2000), Reveal)
        assertEquals(listOf("Ben"), revealed.round!!.winnerIds)
        val after = revealed.act(GameAction.RemovePlayer("Ben"))
        assertEquals(listOf("Anna"), after.round!!.winnerIds)
        assertEquals(1, after.act(Next).p("Anna").wins)
    }

    @Test
    fun `at least two players remain`() {
        val s = closestGame(listOf("Anna", "Ben"), 1984)
        assertEquals(RejectReason.TOO_FEW_PLAYERS, s.rejectReason(GameAction.RemovePlayer("Anna")))
        assertEquals(RejectReason.UNKNOWN_PLAYER, closestGame(five, 1984).rejectReason(GameAction.RemovePlayer("Zoe")))
    }

    @Test
    fun `removing the active timeline player ends the turn and hands back vetoes`() {
        val s = timelineGame(
            listOf("Anna" to listOf(1970), "Ben" to listOf(1990), "Cara" to listOf(2000)),
            song = 1984,
        ).actAll(GameAction.SelectSlot(1), GameAction.LockIn, GameAction.PlaceVeto("Ben", 0))
        val vetoesBefore = s.p("Ben").vetoes
        val after = s.act(GameAction.RemovePlayer("Anna"))
        assertEquals(Phase.AWAITING_DRAW, after.phase)
        assertNull(after.round)
        assertEquals("Ben", after.activePlayer.id, "the next player's turn")
        assertEquals(vetoesBefore + 1, after.p("Ben").vetoes)
        assertEquals(1984, after.discarded.last().year)
    }

    @Test
    fun `removing another timeline player keeps the turn going without their veto`() {
        val s = timelineGame(
            listOf("Anna" to listOf(1970), "Ben" to listOf(1990), "Cara" to listOf(2000)),
            song = 1984,
            activeSeat = 2,
        ).actAll(GameAction.SelectSlot(1), GameAction.LockIn, GameAction.PlaceVeto("Anna", 0))
        val after = s.act(GameAction.RemovePlayer("Anna"))
        assertEquals(Phase.VETOING, after.phase)
        assertEquals("Cara", after.activePlayer.id)
        assertTrue(after.round!!.vetoes.isEmpty())
    }

    @Test
    fun `a card stolen before the active player left still goes to the vetoer`() {
        val s = timelineGame(
            listOf("Anna" to listOf(1970), "Ben" to listOf(1990), "Cara" to listOf(2000)),
            song = 1984,
        ).actAll(GameAction.SelectSlot(0), GameAction.LockIn, GameAction.PlaceVeto("Ben", 1), Reveal)
        assertTrue(s.round!!.timelineOutcome is TimelineOutcome.Stolen)
        val after = s.act(GameAction.RemovePlayer("Anna"))
        assertEquals(listOf(1984, 1990), after.years("Ben"))
        assertEquals("Ben", after.activePlayer.id)
        assertEquals(listOf(1970), after.removed.single().timeline.map { it.year }, "their cards stay shown")
    }
}
