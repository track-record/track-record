package com.trackrecord.engine

import kotlinx.serialization.Serializable

/** Rule values for Timeline mode. `null` cost means the feature is off. */
@Serializable
data class TimelineSettings(
    val startingCards: Int = 1,
    val cardsToWin: Int = 10,
    val startingVetoes: Int = 2,
    val maxVetoes: Int = 2,
    val vetoesPerPlayerPerSong: Int = 1,
    val namingBonus: Boolean = false,
    /** Who may call out song and artist for the naming bonus (feedback round 19). */
    val namingCallers: NamingCallers = NamingCallers.ACTIVE,
    val skipCost: Int? = null,
    val buyCost: Int? = null,
) {
    fun validate(): List<SettingsError> = buildList {
        if (startingCards !in 1..3) add(SettingsError.STARTING_CARDS)
        if (cardsToWin !in 3..30) add(SettingsError.CARDS_TO_WIN)
        // DECISIONS.md D-004: cards to win must exceed starting cards.
        if (cardsToWin <= startingCards) add(SettingsError.CARDS_TO_WIN_NOT_ABOVE_STARTING)
        if (startingVetoes !in 0..10) add(SettingsError.STARTING_VETOES)
        if (maxVetoes !in startingVetoes..10) add(SettingsError.MAX_VETOES)
        if (vetoesPerPlayerPerSong !in 1..3) add(SettingsError.VETOES_PER_SONG)
        // A cost above the most vetoes held could never be paid (REVIEW-2 B-11; DECISIONS.md D-267).
        if (skipCost != null && (skipCost !in 1..3 || skipCost > maxVetoes)) add(SettingsError.SKIP_COST)
        if (buyCost != null && (buyCost !in 1..5 || buyCost > maxVetoes)) add(SettingsError.BUY_COST)
    }

    /** These settings with the skip and buy costs lowered to the most vetoes held, or off when none can be held (D-267). */
    fun withCostsWithinMax(): TimelineSettings = copy(
        skipCost = skipCost?.coerceAtMost(maxVetoes)?.takeIf { it >= 1 },
        buyCost = buyCost?.coerceAtMost(maxVetoes)?.takeIf { it >= 1 },
    )

    val preset: TimelinePreset
        get() = TimelinePreset.entries.firstOrNull { it.settings == this } ?: TimelinePreset.CUSTOM

    companion object {
        val FIXED_VETOES = TimelineSettings()
        val CLASSIC = TimelineSettings(
            startingCards = 1,
            cardsToWin = 10,
            startingVetoes = 2,
            maxVetoes = 5,
            vetoesPerPlayerPerSong = 1,
            namingBonus = true,
            skipCost = 1,
            buyCost = 3,
        )
    }
}

/**
 * Who may call out song and artist in Timeline (feedback round 19). One player gets the mark: +1 veto,
 * up to the max held; for a vetoer this refunds the veto they spent.
 * - [ACTIVE]: only the active player (the classic rule).
 * - [VETOERS]: also the players who vetoed, in veto order.
 * - [ALL]: everyone; vetoers are listed first, then the others.
 */
@Serializable
enum class NamingCallers { ACTIVE, VETOERS, ALL }

enum class TimelinePreset(val settings: TimelineSettings?) {
    FIXED_VETOES(TimelineSettings.FIXED_VETOES),
    CLASSIC(TimelineSettings.CLASSIC),
    CUSTOM(null),
}

/**
 * How a Closest Year round scores (feedback round 36):
 * - [CLOSEST]: the closest guess wins the round and the song (a card towards the target).
 * - [RANK]: points by rank; with n players the closest gets n − 1, the farthest 0.
 * - [DISTANCE]: [ClosestYearSettings.distancePoints] minus the years off, never below 0.
 *
 * Rank and Distance collect points, not cards; their target is [ClosestYearSettings.winsToWin] perfect rounds.
 */
@Serializable
enum class ClosestScoring { CLOSEST, RANK, DISTANCE }

/**
 * Equally close guesses (feedback round 36): [BOTH_SCORE] gives each the better place; [FIRST_SCORES]
 * puts the earlier guess ahead (with hidden guesses: the one locked in first).
 */
@Serializable
enum class TieBreaker { BOTH_SCORE, FIRST_SCORES }

