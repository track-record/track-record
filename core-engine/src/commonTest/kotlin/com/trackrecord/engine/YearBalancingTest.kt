package com.trackrecord.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Evening out the years (YEAR_BALANCE.md). */
class YearBalancingTest {
    /** An 80s-heavy playlist over 1960–2023: about [total] songs, most from 1980–1992, 1960 with very few. */
    private fun skewedPool(total: Int): List<Card> {
        val density = (1960..2023).associateWith { y -> 2.0 + 40 * exp(-((y - 1986) / 5.0).let { it * it } / 2) }
        val sum = density.values.sum()
        var id = 0L
        return density.flatMap { (year, d) -> List((d / sum * total).toInt().coerceAtLeast(1)) { Card(++id, year) } }
    }

    @Test
    fun `off, or a pool too small for the game, is the plain shuffle of before`() {
        val songs = skewedPool(600)
        val plain = songs.sortedBy { it.songId }.shuffled(Random(7))
        assertEquals(plain, GameEngine.shufflePool(songs, 7))
        assertEquals(plain, GameEngine.shufflePool(songs, 7, YearBalance.OFF, perGame = 76))
        // A quarter of 600 songs is less than a game of 200.
        assertEquals(plain, GameEngine.shufflePool(songs, 7, YearBalance.GENTLE, perGame = 200))
        assertNotEquals(plain, GameEngine.shufflePool(songs, 7, YearBalance.GENTLE, perGame = 76))
    }

    @Test
    fun `the balanced shuffle keeps every song and is the same for a seed`() {
        val songs = skewedPool(600)
        val a = GameEngine.shufflePool(songs, 7, YearBalance.STRONG, 76)
        assertEquals(songs.toSet(), a.toSet())
        assertEquals(songs.size, a.size)
        assertEquals(a, GameEngine.shufflePool(songs.reversed(), 7, YearBalance.STRONG, 76))
        assertNotEquals(a, GameEngine.shufflePool(songs, 8, YearBalance.STRONG, 76))
    }

    @Test
    fun `targets are as equal as the cap allows and add up to a game`() {
        val perYear = mapOf(1960 to 2, 1970 to 10, 1985 to 100, 1990 to 40)
        val t = YearBalancing.targets(perYear, perGame = 30, cap = 0.5)!!
        assertEquals(30.0, t.values.sum(), 1e-6)
        assertEquals(1.0, t.getValue(1960), 1e-6, "capped at half its songs")
        assertEquals(5.0, t.getValue(1970), 1e-6, "capped at half its songs")
        assertEquals(t.getValue(1985), t.getValue(1990), 1e-6, "the rest shared equally")
        perYear.forEach { (year, n) -> assertTrue(t.getValue(year) <= 0.5 * n + 1e-6) }
        // An even pool: equal targets, so equal weights.
        val even = YearBalancing.weights((1..100).map { Card(it.toLong(), 1960 + it % 20) }, YearBalance.STRONG, 20)!!
        assertTrue(even.all { abs(it - even.first()) < 1e-9 })
        assertNull(YearBalancing.targets(perYear, perGame = 76, cap = 0.5), "152 songs × 0.5 ≤ 76")
    }

    @Test
    fun `over many games each year comes up about as often as its target`() {
        val songs = skewedPool(600)
        val perGame = 76
        val perYear = songs.groupingBy { it.year }.eachCount()
        val targets = YearBalancing.targets(perYear, perGame, YearBalance.STRONG.cap!!)!!
        val games = 2000
        val counts = mutableMapOf<Int, Int>()
        repeat(games) { seed ->
            GameEngine.shufflePool(songs, seed.toLong(), YearBalance.STRONG, perGame).take(perGame).forEach { counts[it.year] = (counts[it.year] ?: 0) + 1 }
        }
        for ((year, target) in targets) {
            val mean = (counts[year] ?: 0).toDouble() / games
            assertTrue(abs(mean - target) < 0.15 + 0.1 * target, "$year: $mean songs per game, target $target")
        }
        // The busy 80s no longer dominate: from over half the pool to about a fifth of a game.
        val eighties = counts.filterKeys { it in 1980..1989 }.values.sum().toDouble() / (games * perGame)
        assertTrue(songs.count { it.year in 1980..1989 } > songs.size / 2)
        assertTrue(eighties < 0.3, "80s share $eighties")
    }

    @Test
    fun `a game estimates its songs from the players and rules`() {
        val settings = GameSettings(timeline = TimelineSettings(startingCards = 1, cardsToWin = 10))
        assertEquals(76, YearBalancing.songsPerGame(GameMode.TIMELINE, settings, players = 4))
        assertEquals(40, YearBalancing.songsPerGame(GameMode.CLOSEST_YEAR, GameSettings(), players = 4))
        val rank = GameSettings(closestYear = ClosestYearSettings.RANKING)
        assertEquals(30, YearBalancing.songsPerGame(GameMode.CLOSEST_YEAR, rank, players = 4))
    }

    @Test
    fun `a game started with the years evened out deals from the balanced order`() {
        val songs = skewedPool(600)
        val settings = GameSettings(common = CommonSettings(yearBalance = YearBalance.GENTLE))
        val r = GameEngine.reduce(null, GameAction.StartGame(GameMode.TIMELINE, listOf("A", "B"), songs, settings, 5, CURRENT_YEAR))
        val state = (r as ReduceResult.Accepted).state
        val expected = GameEngine.shufflePool(songs, 5, YearBalance.GENTLE, YearBalancing.songsPerGame(GameMode.TIMELINE, settings, 2))
        assertEquals(expected.drop(2), state.pool)
        assertEquals(YearBalance.GENTLE, state.settings.common.yearBalance)
    }
}
