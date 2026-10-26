package com.trackrecord.engine

import com.trackrecord.engine.GameAction.CorrectYear
import com.trackrecord.engine.GameAction.Draw
import com.trackrecord.engine.GameAction.Next
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SubmitGuess
import com.trackrecord.engine.GameAction.UndoGuess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClosestYearTest {
    private val four = listOf("Anna", "Ben", "Cara", "Dan")

    private fun guesses(vararg years: Int) = years.map { SubmitGuess(it) }.toTypedArray()

    @Test
    fun `worked example 1 - Dan wins`() {
        val s = closestGame(four, 1984).actAll(*guesses(1990, 1980, 1988, 1981), Reveal)
        assertEquals(listOf("Dan"), s.round!!.winnerIds)
    }

    @Test
    fun `worked example 2 - Anna wins the tie with Ben by guessing earlier when the first scores`() {
        val s = closestGame(four, 1984, ClosestYearSettings(tieBreaker = TieBreaker.FIRST_SCORES)).actAll(*guesses(1986, 1982, 1990, 1975), Reveal)
        assertEquals(listOf("Anna"), s.round!!.winnerIds)
    }

    @Test
    fun `on a tie both score by default, each with a win and the song`() {
        val s = closestGame(four, 1984).actAll(*guesses(1986, 1982, 1990, 1975), Reveal)
        assertEquals(listOf("Anna", "Ben"), s.round!!.winnerIds)
        val next = s.act(Next)
        assertEquals(listOf(1, 1, 0, 0), four.map { next.p(it).wins })
        assertEquals(listOf(1984), next.p("Ben").collection.map { it.year })
    }

    @Test
    fun `worked example 3 - Ben's exact guess ends the round`() {
        val s = closestGame(four, 1984).actAll(*guesses(1979, 1984))
        assertEquals(Phase.REVEALED, s.phase)
        assertEquals(listOf("Ben"), s.round!!.winnerIds)
        assertEquals(2, s.round.guesses.size)
        assertEquals(RejectReason.WRONG_PHASE, s.rejectReason(SubmitGuess(1985)))
    }

    @Test
    fun `exact guess does not end the round when the setting is off`() {
        val s = closestGame(four, 1984, ClosestYearSettings(exactGuessEndsRound = false)).actAll(*guesses(1979, 1984))
        assertEquals(Phase.GUESSING, s.phase)
        assertEquals(listOf("Ben"), s.actAll(*guesses(1985, 1983), Reveal).round!!.winnerIds)
    }

    @Test
    fun `duplicate guess rejected when distinct guesses are required`() {
        val s = closestGame(four, 1984).act(SubmitGuess(1990))
        assertEquals(RejectReason.DUPLICATE_GUESS, s.rejectReason(SubmitGuess(1990)))
        val loose = closestGame(four, 1984, ClosestYearSettings(distinctGuesses = false, tieBreaker = TieBreaker.FIRST_SCORES))
            .actAll(*guesses(1990, 1990, 1970, 1999), Reveal)
        assertEquals(listOf("Anna"), loose.round!!.winnerIds)
    }

    @Test
    fun `guess range is lowest guessable year to the current year`() {
        val s = closestGame(four, 1984, ClosestYearSettings(lowestGuessableYear = 1950))
        assertEquals(RejectReason.YEAR_OUT_OF_RANGE, s.rejectReason(SubmitGuess(1949)))
        assertEquals(RejectReason.YEAR_OUT_OF_RANGE, s.rejectReason(SubmitGuess(CURRENT_YEAR + 1)))
        s.act(SubmitGuess(1950)).act(SubmitGuess(CURRENT_YEAR))
    }

    @Test
    fun `undo last guess works until reveal`() {
        val s = closestGame(four, 1984).actAll(*guesses(1990, 1980))
        val undone = s.act(UndoGuess)
        assertEquals(listOf(Guess("Anna", 1990)), undone.round!!.guesses)
        assertEquals("Ben", undone.round.currentGuesserId)
        val full = undone.actAll(*guesses(1970, 1971, 1972))
        assertEquals(RejectReason.ALL_GUESSED, full.rejectReason(SubmitGuess(1973)))
        full.act(UndoGuess)
        assertEquals(RejectReason.WRONG_PHASE, full.act(Reveal).rejectReason(UndoGuess))
        assertEquals(RejectReason.NOTHING_TO_UNDO, closestGame(four, 1984).rejectReason(UndoGuess))
    }

    @Test
    fun `reveal needs every guess`() {
        val s = closestGame(four, 1984).actAll(*guesses(1990, 1980, 1988))
        assertEquals(RejectReason.GUESSES_INCOMPLETE, s.rejectReason(Reveal))
    }

    @Test
    fun `winner gets a win and the song`() {
        val s = closestGame(four, 1984).actAll(*guesses(1990, 1980, 1988, 1981), Reveal, Next)
        assertEquals(1, s.p("Dan").wins)
        assertEquals(listOf(1984), s.p("Dan").collection.map { it.year })
        assertEquals(Phase.AWAITING_DRAW, s.phase)
    }

    @Test
    fun `wrong year recomputes the winner among the guesses made`() {
        val revealed = closestGame(four, 1984).actAll(*guesses(1990, 1980, 1988, 1981), Reveal)
        val corrected = revealed.act(CorrectYear(1991))
        assertEquals(listOf("Anna"), corrected.round!!.winnerIds)
        assertEquals(1991, corrected.round.song.year)
        assertEquals(true, corrected.round.yearCorrected)
        assertEquals(1, corrected.act(Next).p("Anna").wins)
        assertEquals(RejectReason.YEAR_OUT_OF_RANGE, revealed.rejectReason(CorrectYear(1899)))
    }

    @Test
    fun `rotation over P + 1 rounds`() {
        val players = listOf("Anna", "Ben", "Cara")
        var s = startGame(GameMode.CLOSEST_YEAR, players, (1..10).map { 1950 + it })
        val firstGuessers = mutableListOf<String>()
        repeat(players.size + 1) {
            s = s.act(Draw)
            val round = s.round!!
            firstGuessers += round.guessOrder.first()
            assertEquals(players.size, round.guessOrder.toSet().size)
            // Guess far from any song year so nobody wins exactly
            s = s.actAll(*guesses(2020, 2021, 2022), Reveal, Next)
        }
        assertEquals(listOf("Anna", "Ben", "Cara", "Anna"), firstGuessers)
    }

    @Test
    fun `order wraps around in seating order`() {
        val s = startGame(GameMode.CLOSEST_YEAR, four, listOf(1970, 1971, 1972))
            .copy(roundsPlayed = 2).act(Draw)
        assertEquals(listOf("Cara", "Dan", "Anna", "Ben"), s.round!!.guessOrder)
    }

    @Test
    fun `no rotation means seat 1 always starts`() {
        val settings = GameSettings(closestYear = ClosestYearSettings(rotateFirstGuesser = false))
        var s = startGame(GameMode.CLOSEST_YEAR, four, (1..5).map { 1960 + it }, settings)
        repeat(3) {
            s = s.act(Draw)
            assertEquals("Anna", s.round!!.guessOrder.first())
            s = s.actAll(*guesses(2020, 2021, 2022, 2023), Reveal, Next)
        }
    }

    @Test
    fun `the game ends at wins to win`() {
        val settings = GameSettings(closestYear = ClosestYearSettings(winsToWin = 2, rotateFirstGuesser = false))
        var s = startGame(GameMode.CLOSEST_YEAR, listOf("Anna", "Ben"), listOf(1980, 1980, 1980), settings)
        s = s.actAll(Draw, SubmitGuess(1980), Next)
        assertEquals(1, s.p("Anna").wins)
        assertNull(s.result)
        s = s.actAll(Draw, SubmitGuess(1980), Next)
        assertEquals(Phase.GAME_OVER, s.phase)
        assertEquals(GameResult(listOf("Anna"), EndReason.TARGET_REACHED), s.result)
    }

    @Test
    fun `timeline actions are rejected in closest year`() {
        val s = closestGame(four, 1984)
        assertEquals(RejectReason.WRONG_MODE, s.rejectReason(GameAction.SelectSlot(0)))
        // Buying a card is in both modes since feedback round 27; off unless the rules set a cost.
        assertEquals(RejectReason.FEATURE_DISABLED, closestGame(four, 1984, NO_SKIPS).rejectReason(GameAction.BuyCard("Anna")))
    }
}