@Serializable
data class ClosestYearSettings(
    /** Cards to win (Closest); Rank and Distance: the target in perfect rounds ([ClosestYearSettings.target]). */
    val winsToWin: Int = 10,
    val exactGuessEndsRound: Boolean = true,
    val rotateFirstGuesser: Boolean = true,
    val distinctGuesses: Boolean = true,
    val lowestGuessableYear: Int = 1900,
    /** Skips each player starts with; the first guesser of a round may spend one to skip the song. */
    val startingSkips: Int = 1,
    /** +1 skip for the player who named song and artist during their turn (marked at reveal). */
    val namingEarnsSkip: Boolean = true,
    /** Most skips a player can hold; null = no limit (feedback round 13). */
    val maxSkips: Int? = 5,
    /** Skips for buying a card, worth a perfect round; null = off (feedback rounds 27, 36). */
    val buyCost: Int? = 3,
    /**
     * Nobody sees the others' guesses before the reveal; everyone guesses at once and the same year may
     * be guessed twice (feedback round 36). Not part of a preset.
     */
    val hiddenGuesses: Boolean = false,
    val tieBreaker: TieBreaker = TieBreaker.BOTH_SCORE,
    val scoring: ClosestScoring = ClosestScoring.CLOSEST,
    /** Distance scoring: the points for an exact guess, one less per year off. */
    val distancePoints: Int = 5,
) {
    fun validate(): List<SettingsError> = buildList {
        if (winsToWin !in 1..30) add(SettingsError.WINS_TO_WIN)
        if (lowestGuessableYear !in 1800..2000) add(SettingsError.LOWEST_YEAR)
        if (startingSkips !in 0..5) add(SettingsError.STARTING_SKIPS)
        if (maxSkips != null && (maxSkips !in 1..10 || maxSkips < startingSkips)) add(SettingsError.MAX_SKIPS)
        // A buy above the most skips held could never be paid (REVIEW-2 B-11; DECISIONS.md D-267).
        if (buyCost != null && (buyCost !in 1..5 || (maxSkips != null && buyCost > maxSkips))) add(SettingsError.BUY_COST)
        if (distancePoints !in 1..20) add(SettingsError.DISTANCE_POINTS)
    }

    /** These settings with the buy cost lowered to the most skips held (D-267). */
    fun withCostsWithinMax(): ClosestYearSettings = copy(buyCost = buyCost?.coerceAtMost(maxSkips ?: buyCost))

    /** Every guess a different year: only when guesses are seen. */
    val guessesDistinct: Boolean get() = distinctGuesses && !hiddenGuesses

    /** An exact guess ends the round: only in Closest scoring with guesses in turn (feedback round 36). */
    val exactEndsRound: Boolean get() = exactGuessEndsRound && scoring == ClosestScoring.CLOSEST && !hiddenGuesses

    /** The tie breaker matters: Distance scores every guess on its own. */
    val usesTieBreaker: Boolean get() = scoring != ClosestScoring.DISTANCE

    /** A perfect round's points: one win (Closest), first place among [seats] players (Rank), an exact guess (Distance). */
    fun perfectRound(seats: Int): Int = when (scoring) {
        ClosestScoring.CLOSEST -> 1
        ClosestScoring.RANK -> (seats - 1).coerceAtLeast(1)
        ClosestScoring.DISTANCE -> distancePoints
    }

    /** The score to reach: [winsToWin] perfect rounds; [seats] counts removed players too (feedback round 36). */
    fun target(seats: Int): Int = winsToWin * perfectRound(seats)

    /** The preset these values match; only the values a preset sets are compared. */
    val preset: ClosestYearPreset
        get() = ClosestYearPreset.entries.firstOrNull { it.settings?.sameRules(this) == true } ?: ClosestYearPreset.CUSTOM

    /** These settings with [preset]'s values. */
    fun withPreset(preset: ClosestYearPreset): ClosestYearSettings = preset.settings?.let(::withRulesOf) ?: this

    /**
     * The values a preset sets (built in or saved, feedback round 27); the others are fixed or follow the
     * songs, and hidden guesses are chosen apart (feedback round 36). Values the scoring ignores don't count.
     */
    fun sameRules(other: ClosestYearSettings): Boolean =
        winsToWin == other.winsToWin && startingSkips == other.startingSkips && namingEarnsSkip == other.namingEarnsSkip &&
            maxSkips == other.maxSkips && buyCost == other.buyCost && scoring == other.scoring &&
            (!usesTieBreaker || tieBreaker == other.tieBreaker) &&
            (scoring != ClosestScoring.DISTANCE || distancePoints == other.distancePoints)

    /** These settings with [other]'s preset values. */
    fun withRulesOf(other: ClosestYearSettings): ClosestYearSettings = copy(
        winsToWin = other.winsToWin,
        startingSkips = other.startingSkips,
        namingEarnsSkip = other.namingEarnsSkip,
        maxSkips = other.maxSkips,
        buyCost = other.buyCost,
        scoring = other.scoring,
        tieBreaker = other.tieBreaker,
        distancePoints = other.distancePoints,
    )

    companion object {
        /** Skips, holding up to 5, and buying a card for 3 skips (feedback rounds 27, 36). */
        val CLOSEST = ClosestYearSettings(startingSkips = 1, namingEarnsSkip = true, maxSkips = 5, buyCost = 3)

        /** Like Closest, scored by rank (feedback round 36). */
        val RANKING = CLOSEST.copy(scoring = ClosestScoring.RANK)

        /** Like Closest, scored by distance: 5 points for an exact guess (feedback round 36). */
        val DISTANCE = CLOSEST.copy(scoring = ClosestScoring.DISTANCE, distancePoints = 5)
    }
}

