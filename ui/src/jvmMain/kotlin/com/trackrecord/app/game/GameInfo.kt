package com.trackrecord.app.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trackrecord.app.newgame.label
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.brief_closest
import com.trackrecord.app.resources.brief_closest_naming
import com.trackrecord.app.resources.brief_closest_naming_hidden
import com.trackrecord.app.resources.brief_closest_naming_turns
import com.trackrecord.app.resources.brief_timeline
import com.trackrecord.app.resources.brief_timeline_naming_active
import com.trackrecord.app.resources.brief_timeline_naming_all
import com.trackrecord.app.resources.brief_timeline_naming_first
import com.trackrecord.app.resources.brief_timeline_naming_vetoers
import com.trackrecord.app.resources.cards
import com.trackrecord.app.resources.info_excluded
import com.trackrecord.app.resources.info_included
import com.trackrecord.app.resources.info_mode
import com.trackrecord.app.resources.info_rules
import com.trackrecord.app.resources.info_settings
import com.trackrecord.app.resources.info_songs
import com.trackrecord.app.resources.info_target
import com.trackrecord.app.resources.info_title
import com.trackrecord.app.resources.info_unknown_playlist
import com.trackrecord.app.resources.info_year_range
import com.trackrecord.app.resources.info_years
import com.trackrecord.app.resources.mode_closest
import com.trackrecord.app.resources.mode_timeline
import com.trackrecord.app.resources.playlist_all
import com.trackrecord.app.resources.playlist_excluded
import com.trackrecord.app.resources.playlist_local_files
import com.trackrecord.app.resources.playlist_streamed
import com.trackrecord.app.resources.points
import com.trackrecord.app.resources.rules_closest_buy
import com.trackrecord.app.resources.rules_closest_buy_points
import com.trackrecord.app.resources.rules_closest_closest
import com.trackrecord.app.resources.rules_closest_distance
import com.trackrecord.app.resources.rules_closest_exact
import com.trackrecord.app.resources.rules_closest_hidden
import com.trackrecord.app.resources.rules_closest_naming
import com.trackrecord.app.resources.rules_closest_rank
import com.trackrecord.app.resources.rules_closest_skip
import com.trackrecord.app.resources.rules_closest_turns
import com.trackrecord.app.resources.rules_target_cards
import com.trackrecord.app.resources.rules_target_points
import com.trackrecord.app.resources.rules_tie_both
import com.trackrecord.app.resources.rules_tie_both_rank
import com.trackrecord.app.resources.rules_tie_first
import com.trackrecord.app.resources.rules_timeline_buy
import com.trackrecord.app.resources.rules_timeline_naming
import com.trackrecord.app.resources.rules_timeline_naming_all
import com.trackrecord.app.resources.rules_timeline_naming_vetoers
import com.trackrecord.app.resources.rules_timeline_skip
import com.trackrecord.app.resources.rules_timeline_turn
import com.trackrecord.app.resources.rules_timeline_veto
import com.trackrecord.app.resources.scoring_closest
import com.trackrecord.app.resources.scoring_distance
import com.trackrecord.app.resources.scoring_rank
import com.trackrecord.app.resources.seconds
import com.trackrecord.app.resources.settings_buy_cost
import com.trackrecord.app.resources.settings_buy_points
import com.trackrecord.app.resources.settings_cards_to_win
import com.trackrecord.app.resources.settings_clip_limit
import com.trackrecord.app.resources.settings_distance_points
import com.trackrecord.app.resources.settings_hidden_guesses
import com.trackrecord.app.resources.settings_max_skips
import com.trackrecord.app.resources.settings_max_vetoes
import com.trackrecord.app.resources.settings_naming_bonus
import com.trackrecord.app.resources.settings_naming_callers
import com.trackrecord.app.resources.settings_naming_skip
import com.trackrecord.app.resources.settings_scoring
import com.trackrecord.app.resources.settings_skip_cost
import com.trackrecord.app.resources.settings_start_offset
import com.trackrecord.app.resources.settings_starting_cards
import com.trackrecord.app.resources.settings_starting_skips
import com.trackrecord.app.resources.settings_starting_vetoes
import com.trackrecord.app.resources.settings_tie_breaker
import com.trackrecord.app.resources.settings_vetoes_per_song
import com.trackrecord.app.resources.settings_year_balance
import com.trackrecord.app.resources.skips
import com.trackrecord.app.resources.tie_both
import com.trackrecord.app.resources.tie_first
import com.trackrecord.app.resources.value_off
import com.trackrecord.app.resources.value_on
import com.trackrecord.app.resources.vetoes
import com.trackrecord.app.resources.year_balance_gentle
import com.trackrecord.app.resources.year_balance_strong
import com.trackrecord.app.ui.Texts
import com.trackrecord.app.ui.components.AppSheet
import com.trackrecord.app.ui.components.SectionCard
import com.trackrecord.data.playlists.BuiltInPlaylists
import com.trackrecord.engine.ClosestScoring
import com.trackrecord.engine.ClosestYearSettings
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameState
import com.trackrecord.engine.NamingCallers
import com.trackrecord.engine.TieBreaker
import com.trackrecord.engine.YearBalance
import com.trackrecord.remote.InfoLine
import com.trackrecord.remote.InfoView
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The info sheet's content (feedback round 36): how the mode being played works, the game's settings,
 * and the playlists its songs come from. Worded here once for the host and the guests' phones.
 */
