package com.trackrecord.engine

import com.trackrecord.engine.GameAction.LockIn
import com.trackrecord.engine.GameAction.PlaceVeto
import com.trackrecord.engine.GameAction.Reveal
import com.trackrecord.engine.GameAction.SelectSlot
import com.trackrecord.engine.GameAction.SetNamedBy
import com.trackrecord.engine.GameAction.SubmitGuess
import com.trackrecord.engine.stats.RoundRecord
import com.trackrecord.engine.stats.RoundStats
import kotlin.test.Test
import kotlin.test.assertEquals

/** Player stats per round (feedback round 47): turns, years off and naming chances. */
class RoundStatsTest {
    /** Anna active (wrong slot), Cara then Ben veto; Dan doesn't. */
    private fun songOrder(callers: NamingCallers) = timelineGame(
        listOf("Anna" to listOf(1970, 1980, 1990), "Ben" to listOf(1960), "Cara" to listOf(2010), "Dan" to listOf(2000)),
        song = 1985,
        settings = TimelineSettings.CLASSIC.copy(namingCallers = callers),
    ).actAll(SelectSlot(0), LockIn, PlaceVeto("Cara", 3), PlaceVeto("Ben", 2), Reveal)

    @Test
    fun `song order - only the active player plays the round, and no years off are recorded`() {
        val records = RoundStats.of(songOrder(NamingCallers.ACTIVE))
        assertEquals(listOf(RoundRecord("Anna", 1985, played = true, yearsOff = null, namingChance = true, named = false)), records)
    }

    @Test
    fun `nobody named it - every caller had a chance`() {
        assertEquals(setOf("Anna", "Cara", "Ben"), RoundStats.namingChances(songOrder(NamingCallers.VETOERS)))
        assertEquals(setOf("Anna", "Cara", "Ben", "Dan"), RoundStats.namingChances(songOrder(NamingCallers.ALL)))
    }

    @Test
    fun `callers after the namer had no chance, those before missed theirs`() {
        val s = songOrder(NamingCallers.VETOERS).act(SetNamedBy("Cara", true))
        assertEquals(setOf("Anna", "Cara"), RoundStats.namingChances(s))
        val cara = RoundStats.of(s).single { it.playerId == "Cara" }
        assertEquals(RoundRecord("Cara", 1985, played = false, yearsOff = null, namingChance = true, named = true), cara)
    }

    @Test
    fun `the others call together - one of them naming it takes the chance from the rest`() {
        val s = songOrder(NamingCallers.ALL).act(SetNamedBy("Dan", true))
        assertEquals(setOf("Anna", "Cara", "Ben", "Dan"), RoundStats.namingChances(s))
        val t = timelineGame(
            listOf("Anna" to listOf(1970), "Ben" to listOf(1960), "Cara" to listOf(2010)),
            song = 1985,
            settings = TimelineSettings.CLASSIC.copy(namingCallers = NamingCallers.ALL),
        ).actAll(SelectSlot(1), LockIn, Reveal, SetNamedBy("Ben", true))
        assertEquals(setOf("Anna", "Ben"), RoundStats.namingChances(t))
    }

    @Test
    fun `no naming bonus - no chances`() {
        val s = songOrder(NamingCallers.ALL).let { it.copy(settings = it.settings.copy(timeline = it.settings.timeline.copy(namingBonus = false))) }
        assertEquals(emptySet(), RoundStats.namingChances(s))
    }

    @Test
    fun `closest year - every guess counts with its years off, guessers name together`() {
        val s = closestGame(listOf("Anna", "Ben", "Cara"), 1984, SKIPS)
            .actAll(SubmitGuess(1990), SubmitGuess(1980), SubmitGuess(1983), Reveal, SetNamedBy("Ben", true))
        assertEquals(
            listOf(
                RoundRecord("Anna", 1984, played = true, yearsOff = 6, namingChance = false, named = false),
                RoundRecord("Ben", 1984, played = true, yearsOff = 4, namingChance = true, named = true),
                RoundRecord("Cara", 1984, played = true, yearsOff = 1, namingChance = false, named = false),
            ),
            RoundStats.of(s),
        )
    }

    @Test
    fun `closest year - an exact guess ends the round, those who didn't guess have nothing recorded`() {
        val s = closestGame(listOf("Anna", "Ben", "Cara"), 1984, SKIPS).actAll(SubmitGuess(1984))
        assertEquals(listOf(RoundRecord("Anna", 1984, played = true, yearsOff = 0, namingChance = true, named = false)), RoundStats.of(s))
    }

    @Test
    fun `nothing before the reveal`() {
        assertEquals(emptyList(), RoundStats.of(closestGame(listOf("Anna", "Ben"), 1984)))
    }
}
