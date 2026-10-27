package com.trackrecord.app.game

import android.content.Context
import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R
import com.trackrecord.app.newgame.label
import com.trackrecord.app.ui.components.AppSheet
import com.trackrecord.data.playlists.BuiltInPlaylists
import com.trackrecord.engine.ClosestScoring
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameState
import com.trackrecord.engine.NamingCallers
import com.trackrecord.engine.TieBreaker
import com.trackrecord.remote.InfoLine
import com.trackrecord.remote.InfoView

/**
 * The info sheet's content (feedback round 36): how the mode being played works, the game's settings,
 * and the playlists its songs come from. Worded here once for the host and the guests' phones.
 */
object GameInfo {
    fun of(context: Context, session: GameSession): InfoView {
        val r = context.resources
        val c = session.state.settings.common
        fun name(id: Long) = when (id) {
            BuiltInPlaylists.LOCAL_FILES -> r.getString(R.string.playlist_local_files)
            BuiltInPlaylists.STREAMED -> r.getString(R.string.playlist_streamed)
            BuiltInPlaylists.EXCLUDED -> r.getString(R.string.playlist_excluded)
            else -> session.playlistNames[id] ?: r.getString(R.string.info_unknown_playlist)
        }
        return InfoView(
            rules = rules(r, session.state),
            settings = settings(r, session.state),
            included = c.playlistIds?.map(::name) ?: listOf(r.getString(R.string.playlist_all)),
            // "Excluded songs" is always left out (feedback round 28).
            excluded = (c.excludedPlaylistIds.map(::name) + r.getString(R.string.playlist_excluded)).distinct(),
        )
    }

    private fun rules(r: Resources, s: GameState): List<String> = when (s.mode) {
        GameMode.TIMELINE -> buildList {
            val t = s.settings.timeline
            add(r.getString(R.string.rules_timeline_turn))
            add(r.getString(R.string.rules_timeline_veto))
            if (t.namingBonus) {
                add(
                    r.getString(
                        when (t.namingCallers) {
                            NamingCallers.ACTIVE -> R.string.rules_timeline_naming
                            NamingCallers.VETOERS -> R.string.rules_timeline_naming_vetoers
                            NamingCallers.ALL -> R.string.rules_timeline_naming_all
                        },
                    ),
                )
            }
            t.skipCost?.let { add(r.getQuantityString(R.plurals.rules_timeline_skip, it, it)) }
            t.buyCost?.let { add(r.getQuantityString(R.plurals.rules_timeline_buy, it, it)) }
            add(r.getQuantityString(R.plurals.rules_target_cards, t.cardsToWin, t.cardsToWin))
        }
        GameMode.CLOSEST_YEAR -> buildList {
            val c = s.settings.closestYear
            add(r.getString(if (c.hiddenGuesses) R.string.rules_closest_hidden else R.string.rules_closest_turns))
            val target = c.target(s.seats)
            when (c.scoring) {
                ClosestScoring.CLOSEST -> {
                    add(r.getString(R.string.rules_closest_closest))
                    if (c.exactEndsRound) add(r.getString(R.string.rules_closest_exact))
                    add(r.getQuantityString(R.plurals.rules_target_cards, target, target))
                }
                ClosestScoring.RANK -> {
                    add(r.getQuantityString(R.plurals.rules_closest_rank, s.seats - 1, s.seats, s.seats - 1))
                    add(r.getQuantityString(R.plurals.rules_target_points, target, target))
                }
                ClosestScoring.DISTANCE -> {
                    add(r.getQuantityString(R.plurals.rules_closest_distance, c.distancePoints, c.distancePoints))
                    add(r.getQuantityString(R.plurals.rules_target_points, target, target))
                }
            }
            if (c.usesTieBreaker) {
                add(
                    r.getString(
                        when {
                            c.tieBreaker == TieBreaker.FIRST_SCORES -> R.string.rules_tie_first
                            c.scoring == ClosestScoring.RANK -> R.string.rules_tie_both_rank
                            else -> R.string.rules_tie_both
                        },
                    ),
                )
            }
            if (c.startingSkips > 0 || c.namingEarnsSkip) add(r.getString(R.string.rules_closest_skip))
            if (c.namingEarnsSkip) add(r.getString(R.string.rules_closest_naming))
            c.buyCost?.let { add(r.getQuantityString(R.plurals.rules_closest_buy, it, it)) }
        }
    }