object GameInfo {
    fun of(session: GameSession): InfoView {
        val c = session.state.settings.common
        fun name(id: Long) = when (id) {
            BuiltInPlaylists.LOCAL_FILES -> Texts.get(Res.string.playlist_local_files)
            BuiltInPlaylists.STREAMED -> Texts.get(Res.string.playlist_streamed)
            BuiltInPlaylists.EXCLUDED -> Texts.get(Res.string.playlist_excluded)
            else -> session.playlistNames[id] ?: Texts.get(Res.string.info_unknown_playlist)
        }
        return InfoView(
            rules = rules(session.state),
            settings = settings(session.state),
            included = c.playlistIds?.map(::name) ?: listOf(Texts.get(Res.string.playlist_all)),
            // "Excluded songs" is always left out (feedback round 28).
            excluded = (c.excludedPlaylistIds.map(::name) + Texts.get(Res.string.playlist_excluded)).distinct(),
            brief = brief(session.state),
        )
    }

    /** The rules in brief for guests' phones (feedback round 51): the aim, and who may name the song for what. */
    private fun brief(s: GameState): List<String> = when (s.mode) {
        GameMode.TIMELINE -> buildList {
            val t = s.settings.timeline
            add(Texts.get(Res.string.brief_timeline))
            if (t.namingBonus) {
                add(
                    Texts.get(
                        when (t.namingCallers) {
                            NamingCallers.ACTIVE -> Res.string.brief_timeline_naming_active
                            NamingCallers.VETOERS -> Res.string.brief_timeline_naming_vetoers
                            NamingCallers.ALL -> Res.string.brief_timeline_naming_all
                        },
                    ),
                )
                if (t.namingCallers != NamingCallers.ACTIVE) add(Texts.get(Res.string.brief_timeline_naming_first))
            }
        }
        GameMode.CLOSEST_YEAR -> buildList {
            val c = s.settings.closestYear
            add(Texts.get(Res.string.brief_closest))
            if (c.namingEarnsSkip) {
                add(Texts.get(Res.string.brief_closest_naming))
                add(Texts.get(if (c.hiddenGuesses) Res.string.brief_closest_naming_hidden else Res.string.brief_closest_naming_turns))
            }
        }
    }

