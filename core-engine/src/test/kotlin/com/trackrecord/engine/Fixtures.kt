package com.trackrecord.engine

import kotlin.test.assertIs
import kotlin.test.fail

const val CURRENT_YEAR = 2026

private var nextSongId = 1L

fun card(year: Int) = Card(nextSongId++, year)

fun cards(vararg years: Int) = years.map { card(it) }

fun GameState.act(action: GameAction): GameState {
    val result = GameEngine.reduce(this, action)
    if (result !is ReduceResult.Accepted) fail("Expected $action to be accepted, got $result")
    return result.state
}

fun GameState.actAll(vararg actions: GameAction): GameState = actions.fold(this) { s, a -> s.act(a) }

fun GameState.rejectReason(action: GameAction): RejectReason {
    val result = GameEngine.reduce(this, action)
    assertIs<ReduceResult.Rejected>(result, "Expected $action to be rejected")
    kotlin.test.assertEquals(this, result.state, "A rejected action must not change the state")
    return result.reason
}

fun GameState.p(id: String): PlayerState = player(id) ?: fail("no player $id")

fun GameState.years(id: String): List<Int> = p(id).timeline.map { it.year }

/**
 * A Timeline game in the PLACING phase. The first entry of [timelines] is seat 1 (active unless
 * [activeSeat] says otherwise).
 */
fun timelineGame(
    timelines: List<Pair<String, List<Int>>>,
    song: Int,
    settings: TimelineSettings = TimelineSettings.FIXED_VETOES,
    vetoes: Map<String, Int> = emptyMap(),
    pool: List<Int> = listOf(2000, 2001, 2002),
    activeSeat: Int = 0,
): GameState = GameState(
    mode = GameMode.TIMELINE,
    settings = GameSettings(timeline = settings),
    seed = 0,
    currentYear = CURRENT_YEAR,
    players = timelines.map { (id, years) ->
        PlayerState(id = id, vetoes = vetoes[id] ?: settings.startingVetoes, timeline = years.sorted().map { card(it) })
    },
    pool = pool.map { card(it) },
    phase = Phase.PLACING,
    activeSeat = activeSeat,
    roundsPlayed = 1,
    round = RoundState(number = 1, song = card(song)),
)

/** The skip rules of the presets removed in feedback round 36, for the skip tests. */
val SKIPS = ClosestYearSettings(startingSkips = 1, namingEarnsSkip = true, maxSkips = 2, buyCost = null)
val NO_SKIPS = ClosestYearSettings(startingSkips = 0, namingEarnsSkip = false, maxSkips = null, buyCost = null)

/** A Closest Year game in the GUESSING phase for a song from [year]. */
fun closestGame(
    playerIds: List<String>,
    year: Int,
    settings: ClosestYearSettings = ClosestYearSettings(),
    pool: List<Int> = listOf(2000, 2001, 2002),
): GameState {
    val start = GameState(
        mode = GameMode.CLOSEST_YEAR,
        settings = GameSettings(closestYear = settings),
        seed = 0,
        currentYear = CURRENT_YEAR,
        players = playerIds.map { PlayerState(id = it) },
        pool = listOf(card(year)) + pool.map { card(it) },
        phase = Phase.AWAITING_DRAW,
    )
    return start.act(GameAction.Draw)
}

fun startGame(
    mode: GameMode,
    players: List<String>,
    songYears: List<Int>,
    settings: GameSettings = GameSettings(),
    seed: Long = 42,
): GameState {
    val result = GameEngine.reduce(
        null,
        GameAction.StartGame(mode, players, songYears.map { card(it) }, settings, seed, CURRENT_YEAR),
    )
    if (result !is ReduceResult.Accepted) fail("StartGame rejected: $result")
    return result.state
}
