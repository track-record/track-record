package com.trackrecord.app.newgame

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.game.buyLabel
import com.trackrecord.app.game.label
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_cancel
import com.trackrecord.app.resources.action_save
import com.trackrecord.app.resources.naming_callers_active
import com.trackrecord.app.resources.naming_callers_active_hint
import com.trackrecord.app.resources.naming_callers_all
import com.trackrecord.app.resources.naming_callers_all_hint
import com.trackrecord.app.resources.naming_callers_vetoers
import com.trackrecord.app.resources.naming_callers_vetoers_hint
import com.trackrecord.app.resources.preset_classic
import com.trackrecord.app.resources.preset_classic_hint
import com.trackrecord.app.resources.preset_closest
import com.trackrecord.app.resources.preset_closest_hint
import com.trackrecord.app.resources.preset_custom
import com.trackrecord.app.resources.preset_custom_hint
import com.trackrecord.app.resources.preset_delete
import com.trackrecord.app.resources.preset_distance
import com.trackrecord.app.resources.preset_distance_hint
import com.trackrecord.app.resources.preset_fixed
import com.trackrecord.app.resources.preset_fixed_hint
import com.trackrecord.app.resources.preset_name
import com.trackrecord.app.resources.preset_ranking
import com.trackrecord.app.resources.preset_ranking_hint
import com.trackrecord.app.resources.preset_save
import com.trackrecord.app.resources.scoring_closest_hint
import com.trackrecord.app.resources.scoring_distance_hint
import com.trackrecord.app.resources.scoring_rank_hint
import com.trackrecord.app.resources.seconds
import com.trackrecord.app.resources.settings_auto_play
import com.trackrecord.app.resources.settings_buy_cost
import com.trackrecord.app.resources.settings_cards_to_win
import com.trackrecord.app.resources.settings_cards_to_win_hint
import com.trackrecord.app.resources.settings_clip_limit
import com.trackrecord.app.resources.settings_closest_fixed_rules
import com.trackrecord.app.resources.settings_distance_points
import com.trackrecord.app.resources.settings_hidden_guesses
import com.trackrecord.app.resources.settings_hidden_guesses_hint
import com.trackrecord.app.resources.settings_max_skips
import com.trackrecord.app.resources.settings_max_vetoes
import com.trackrecord.app.resources.settings_musicbrainz
import com.trackrecord.app.resources.settings_musicbrainz_hint
import com.trackrecord.app.resources.settings_naming_bonus
import com.trackrecord.app.resources.settings_naming_bonus_hint
import com.trackrecord.app.resources.settings_naming_callers
import com.trackrecord.app.resources.settings_naming_skip
import com.trackrecord.app.resources.settings_naming_skip_hint
import com.trackrecord.app.resources.settings_perfect_rounds
import com.trackrecord.app.resources.settings_perfect_rounds_hint_distance
import com.trackrecord.app.resources.settings_perfect_rounds_hint_rank
import com.trackrecord.app.resources.settings_playback
import com.trackrecord.app.resources.settings_preset
import com.trackrecord.app.resources.settings_rules
import com.trackrecord.app.resources.settings_scoring
import com.trackrecord.app.resources.settings_skip_cost
import com.trackrecord.app.resources.settings_skip_costs
import com.trackrecord.app.resources.settings_skip_costs_hint
import com.trackrecord.app.resources.settings_start_offset
import com.trackrecord.app.resources.settings_starting_cards
import com.trackrecord.app.resources.settings_starting_skips
import com.trackrecord.app.resources.settings_starting_skips_hint
import com.trackrecord.app.resources.settings_starting_vetoes
import com.trackrecord.app.resources.settings_tie_breaker
import com.trackrecord.app.resources.settings_veto_costs
import com.trackrecord.app.resources.settings_veto_costs_hint
import com.trackrecord.app.resources.settings_vetoes_per_song
import com.trackrecord.app.resources.settings_wins_to_win
import com.trackrecord.app.resources.summary_buy_for
import com.trackrecord.app.resources.summary_buy_points_for
import com.trackrecord.app.resources.summary_cards_to_win
import com.trackrecord.app.resources.summary_naming
import com.trackrecord.app.resources.summary_perfect_rounds
import com.trackrecord.app.resources.summary_scoring_distance
import com.trackrecord.app.resources.summary_scoring_rank
import com.trackrecord.app.resources.summary_skip_for
import com.trackrecord.app.resources.summary_skips
import com.trackrecord.app.resources.summary_skips_no_max
import com.trackrecord.app.resources.summary_tie_first
import com.trackrecord.app.resources.summary_vetoes
import com.trackrecord.app.resources.tie_both_hint
import com.trackrecord.app.resources.tie_first_hint
import com.trackrecord.app.ui.components.OptionalStepper
import com.trackrecord.app.ui.components.PanelGap
import com.trackrecord.app.ui.components.SectionCard
import com.trackrecord.app.ui.components.Stepper
import com.trackrecord.app.ui.components.SwitchRow
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.data.settings.CustomPreset
import com.trackrecord.engine.ClosestScoring
import com.trackrecord.engine.ClosestYearPreset
import com.trackrecord.engine.ClosestYearSettings
import com.trackrecord.engine.CommonSettings
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.NamingCallers
import com.trackrecord.engine.TieBreaker
import com.trackrecord.engine.TimelinePreset
import com.trackrecord.engine.TimelineSettings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Every rule value of a mode plus the playback settings, each part in its panel. Used by New game and Settings. */
@Composable
fun GameSettingsEditor(mode: GameMode, settings: GameSettings, onChange: ((GameSettings) -> GameSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PanelGap)) {
        when (mode) {
            GameMode.TIMELINE -> TimelineEditor(settings.timeline) { t -> onChange { it.copy(timeline = t) } }
            GameMode.CLOSEST_YEAR -> ClosestYearEditor(settings.closestYear) { c -> onChange { it.copy(closestYear = c) } }
        }
        PlaybackEditor(settings.common) { c -> onChange { it.copy(common = c) } }
    }
}