/**
 * Closest Year presets (Felix, feedback rounds 11, 36); changing a value turns the preset into Custom.
 * The skip-only presets of round 11 are gone; a stored one reads as the default.
 */
enum class ClosestYearPreset(val settings: ClosestYearSettings?) {
    CLOSEST(ClosestYearSettings.CLOSEST),
    RANKING(ClosestYearSettings.RANKING),
    DISTANCE(ClosestYearSettings.DISTANCE),
    CUSTOM(null),
}

/**
 * Settings shared by both modes. The engine only stores them in the game snapshot;
 * the app applies them when building the pool and during playback.
 */
@Serializable
data class CommonSettings(
    /** Playlists in the pool; `null` means the built-in "All" playlist. */
    val playlistIds: List<Long>? = null,
    /** Songs on these playlists never enter the pool, even when also on an included one. */
    val excludedPlaylistIds: List<Long> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val autoPlay: Boolean = true,
    val startOffsetSeconds: Int = 0,
    /** `null` means no clip limit. */
    val clipLimitSeconds: Int? = null,
    val musicBrainzLookups: Boolean = true,
) {
    fun validate(): List<SettingsError> = buildList {
        if (startOffsetSeconds !in 0..60) add(SettingsError.START_OFFSET)
        if (clipLimitSeconds != null && clipLimitSeconds !in 10..120) add(SettingsError.CLIP_LIMIT)
        if (yearFrom != null && yearTo != null && yearFrom > yearTo) add(SettingsError.YEAR_FILTER)
    }
}

@Serializable
data class GameSettings(
    val timeline: TimelineSettings = TimelineSettings.FIXED_VETOES,
    val closestYear: ClosestYearSettings = ClosestYearSettings(),
    val common: CommonSettings = CommonSettings(),
) {
    fun validate(mode: GameMode): List<SettingsError> =
        common.validate() + when (mode) {
            GameMode.TIMELINE -> timeline.validate()
            GameMode.CLOSEST_YEAR -> closestYear.validate()
        }

    /** Costs that could never be paid lowered, e.g. in a setup stored before D-267. */
    fun withCostsWithinMax(): GameSettings = copy(timeline = timeline.withCostsWithinMax(), closestYear = closestYear.withCostsWithinMax())
}

enum class SettingsError {
    STARTING_CARDS,
    CARDS_TO_WIN,
    CARDS_TO_WIN_NOT_ABOVE_STARTING,
    STARTING_VETOES,
    MAX_VETOES,
    VETOES_PER_SONG,
    SKIP_COST,
    BUY_COST,
    WINS_TO_WIN,
    LOWEST_YEAR,
    STARTING_SKIPS,
    MAX_SKIPS,
    DISTANCE_POINTS,
    START_OFFSET,
    CLIP_LIMIT,
    YEAR_FILTER,
}
