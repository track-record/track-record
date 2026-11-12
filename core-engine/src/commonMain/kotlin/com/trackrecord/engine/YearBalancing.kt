package com.trackrecord.engine

import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.random.Random

/**
 * How much the shuffle evens out the years (YEAR_BALANCE.md): [cap] is the most of a year's songs one game
 * should play on average, which limits how often a song from a rare year comes back from game to game.
 */
@Serializable
enum class YearBalance(val cap: Double?) {
    OFF(null),
    GENTLE(0.25),
    STRONG(0.5),
}

/**
 * The weighted shuffle behind [YearBalance] (YEAR_BALANCE.md): each year gets a share of a game's songs, as
 * equal as the cap allows, and its songs the weight to get it.
 */
object YearBalancing {
    /**
     * Songs a game uses, estimated on the high side: an overestimate only evens out a little less.
     * - Song Order: the starting cards, and about two turns per card still to win.
     * - Closest Year: each round gives one player a card; Rank and Distance seldom give a round's full points.
     */
    fun songsPerGame(mode: GameMode, settings: GameSettings, players: Int): Int = when (mode) {
        GameMode.TIMELINE -> settings.timeline.let { players * it.startingCards + players * (it.cardsToWin - it.startingCards) * 2 }
        GameMode.CLOSEST_YEAR -> settings.closestYear.let { if (it.scoring == ClosestScoring.CLOSEST) players * it.winsToWin else 3 * it.winsToWin }
    }

    /**
     * Songs per game for each year: as equal as possible, at most [cap] × the year's songs, adding up to [perGame]
     * (water-filling). Null when the pool is too small for that: then the shuffle stays plain.
     */
    fun targets(perYear: Map<Int, Int>, perGame: Int, cap: Double): Map<Int, Double>? {
        if (perYear.isEmpty() || cap * perYear.values.sum() <= perGame) return null
        var low = 0.0
        var high = perYear.values.max().toDouble()
        repeat(60) {
            val level = (low + high) / 2
            if (perYear.values.sumOf { minOf(cap * it, level) } < perGame) low = level else high = level
        }
        return perYear.mapValues { (_, n) -> minOf(cap * n, high) }
    }

    /** Each song's weight for [balance], or null for a plain shuffle (off, or a pool too small). */
    fun weights(songs: List<Card>, balance: YearBalance, perGame: Int): List<Double>? {
        val cap = balance.cap ?: return null
        val perYear = songs.groupingBy { it.year }.eachCount()
        val targets = targets(perYear, perGame, cap) ?: return null
        return songs.map { targets.getValue(it.year) / perYear.getValue(it.year) }
    }

    /**
     * A shuffle where a song with more weight tends to come earlier: the same as drawing one by one in proportion
     * to the weights (Efraimidis–Spirakis: sorted by an exponential key over the weight).
     */
    fun <T> weightedShuffle(items: List<T>, weights: List<Double>, random: Random): List<T> =
        items.indices.map { i -> -ln(1.0 - random.nextDouble()) / weights[i] to i }
            .sortedBy { it.first }
            .map { items[it.second] }
}