    private fun settings(r: Resources, s: GameState): List<InfoLine> = buildList {
        fun line(label: Int, value: String) = add(InfoLine(r.getString(label), value))
        fun onOff(on: Boolean) = r.getString(if (on) R.string.value_on else R.string.value_off)
        fun orOff(value: Int?) = value?.toString() ?: r.getString(R.string.value_off)
        when (s.mode) {
            GameMode.TIMELINE -> {
                val t = s.settings.timeline
                line(R.string.info_mode, r.getString(R.string.mode_timeline))
                line(R.string.settings_cards_to_win, t.cardsToWin.toString())
                line(R.string.settings_starting_cards, t.startingCards.toString())
                line(R.string.settings_starting_vetoes, t.startingVetoes.toString())
                line(R.string.settings_max_vetoes, t.maxVetoes.toString())
                line(R.string.settings_vetoes_per_song, t.vetoesPerPlayerPerSong.toString())
                line(R.string.settings_naming_bonus, onOff(t.namingBonus))
                // Every rule of the setup screen, each where it applies (feedback round 43).
                if (t.namingBonus) line(R.string.settings_naming_callers, r.getString(t.namingCallers.label))
                // With their unit (DESIGN-REVIEW UX-05).
                line(R.string.settings_skip_cost, t.skipCost?.let { r.getQuantityString(R.plurals.vetoes, it, it) } ?: r.getString(R.string.value_off))
                line(R.string.settings_buy_cost, t.buyCost?.let { r.getQuantityString(R.plurals.vetoes, it, it) } ?: r.getString(R.string.value_off))
            }
            GameMode.CLOSEST_YEAR -> {
                val c = s.settings.closestYear
                line(R.string.info_mode, r.getString(R.string.mode_closest))
                line(R.string.settings_hidden_guesses, onOff(c.hiddenGuesses))
                line(R.string.settings_scoring, r.getString(c.scoring.label))
                if (c.scoring == ClosestScoring.DISTANCE) line(R.string.settings_distance_points, c.distancePoints.toString())
                val target = c.target(s.seats)
                line(
                    R.string.info_target,
                    if (c.scoring == ClosestScoring.CLOSEST) r.getQuantityString(R.plurals.cards, target, target) else r.getQuantityString(R.plurals.points, target, target),
                )
                if (c.usesTieBreaker) line(R.string.settings_tie_breaker, r.getString(c.tieBreaker.label))
                line(R.string.settings_starting_skips, c.startingSkips.toString())
                line(R.string.settings_max_skips, orOff(c.maxSkips))
                line(R.string.settings_naming_skip, onOff(c.namingEarnsSkip))
                line(R.string.settings_buy_cost, c.buyCost?.let { r.getQuantityString(R.plurals.skips, it, it) } ?: r.getString(R.string.value_off))
            }
        }
        val common = s.settings.common
        if (common.yearFrom != null || common.yearTo != null) {
            line(R.string.info_years, r.getString(R.string.info_year_range, common.yearFrom?.toString() ?: "…", common.yearTo?.toString() ?: "…"))
        }
        line(R.string.settings_clip_limit, common.clipLimitSeconds?.let { r.getQuantityString(R.plurals.seconds, it, it) } ?: r.getString(R.string.value_off))
        line(R.string.settings_start_offset, r.getQuantityString(R.plurals.seconds, common.startOffsetSeconds, common.startOffsetSeconds))
    }
}

val ClosestScoring.label: Int
    get() = when (this) {
        ClosestScoring.CLOSEST -> R.string.scoring_closest
        ClosestScoring.RANK -> R.string.scoring_rank
        ClosestScoring.DISTANCE -> R.string.scoring_distance
    }

val TieBreaker.label: Int
    get() = when (this) {
        TieBreaker.BOTH_SCORE -> R.string.tie_both
        TieBreaker.FIRST_SCORES -> R.string.tie_first
    }

/** The info button's sheet (feedback round 36): the rules folded away, then the settings and playlists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(session: GameSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = remember(session.state.settings, session.state.seats, session.playlistNames) { GameInfo.of(context, session) }
    var rulesOpen by rememberSaveable { mutableStateOf(false) }
    // Opened all the way, at a fixed height well below the status bar (feedback rounds 37, 38, 39): on
    // Felix's phone a sheet reaching the status bar made room for it, grew, and settled again without end.
    AppSheet(stringResource(R.string.info_title), onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            sheetBody().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(stringResource(R.string.info_rules), expanded = rulesOpen, onToggle = { rulesOpen = !rulesOpen }) {
                info.rules.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            SectionCard(stringResource(R.string.info_settings), spacing = 6.dp) {
                for (line in info.settings) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(line.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(line.value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                    }
                }
            }
            SectionCard(stringResource(R.string.info_songs), spacing = 4.dp) {
                Text(stringResource(R.string.info_included), style = MaterialTheme.typography.titleSmall)
                info.included.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.info_excluded), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                info.excluded.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