    private fun rules(s: GameState): List<String> = when (s.mode) {
        GameMode.TIMELINE -> buildList {
            val t = s.settings.timeline
            add(Texts.get(Res.string.rules_timeline_turn))
            add(Texts.get(Res.string.rules_timeline_veto))
            if (t.namingBonus) {
                add(
                    Texts.get(
                        when (t.namingCallers) {
                            NamingCallers.ACTIVE -> Res.string.rules_timeline_naming
                            NamingCallers.VETOERS -> Res.string.rules_timeline_naming_vetoers
                            NamingCallers.ALL -> Res.string.rules_timeline_naming_all
                        },
                    ),
                )
            }
            t.skipCost?.let { add(Texts.plural(Res.plurals.rules_timeline_skip, it, it)) }
            t.buyCost?.let { add(Texts.plural(Res.plurals.rules_timeline_buy, it, it)) }
            add(Texts.plural(Res.plurals.rules_target_cards, t.cardsToWin, t.cardsToWin))
        }
        GameMode.CLOSEST_YEAR -> buildList {
            val c = s.settings.closestYear
            add(Texts.get(if (c.hiddenGuesses) Res.string.rules_closest_hidden else Res.string.rules_closest_turns))
            val target = c.target(s.seats)
            when (c.scoring) {
                ClosestScoring.CLOSEST -> {
                    add(Texts.get(Res.string.rules_closest_closest))
                    if (c.exactEndsRound) add(Texts.get(Res.string.rules_closest_exact))
                    add(Texts.plural(Res.plurals.rules_target_cards, target, target))
                }
                ClosestScoring.RANK -> {
                    add(Texts.plural(Res.plurals.rules_closest_rank, s.seats - 1, s.seats, s.seats - 1))
                    add(Texts.plural(Res.plurals.rules_target_points, target, target))
                }
                ClosestScoring.DISTANCE -> {
                    add(Texts.plural(Res.plurals.rules_closest_distance, c.distancePoints, c.distancePoints))
                    add(Texts.plural(Res.plurals.rules_target_points, target, target))
                }
            }
            if (c.usesTieBreaker) {
                add(
                    Texts.get(
                        when {
                            c.tieBreaker == TieBreaker.FIRST_SCORES -> Res.string.rules_tie_first
                            c.scoring == ClosestScoring.RANK -> Res.string.rules_tie_both_rank
                            else -> Res.string.rules_tie_both
                        },
                    ),
                )
            }
            if (c.startingSkips > 0 || c.namingEarnsSkip) add(Texts.get(Res.string.rules_closest_skip))
            if (c.namingEarnsSkip) add(Texts.get(Res.string.rules_closest_naming))
            c.buyCost?.let { cost ->
                if (c.scoring == ClosestScoring.CLOSEST) add(Texts.plural(Res.plurals.rules_closest_buy, cost, cost))
                else {
                    // Points, not a card (feedback round 50).
                    val points = c.perfectRound(s.seats)
                    add(Texts.get(Res.string.rules_closest_buy_points, Texts.plural(Res.plurals.points, points, points), Texts.plural(Res.plurals.skips, cost, cost)))
                }
            }
        }
    }

