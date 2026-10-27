package com.trackrecord.data

import com.trackrecord.data.db.PlayerGameEntity
import com.trackrecord.data.db.PlayerRoundEntity
import com.trackrecord.data.stats.ModeStats
import com.trackrecord.data.stats.PlayerStats
import com.trackrecord.data.stats.YearBar
import com.trackrecord.engine.GameMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Player stats from the recorded rounds and games (feedback round 47). */
class PlayerStatsTest {
    private fun round(mode: GameMode, year: Int, played: Boolean = true, off: Int? = null, chance: Boolean = false, named: Boolean = false, won: Boolean = false) =
        PlayerRoundEntity(profileId = 1, game = 1, round = 1, mode = mode.name, year = year, played = played, yearsOff = off, namingChance = chance, named = named, won = won)

    private fun game(mode: GameMode, won: Boolean) = PlayerGameEntity(profileId = 1, game = 1, mode = mode.name, won = won, finishedAt = 0)

    @Test
    fun `games, wins, rounds and rounds won per mode - a card won by a veto counts too`() {
        val stats = PlayerStats.of(
            listOf(
                round(GameMode.TIMELINE, 1980, won = true),
                round(GameMode.TIMELINE, 1981, played = false, chance = true),
                round(GameMode.TIMELINE, 1982, played = false, won = true),
                round(GameMode.CLOSEST_YEAR, 1990, off = 2),
            ),
            listOf(game(GameMode.TIMELINE, true), game(GameMode.TIMELINE, false), game(GameMode.CLOSEST_YEAR, false)),
        )
        assertEquals(ModeStats(games = 2, wins = 1, rounds = 1, roundsWon = 2), stats.modes[GameMode.TIMELINE])
        assertEquals(ModeStats(games = 1, wins = 0, rounds = 1), stats.modes[GameMode.CLOSEST_YEAR])
    }

    @Test
    fun `years off - Timeline guesses only, averaged overall and per song year with empty years in between`() {
        val stats = PlayerStats.of(
            listOf(
                round(GameMode.CLOSEST_YEAR, 1980, off = 0),
                round(GameMode.CLOSEST_YEAR, 1980, off = 4),
                round(GameMode.CLOSEST_YEAR, 1982, off = 5),
                round(GameMode.TIMELINE, 1970),
            ),
            emptyList(),
        )
        assertEquals(3, stats.guesses)
        assertEquals(3.0, stats.meanYearsOff)
        assertEquals(listOf(YearBar(1980, 2.0, 2), YearBar(1981, null, 0), YearBar(1982, 5.0, 1)), stats.yearsOffByYear)
        assertEquals(listOf(YearBar(1980, 3.0, 3)), stats.yearsOffByDecade)
    }

    @Test
    fun `decade bars start at the decade and leave empty decades in between`() {
        val stats = PlayerStats.of(
            listOf(round(GameMode.TIMELINE, 1979, chance = true, named = true), round(GameMode.TIMELINE, 1999, chance = true)),
            emptyList(),
        )
        assertEquals(listOf(YearBar(1970, 1.0, 1), YearBar(1980, null, 0), YearBar(1990, 0.0, 1)), stats.namingByDecade)
    }

    @Test
    fun `naming rate - named over chances, in both modes`() {
        val stats = PlayerStats.of(
            listOf(
                round(GameMode.TIMELINE, 1980, chance = true, named = true),
                round(GameMode.CLOSEST_YEAR, 1980, off = 1, chance = true),
                round(GameMode.CLOSEST_YEAR, 1981, off = 1, chance = false),
                round(GameMode.TIMELINE, 1990, chance = true),
            ),
            emptyList(),
        )
        assertEquals(3, stats.namingChances)
        assertEquals(1, stats.named)
        assertEquals(1.0 / 3, stats.namingRate)
        assertEquals(YearBar(1980, 0.5, 2), stats.namingByYear.first())
        assertEquals(11, stats.namingByYear.size)
    }

    @Test
    fun `nothing recorded yet`() {
        val stats = PlayerStats.of(emptyList(), emptyList())
        assertNull(stats.meanYearsOff)
        assertNull(stats.namingRate)
        assertEquals(emptyList(), stats.yearsOffByYear)
    }
}
