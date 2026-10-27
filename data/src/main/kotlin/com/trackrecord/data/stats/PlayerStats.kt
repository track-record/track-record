package com.trackrecord.data.stats

import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.PlayerGameEntity
import com.trackrecord.data.db.PlayerRoundEntity
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.stats.RoundRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Games, wins, rounds and rounds won in one mode; [games] counts finished games only. */
data class ModeStats(val games: Int = 0, val wins: Int = 0, val rounds: Int = 0, val roundsWon: Int = 0)

/** A year's (or decade's, from its first [year]) bar: [value] over [count] rounds, or no bar ([value] null) without any. */
data class YearBar(val year: Int, val value: Double?, val count: Int)

/**
 * A saved player's stats (feedback round 47): per mode games, wins and rounds; for Timeline (Closest Year) how
 * many years their guesses were off on average; and how often they named the song when they had the chance.
 * The bars run over the song's year, every year (or decade, feedback round 48) from the earliest to the latest included.
 */
data class PlayerStats(
    val modes: Map<GameMode, ModeStats>,
    val guesses: Int,
    val meanYearsOff: Double?,
    val yearsOffByYear: List<YearBar>,
    val namingChances: Int,
    val named: Int,
    val namingByYear: List<YearBar>,
    val yearsOffByDecade: List<YearBar> = emptyList(),
    val namingByDecade: List<YearBar> = emptyList(),
) {
    val namingRate: Double? get() = if (namingChances == 0) null else named.toDouble() / namingChances

    companion object {
        fun of(rounds: List<PlayerRoundEntity>, games: List<PlayerGameEntity>): PlayerStats {
            val modes = GameMode.entries.associateWith { mode ->
                val inMode = games.filter { it.mode == mode.name }
                val roundsInMode = rounds.filter { it.mode == mode.name }
                ModeStats(inMode.size, inMode.count { it.won }, roundsInMode.count { it.played }, roundsInMode.count { it.won })
            }
            val guesses = rounds.filter { it.mode == GameMode.CLOSEST_YEAR.name && it.yearsOff != null }
            val chances = rounds.filter { it.namingChance }
            val yearsOff = { rows: List<PlayerRoundEntity> -> rows.map { it.yearsOff!! }.average() }
            val namedShare = { rows: List<PlayerRoundEntity> -> rows.count { it.named }.toDouble() / rows.size }
            return PlayerStats(
                modes = modes,
                guesses = guesses.size,
                meanYearsOff = guesses.takeIf { it.isNotEmpty() }?.map { it.yearsOff!! }?.average(),
                yearsOffByYear = bars(guesses, 1, yearsOff),
                namingChances = chances.size,
                named = chances.count { it.named },
                namingByYear = bars(chances, 1, namedShare),
                yearsOffByDecade = bars(guesses, DECADE, yearsOff),
                namingByDecade = bars(chances, DECADE, namedShare),
            )
        }

        /** One bar per [step] years, each starting at a multiple of [step]. */
        private fun bars(rows: List<PlayerRoundEntity>, step: Int, value: (List<PlayerRoundEntity>) -> Double): List<YearBar> {
            if (rows.isEmpty()) return emptyList()
            val groups = rows.groupBy { Math.floorDiv(it.year, step) * step }
            return (groups.keys.min()..groups.keys.max() step step).map { start ->
                val inBar = groups[start].orEmpty()
                YearBar(start, inBar.takeIf { it.isNotEmpty() }?.let(value), inBar.size)
            }
        }

        private const val DECADE = 10
    }
}

/** Records rounds and finished games of saved players, and reads them back as [PlayerStats]. */
class PlayerStatsRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    fun stats(profileId: Long): Flow<PlayerStats> =
        combine(db.playerStats().observeRounds(profileId), db.playerStats().observeGames(profileId), PlayerStats::of)

    /** [records] of round [round] in game [game] (its seed), each with the saved player's profile id. */
    suspend fun recordRound(game: Long, round: Int, mode: GameMode, records: List<Pair<Long, RoundRecord>>) {
        if (records.isEmpty()) return
        db.playerStats().insertRounds(
            records.map { (profileId, r) ->
                PlayerRoundEntity(
                    profileId = profileId, game = game, round = round, mode = mode.name, year = r.year,
                    played = r.played, yearsOff = r.yearsOff, namingChance = r.namingChance, named = r.named, won = r.won,
                )
            },
        )
    }

    /** A finished game: [players] are the saved players still playing at the end, with whether they won. */
    suspend fun recordGame(game: Long, mode: GameMode, players: List<Pair<Long, Boolean>>) {
        if (players.isEmpty()) return
        val now = clock()
        db.playerStats().insertGames(players.map { (profileId, won) -> PlayerGameEntity(profileId = profileId, game = game, mode = mode.name, won = won, finishedAt = now) })
    }
}