@Composable
fun TimelineEditor(t: TimelineSettings, onChange: (TimelineSettings) -> Unit) {
    SectionCard(stringResource(Res.string.settings_preset), spacing = 4.dp) {
        val current = t.preset
        val presets = rememberCustomPresets(GameMode.TIMELINE)
        val saved = presets.list.firstOrNull { it.timeline == t }
        // Classic first (feedback round 11); the user's own presets before Custom (feedback round 27).
        for (preset in TIMELINE_PRESET_ORDER) {
            if (preset == TimelinePreset.CUSTOM) SavedPresetRows(presets, saved, { timelineSummary(it.timeline!!) }) { p -> p.timeline?.let { onChange(it.withCostsWithinMax()) } }
            PresetRow(stringResource(preset.label), stringResource(preset.hint), preset == current && (preset != TimelinePreset.CUSTOM || saved == null), enabled = preset.settings != null) {
                preset.settings?.let(onChange)
            }
        }
        if (current == TimelinePreset.CUSTOM && saved == null) SavePresetButton { name -> presets.save(CustomPreset(name, timeline = t)) }
    }
    SectionCard(stringResource(Res.string.settings_rules), spacing = 4.dp) {
        Stepper(stringResource(Res.string.settings_starting_cards), t.startingCards, 1..3, { onChange(t.copy(startingCards = it, cardsToWin = maxOf(t.cardsToWin, it + 1))) })
        Stepper(stringResource(Res.string.settings_cards_to_win), t.cardsToWin, maxOf(3, t.startingCards + 1)..30, { onChange(t.copy(cardsToWin = it)) }, hint = stringResource(Res.string.settings_cards_to_win_hint))
        Stepper(stringResource(Res.string.settings_starting_vetoes), t.startingVetoes, 0..10, { onChange(t.copy(startingVetoes = it, maxVetoes = maxOf(t.maxVetoes, it))) })
        Stepper(stringResource(Res.string.settings_max_vetoes), t.maxVetoes, t.startingVetoes..10, { onChange(t.copy(maxVetoes = it).withCostsWithinMax()) })
        Stepper(stringResource(Res.string.settings_vetoes_per_song), t.vetoesPerPlayerPerSong, 1..3, { onChange(t.copy(vetoesPerPlayerPerSong = it)) })
        SwitchRow(stringResource(Res.string.settings_naming_bonus), t.namingBonus, { onChange(t.copy(namingBonus = it)) }, hint = stringResource(Res.string.settings_naming_bonus_hint))
        if (t.namingBonus) {
            // Who may call out song and artist (feedback round 19).
            Text(stringResource(Res.string.settings_naming_callers), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            for (callers in NamingCallers.entries) {
                PresetRow(stringResource(callers.label), stringResource(callers.hint), t.namingCallers == callers, enabled = true) {
                    onChange(t.copy(namingCallers = callers))
                }
            }
        }
    }
    // Costs in vetoes: the section says so, so the values stay as short as the other steppers' (feedback round 10).
    SectionCard(stringResource(Res.string.settings_veto_costs), spacing = 4.dp) {
        Text(stringResource(Res.string.settings_veto_costs_hint), style = MaterialTheme.typography.bodySmall)
        // Never more than the most vetoes held, or it could never be paid (D-267).
        OptionalStepper(stringResource(Res.string.settings_skip_cost), t.skipCost, 1..minOf(3, t.maxVetoes), { onChange(t.copy(skipCost = it)) })
        OptionalStepper(stringResource(Res.string.settings_buy_cost), t.buyCost, 1..minOf(5, t.maxVetoes), { onChange(t.copy(buyCost = it)) })
    }
}

/** Timeline presets in the order shown (Settings and New game). */
val TIMELINE_PRESET_ORDER = listOf(TimelinePreset.CLASSIC, TimelinePreset.FIXED_VETOES, TimelinePreset.CUSTOM)

/** The user's saved presets of one mode (feedback round 27), kept in the app settings. */
class CustomPresets(val list: List<CustomPreset>, val save: (CustomPreset) -> Unit, val delete: (CustomPreset) -> Unit)

@Composable
private fun rememberCustomPresets(mode: GameMode): CustomPresets {
    val repo = LocalAppContainer.current.settings
    val app by repo.appSettings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val all = app?.customPresets.orEmpty()
    return CustomPresets(
        list = all.filter { it.mode == mode },
        // The same name again replaces that preset.
        save = { p -> scope.launch { repo.update { s -> s.copy(customPresets = s.customPresets.filterNot { it.mode == p.mode && it.name == p.name } + p) } } },
        delete = { p -> scope.launch { repo.update { s -> s.copy(customPresets = s.customPresets - p) } } },
    )
}

/** One row per saved preset: pick it, or delete it with the bin. */
@Composable
private fun SavedPresetRows(presets: CustomPresets, selected: CustomPreset?, summary: @Composable (CustomPreset) -> String, onPick: (CustomPreset) -> Unit) {
    for (p in presets.list) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PresetRow(p.name, summary(p), p == selected, enabled = true) { onPick(p) } }
            IconButton(onClick = { presets.delete(p) }) { Icon(Icons.Default.Delete, stringResource(Res.string.preset_delete, p.name)) }
        }
    }
}