    private fun settings(s: GameState): List<InfoLine> = buildList {
        fun line(label: StringResource, value: String) = add(InfoLine(Texts.get(label), value))
        fun onOff(on: Boolean) = Texts.get(if (on) Res.string.value_on else Res.string.value_off)
        fun orOff(value: Int?) = value?.toString() ?: Texts.get(Res.string.value_off)
        when (s.mode) {
            GameMode.TIMELINE -> {
                val t = s.settings.timeline
                line(Res.string.info_mode, Texts.get(Res.string.mode_timeline))
                line(Res.string.settings_cards_to_win, t.cardsToWin.toString())
                line(Res.string.settings_starting_cards, t.startingCards.toString())
                line(Res.string.settings_starting_vetoes, t.startingVetoes.toString())
                line(Res.string.settings_max_vetoes, t.maxVetoes.toString())
                line(Res.string.settings_vetoes_per_song, t.vetoesPerPlayerPerSong.toString())
                line(Res.string.settings_naming_bonus, onOff(t.namingBonus))
                // Every rule of the setup screen, each where it applies (feedback round 43).
                if (t.namingBonus) line(Res.string.settings_naming_callers, Texts.get(t.namingCallers.label))
                // With their unit (DESIGN-REVIEW UX-05).
                line(Res.string.settings_skip_cost, t.skipCost?.let { Texts.plural(Res.plurals.vetoes, it, it) } ?: Texts.get(Res.string.value_off))
                line(Res.string.settings_buy_cost, t.buyCost?.let { Texts.plural(Res.plurals.vetoes, it, it) } ?: Texts.get(Res.string.value_off))
            }
            GameMode.CLOSEST_YEAR -> {
                val c = s.settings.closestYear
                line(Res.string.info_mode, Texts.get(Res.string.mode_closest))
                line(Res.string.settings_hidden_guesses, onOff(c.hiddenGuesses))
                line(Res.string.settings_scoring, Texts.get(c.scoring.label))
                if (c.scoring == ClosestScoring.DISTANCE) line(Res.string.settings_distance_points, c.distancePoints.toString())
                val target = c.target(s.seats)
                line(
                    Res.string.info_target,
                    if (c.scoring == ClosestScoring.CLOSEST) Texts.plural(Res.plurals.cards, target, target) else Texts.plural(Res.plurals.points, target, target),
                )
                if (c.usesTieBreaker) line(Res.string.settings_tie_breaker, Texts.get(c.tieBreaker.label))
                line(Res.string.settings_starting_skips, c.startingSkips.toString())
                line(Res.string.settings_max_skips, orOff(c.maxSkips))
                line(Res.string.settings_naming_skip, onOff(c.namingEarnsSkip))
                line(c.buyLabel, c.buyCost?.let { Texts.plural(Res.plurals.skips, it, it) } ?: Texts.get(Res.string.value_off))
            }
        }
        val common = s.settings.common
        if (common.yearFrom != null || common.yearTo != null) {
            line(Res.string.info_years, Texts.get(Res.string.info_year_range, common.yearFrom?.toString() ?: "…", common.yearTo?.toString() ?: "…"))
        }
        line(Res.string.settings_clip_limit, common.clipLimitSeconds?.let { Texts.plural(Res.plurals.seconds, it, it) } ?: Texts.get(Res.string.value_off))
        line(Res.string.settings_start_offset, Texts.plural(Res.plurals.seconds, common.startOffsetSeconds, common.startOffsetSeconds))
        line(Res.string.settings_year_balance, Texts.get(common.yearBalance.label))
    }
}

val ClosestScoring.label: StringResource
    get() = when (this) {
        ClosestScoring.CLOSEST -> Res.string.scoring_closest
        ClosestScoring.RANK -> Res.string.scoring_rank
        ClosestScoring.DISTANCE -> Res.string.scoring_distance
    }

/** How much the shuffle evened out the years (YEAR_BALANCE.md). */
val YearBalance.label: StringResource
    get() = when (this) {
        YearBalance.OFF -> Res.string.value_off
        YearBalance.GENTLE -> Res.string.year_balance_gentle
        YearBalance.STRONG -> Res.string.year_balance_strong
    }

/** "Buy a card", or "Buy points" in Rank and Distance (feedback round 50). */
val ClosestYearSettings.buyLabel: StringResource
    get() = if (scoring == ClosestScoring.CLOSEST) Res.string.settings_buy_cost else Res.string.settings_buy_points

val TieBreaker.label: StringResource
    get() = when (this) {
        TieBreaker.BOTH_SCORE -> Res.string.tie_both
        TieBreaker.FIRST_SCORES -> Res.string.tie_first
    }

/** The info button's sheet (feedback round 36): the rules folded away, then the settings and playlists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(session: GameSession, onDismiss: () -> Unit) {
    val info = remember(session.state.settings, session.state.seats, session.playlistNames) { GameInfo.of(session) }
    var rulesOpen by rememberSaveable { mutableStateOf(false) }
    // Opened all the way, at a fixed height well below the status bar (feedback rounds 37, 38, 39): on
    // Felix's phone a sheet reaching the status bar made room for it, grew, and settled again without end.
    AppSheet(stringResource(Res.string.info_title), onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            sheetBody().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(stringResource(Res.string.info_rules), expanded = rulesOpen, onToggle = { rulesOpen = !rulesOpen }) {
                info.rules.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            SectionCard(stringResource(Res.string.info_settings), spacing = 6.dp) {
                for (line in info.settings) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(line.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(line.value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                    }
                }
            }
            SectionCard(stringResource(Res.string.info_songs), spacing = 4.dp) {
                Text(stringResource(Res.string.info_included), style = MaterialTheme.typography.titleSmall)
                info.included.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(Res.string.info_excluded), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                info.excluded.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