/** "Save as preset" with a name, shown while the rules match no preset. */
@Composable
private fun SavePresetButton(onSave: (String) -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { naming = true }) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(Res.string.preset_save), modifier = Modifier.padding(start = 8.dp))
    }
    if (!naming) return
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { naming = false },
        title = { Text(stringResource(Res.string.preset_save)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_PRESET_NAME) },
                label = { Text(stringResource(Res.string.preset_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()); naming = false }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

private const val MAX_PRESET_NAME = 40

/** A saved preset's rules in one line, e.g. "10 cards to win · 2 vetoes at the start, max 5 · Skip for 1". */
@Composable
private fun timelineSummary(t: TimelineSettings): String = listOfNotNull(
    pluralStringResource(Res.plurals.summary_cards_to_win, t.cardsToWin, t.cardsToWin),
    pluralStringResource(Res.plurals.summary_vetoes, t.startingVetoes, t.startingVetoes, t.maxVetoes),
    if (t.namingBonus) stringResource(Res.string.summary_naming) else null,
    t.skipCost?.let { stringResource(Res.string.summary_skip_for, it) },
    t.buyCost?.let { stringResource(Res.string.summary_buy_for, it) },
).joinToString(" · ")

@Composable
private fun closestSummary(c: ClosestYearSettings): String = listOfNotNull(
    when (c.scoring) {
        ClosestScoring.CLOSEST -> pluralStringResource(Res.plurals.summary_cards_to_win, c.winsToWin, c.winsToWin)
        else -> pluralStringResource(Res.plurals.summary_perfect_rounds, c.winsToWin, c.winsToWin)
    },
    when (c.scoring) {
        ClosestScoring.CLOSEST -> null
        ClosestScoring.RANK -> stringResource(Res.string.summary_scoring_rank)
        ClosestScoring.DISTANCE -> pluralStringResource(Res.plurals.summary_scoring_distance, c.distancePoints, c.distancePoints)
    },
    if (c.usesTieBreaker && c.tieBreaker == TieBreaker.FIRST_SCORES) stringResource(Res.string.summary_tie_first) else null,
    c.maxSkips?.let { pluralStringResource(Res.plurals.summary_skips, c.startingSkips, c.startingSkips, it) }
        ?: pluralStringResource(Res.plurals.summary_skips_no_max, c.startingSkips, c.startingSkips),
    if (c.namingEarnsSkip) stringResource(Res.string.summary_naming) else null,
    c.buyCost?.let { stringResource(if (c.scoring == ClosestScoring.CLOSEST) Res.string.summary_buy_for else Res.string.summary_buy_points_for, it) },
).joinToString(" · ")

@Composable
private fun PresetRow(label: String, hint: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ClosestYearEditor(c: ClosestYearSettings, onChange: (ClosestYearSettings) -> Unit) {
    // Presets at the top, then the rules: hidden guesses, scoring, the rest; ties after the costs (feedback round 37).
    SectionCard(stringResource(Res.string.settings_preset), spacing = 4.dp) {
        val current = c.preset
        val presets = rememberCustomPresets(GameMode.CLOSEST_YEAR)
        val saved = presets.list.firstOrNull { it.closestYear?.sameRules(c) == true }
        for (preset in ClosestYearPreset.entries) {
            if (preset == ClosestYearPreset.CUSTOM) SavedPresetRows(presets, saved, { closestSummary(it.closestYear!!) }) { p -> p.closestYear?.let { onChange(c.withRulesOf(it).withCostsWithinMax()) } }
            PresetRow(stringResource(preset.label), stringResource(preset.hint), preset == current && (preset != ClosestYearPreset.CUSTOM || saved == null), enabled = preset.settings != null) {
                onChange(c.withPreset(preset))
            }
        }
        if (current == ClosestYearPreset.CUSTOM && saved == null) SavePresetButton { name -> presets.save(CustomPreset(name, closestYear = c)) }
    }
    SectionCard(stringResource(Res.string.settings_rules), spacing = 4.dp) {
        SwitchRow(stringResource(Res.string.settings_hidden_guesses), c.hiddenGuesses, { onChange(c.copy(hiddenGuesses = it)) }, hint = stringResource(Res.string.settings_hidden_guesses_hint))
        // How a round scores (feedback round 36).
        Text(stringResource(Res.string.settings_scoring), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        for (scoring in ClosestScoring.entries) {
            PresetRow(stringResource(scoring.label), stringResource(scoring.hint), c.scoring == scoring, enabled = true) { onChange(c.copy(scoring = scoring)) }
        }
        if (c.scoring == ClosestScoring.DISTANCE) {
            Stepper(stringResource(Res.string.settings_distance_points), c.distancePoints, 1..20, { onChange(c.copy(distancePoints = it)) })
        }
        when (c.scoring) {
            ClosestScoring.CLOSEST -> Stepper(stringResource(Res.string.settings_wins_to_win), c.winsToWin, 1..30, { onChange(c.copy(winsToWin = it)) })
            // The target in points is this many perfect rounds (feedback round 36).
            ClosestScoring.RANK -> Stepper(
                stringResource(Res.string.settings_perfect_rounds), c.winsToWin, 1..30, { onChange(c.copy(winsToWin = it)) },
                hint = stringResource(Res.string.settings_perfect_rounds_hint_rank),
            )
            ClosestScoring.DISTANCE -> Stepper(
                stringResource(Res.string.settings_perfect_rounds), c.winsToWin, 1..30, { onChange(c.copy(winsToWin = it)) },
                hint = c.target(seats = 2).let { pluralStringResource(Res.plurals.settings_perfect_rounds_hint_distance, it, it) },
            )
        }
        Stepper(
            stringResource(Res.string.settings_starting_skips),
            c.startingSkips,
            0..5,
            { onChange(c.copy(startingSkips = it, maxSkips = c.maxSkips?.let { max -> maxOf(max, it) })) },
            hint = stringResource(Res.string.settings_starting_skips_hint),
        )
        OptionalStepper(stringResource(Res.string.settings_max_skips), c.maxSkips, maxOf(1, c.startingSkips)..10, { onChange(c.copy(maxSkips = it).withCostsWithinMax()) })
        SwitchRow(stringResource(Res.string.settings_naming_skip), c.namingEarnsSkip, { onChange(c.copy(namingEarnsSkip = it)) }, hint = stringResource(Res.string.settings_naming_skip_hint))
        // Exact guess ends the round, rotation and distinct guesses are always on (as far as the scoring and
        // hidden guesses allow); the lowest guessable year follows the songs (DECISIONS.md D-066).
        Text(stringResource(Res.string.settings_closest_fixed_rules), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
    // Costs in skips, like Song Order's veto costs (feedback round 27).
    SectionCard(stringResource(Res.string.settings_skip_costs), spacing = 4.dp) {
        Text(stringResource(Res.string.settings_skip_costs_hint), style = MaterialTheme.typography.bodySmall)
        OptionalStepper(stringResource(c.buyLabel), c.buyCost, 1..minOf(5, c.maxSkips ?: 5), { onChange(c.copy(buyCost = it)) })
    }
    if (c.usesTieBreaker) {
        SectionCard(stringResource(Res.string.settings_tie_breaker), spacing = 4.dp) {
            for (tie in TieBreaker.entries) {
                PresetRow(stringResource(tie.label), stringResource(tie.hint), c.tieBreaker == tie, enabled = true) { onChange(c.copy(tieBreaker = tie)) }
            }
        }
    }
}

@Composable
fun PlaybackEditor(c: CommonSettings, includeMusicBrainz: Boolean = true, onChange: (CommonSettings) -> Unit) {
    SectionCard(stringResource(Res.string.settings_playback), spacing = 4.dp) {
        val seconds: @Composable (Int) -> String = { n -> pluralStringResource(Res.plurals.seconds, n, n) }
        SwitchRow(stringResource(Res.string.settings_auto_play), c.autoPlay, { onChange(c.copy(autoPlay = it)) })
        Stepper(stringResource(Res.string.settings_start_offset), c.startOffsetSeconds, 0..60, { onChange(c.copy(startOffsetSeconds = it)) }, step = 5, format = { seconds(it) })
        OptionalStepper(stringResource(Res.string.settings_clip_limit), c.clipLimitSeconds, 10..120, { onChange(c.copy(clipLimitSeconds = it)) }, step = 5, unit = { seconds(it) })
        if (includeMusicBrainz) {
            SwitchRow(stringResource(Res.string.settings_musicbrainz), c.musicBrainzLookups, { onChange(c.copy(musicBrainzLookups = it)) }, hint = stringResource(Res.string.settings_musicbrainz_hint))
        }
    }
}

val TimelinePreset.label: StringResource
    get() = when (this) {
        TimelinePreset.FIXED_VETOES -> Res.string.preset_fixed
        TimelinePreset.CLASSIC -> Res.string.preset_classic
        TimelinePreset.CUSTOM -> Res.string.preset_custom
    }

val NamingCallers.label: StringResource
    get() = when (this) {
        NamingCallers.ACTIVE -> Res.string.naming_callers_active
        NamingCallers.VETOERS -> Res.string.naming_callers_vetoers
        NamingCallers.ALL -> Res.string.naming_callers_all
    }

private val NamingCallers.hint: StringResource
    get() = when (this) {
        NamingCallers.ACTIVE -> Res.string.naming_callers_active_hint
        NamingCallers.VETOERS -> Res.string.naming_callers_vetoers_hint
        NamingCallers.ALL -> Res.string.naming_callers_all_hint
    }

val ClosestYearPreset.label: StringResource
    get() = when (this) {
        ClosestYearPreset.CLOSEST -> Res.string.preset_closest
        ClosestYearPreset.RANKING -> Res.string.preset_ranking
        ClosestYearPreset.DISTANCE -> Res.string.preset_distance
        ClosestYearPreset.CUSTOM -> Res.string.preset_custom
    }

private val ClosestYearPreset.hint: StringResource
    get() = when (this) {
        ClosestYearPreset.CLOSEST -> Res.string.preset_closest_hint
        ClosestYearPreset.RANKING -> Res.string.preset_ranking_hint
        ClosestYearPreset.DISTANCE -> Res.string.preset_distance_hint
        ClosestYearPreset.CUSTOM -> Res.string.preset_custom_hint
    }

private val ClosestScoring.hint: StringResource
    get() = when (this) {
        ClosestScoring.CLOSEST -> Res.string.scoring_closest_hint
        ClosestScoring.RANK -> Res.string.scoring_rank_hint
        ClosestScoring.DISTANCE -> Res.string.scoring_distance_hint
    }

private val TieBreaker.hint: StringResource
    get() = when (this) {
        TieBreaker.BOTH_SCORE -> Res.string.tie_both_hint
        TieBreaker.FIRST_SCORES -> Res.string.tie_first_hint
    }

private val TimelinePreset.hint: StringResource
    get() = when (this) {
        TimelinePreset.FIXED_VETOES -> Res.string.preset_fixed_hint
        TimelinePreset.CLASSIC -> Res.string.preset_classic_hint
        TimelinePreset.CUSTOM -> Res.string.preset_custom_hint
    }
