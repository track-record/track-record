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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.game.label
import com.trackrecord.app.ui.components.OptionalStepper
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

/** Every rule value of a mode plus the playback settings. Used by New game and Settings. */
@Composable
fun GameSettingsEditor(mode: GameMode, settings: GameSettings, onChange: ((GameSettings) -> GameSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (mode) {
            GameMode.TIMELINE -> TimelineEditor(settings.timeline) { t -> onChange { it.copy(timeline = t) } }
            GameMode.CLOSEST_YEAR -> ClosestYearEditor(settings.closestYear) { c -> onChange { it.copy(closestYear = c) } }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        PlaybackEditor(settings.common) { c -> onChange { it.copy(common = c) } }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun TimelineEditor(t: TimelineSettings, onChange: (TimelineSettings) -> Unit) {
    SectionTitle(stringResource(R.string.settings_preset))
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
    SectionTitle(stringResource(R.string.settings_rules))
    Stepper(stringResource(R.string.settings_starting_cards), t.startingCards, 1..3, { onChange(t.copy(startingCards = it, cardsToWin = maxOf(t.cardsToWin, it + 1))) })
    Stepper(stringResource(R.string.settings_cards_to_win), t.cardsToWin, maxOf(3, t.startingCards + 1)..30, { onChange(t.copy(cardsToWin = it)) }, hint = stringResource(R.string.settings_cards_to_win_hint))
    Stepper(stringResource(R.string.settings_starting_vetoes), t.startingVetoes, 0..10, { onChange(t.copy(startingVetoes = it, maxVetoes = maxOf(t.maxVetoes, it))) })
    Stepper(stringResource(R.string.settings_max_vetoes), t.maxVetoes, t.startingVetoes..10, { onChange(t.copy(maxVetoes = it).withCostsWithinMax()) })
    Stepper(stringResource(R.string.settings_vetoes_per_song), t.vetoesPerPlayerPerSong, 1..3, { onChange(t.copy(vetoesPerPlayerPerSong = it)) })
    SwitchRow(stringResource(R.string.settings_naming_bonus), t.namingBonus, { onChange(t.copy(namingBonus = it)) }, hint = stringResource(R.string.settings_naming_bonus_hint))
    if (t.namingBonus) {
        // Who may call out song and artist (feedback round 19).
        Text(stringResource(R.string.settings_naming_callers), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        for (callers in NamingCallers.entries) {
            PresetRow(stringResource(callers.label), stringResource(callers.hint), t.namingCallers == callers, enabled = true) {
                onChange(t.copy(namingCallers = callers))
            }
        }
    }
    // Costs in vetoes: the section says so, so the values stay as short as the other steppers' (feedback round 10).
    SectionTitle(stringResource(R.string.settings_veto_costs))
    Text(stringResource(R.string.settings_veto_costs_hint), style = MaterialTheme.typography.bodySmall)
    // Never more than the most vetoes held, or it could never be paid (D-267).
    OptionalStepper(stringResource(R.string.settings_skip_cost), t.skipCost, 1..minOf(3, t.maxVetoes), { onChange(t.copy(skipCost = it)) })
    OptionalStepper(stringResource(R.string.settings_buy_cost), t.buyCost, 1..minOf(5, t.maxVetoes), { onChange(t.copy(buyCost = it)) })
}

/** Timeline presets in the order shown (Settings and New game). */
val TIMELINE_PRESET_ORDER = listOf(TimelinePreset.CLASSIC, TimelinePreset.FIXED_VETOES, TimelinePreset.CUSTOM)

/** The user's saved presets of one mode (feedback round 27), kept in the app settings. */
class CustomPresets(val list: List<CustomPreset>, val save: (CustomPreset) -> Unit, val delete: (CustomPreset) -> Unit)

@Composable
private fun rememberCustomPresets(mode: GameMode): CustomPresets {
    val repo = LocalContext.current.appContainer.settings
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
            IconButton(onClick = { presets.delete(p) }) { Icon(Icons.Default.Delete, stringResource(R.string.preset_delete, p.name)) }
        }
    }
}

/** "Save as preset" with a name, shown while the rules match no preset. */
@Composable
private fun SavePresetButton(onSave: (String) -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { naming = true }) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(R.string.preset_save), modifier = Modifier.padding(start = 8.dp))
    }
    if (!naming) return
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { naming = false },
        title = { Text(stringResource(R.string.preset_save)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_PRESET_NAME) },
                label = { Text(stringResource(R.string.preset_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()); naming = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_PRESET_NAME = 40

/** A saved preset's rules in one line, e.g. "10 cards to win · 2 vetoes at the start, max 5 · Skip for 1". */
@Composable
private fun timelineSummary(t: TimelineSettings): String = listOfNotNull(
    pluralStringResource(R.plurals.summary_cards_to_win, t.cardsToWin, t.cardsToWin),
    pluralStringResource(R.plurals.summary_vetoes, t.startingVetoes, t.startingVetoes, t.maxVetoes),
    if (t.namingBonus) stringResource(R.string.summary_naming) else null,
    t.skipCost?.let { stringResource(R.string.summary_skip_for, it) },
    t.buyCost?.let { stringResource(R.string.summary_buy_for, it) },
).joinToString(" · ")

@Composable
private fun closestSummary(c: ClosestYearSettings): String = listOfNotNull(
    when (c.scoring) {
        ClosestScoring.CLOSEST -> pluralStringResource(R.plurals.summary_cards_to_win, c.winsToWin, c.winsToWin)
        else -> pluralStringResource(R.plurals.summary_perfect_rounds, c.winsToWin, c.winsToWin)
    },
    when (c.scoring) {
        ClosestScoring.CLOSEST -> null
        ClosestScoring.RANK -> stringResource(R.string.summary_scoring_rank)
        ClosestScoring.DISTANCE -> pluralStringResource(R.plurals.summary_scoring_distance, c.distancePoints, c.distancePoints)
    },
    if (c.usesTieBreaker && c.tieBreaker == TieBreaker.FIRST_SCORES) stringResource(R.string.summary_tie_first) else null,
    c.maxSkips?.let { pluralStringResource(R.plurals.summary_skips, c.startingSkips, c.startingSkips, it) }
        ?: pluralStringResource(R.plurals.summary_skips_no_max, c.startingSkips, c.startingSkips),
    if (c.namingEarnsSkip) stringResource(R.string.summary_naming) else null,
    c.buyCost?.let { stringResource(R.string.summary_buy_for, it) },
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
    SectionTitle(stringResource(R.string.settings_preset))
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
    SectionTitle(stringResource(R.string.settings_rules))
    SwitchRow(stringResource(R.string.settings_hidden_guesses), c.hiddenGuesses, { onChange(c.copy(hiddenGuesses = it)) }, hint = stringResource(R.string.settings_hidden_guesses_hint))
    // How a round scores (feedback round 36).
    Text(stringResource(R.string.settings_scoring), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    for (scoring in ClosestScoring.entries) {
        PresetRow(stringResource(scoring.label), stringResource(scoring.hint), c.scoring == scoring, enabled = true) { onChange(c.copy(scoring = scoring)) }
    }
    if (c.scoring == ClosestScoring.DISTANCE) {
        Stepper(stringResource(R.string.settings_distance_points), c.distancePoints, 1..20, { onChange(c.copy(distancePoints = it)) })
    }
    when (c.scoring) {
        ClosestScoring.CLOSEST -> Stepper(stringResource(R.string.settings_wins_to_win), c.winsToWin, 1..30, { onChange(c.copy(winsToWin = it)) })
        // The target in points is this many perfect rounds (feedback round 36).
        ClosestScoring.RANK -> Stepper(
            stringResource(R.string.settings_perfect_rounds), c.winsToWin, 1..30, { onChange(c.copy(winsToWin = it)) },
            hint = stringResource(R.string.settings_perfect_rounds_hint_rank),
        )
        ClosestScoring.DISTANCE -> Stepper(
            stringResource(R.string.settings_perfect_rounds), c.winsToWin, 1..30, { onChange(c.copy(winsToWin = it)) },
            hint = c.target(seats = 2).let { pluralStringResource(R.plurals.settings_perfect_rounds_hint_distance, it, it) },
        )
    }
    Stepper(
        stringResource(R.string.settings_starting_skips),
        c.startingSkips,
        0..5,
        { onChange(c.copy(startingSkips = it, maxSkips = c.maxSkips?.let { max -> maxOf(max, it) })) },
        hint = stringResource(R.string.settings_starting_skips_hint),
    )
    OptionalStepper(stringResource(R.string.settings_max_skips), c.maxSkips, maxOf(1, c.startingSkips)..10, { onChange(c.copy(maxSkips = it).withCostsWithinMax()) })
    SwitchRow(stringResource(R.string.settings_naming_skip), c.namingEarnsSkip, { onChange(c.copy(namingEarnsSkip = it)) }, hint = stringResource(R.string.settings_naming_skip_hint))
    // Costs in skips, like Song Order's veto costs (feedback round 27).
    SectionTitle(stringResource(R.string.settings_skip_costs))
    Text(stringResource(R.string.settings_skip_costs_hint), style = MaterialTheme.typography.bodySmall)
    OptionalStepper(stringResource(R.string.settings_buy_cost), c.buyCost, 1..minOf(5, c.maxSkips ?: 5), { onChange(c.copy(buyCost = it)) })
    if (c.usesTieBreaker) {
        SectionTitle(stringResource(R.string.settings_tie_breaker))
        for (tie in TieBreaker.entries) {
            PresetRow(stringResource(tie.label), stringResource(tie.hint), c.tieBreaker == tie, enabled = true) { onChange(c.copy(tieBreaker = tie)) }
        }
    }
    // Exact guess ends the round, rotation and distinct guesses are always on (as far as the scoring and
    // hidden guesses allow); the lowest guessable year follows the songs (DECISIONS.md D-066).
    Text(stringResource(R.string.settings_closest_fixed_rules), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun PlaybackEditor(c: CommonSettings, includeMusicBrainz: Boolean = true, onChange: (CommonSettings) -> Unit) {
    SectionTitle(stringResource(R.string.settings_playback))
    val seconds: @Composable (Int) -> String = { n -> pluralStringResource(R.plurals.seconds, n, n) }
    SwitchRow(stringResource(R.string.settings_auto_play), c.autoPlay, { onChange(c.copy(autoPlay = it)) })
    Stepper(stringResource(R.string.settings_start_offset), c.startOffsetSeconds, 0..60, { onChange(c.copy(startOffsetSeconds = it)) }, step = 5, format = { seconds(it) })
    OptionalStepper(stringResource(R.string.settings_clip_limit), c.clipLimitSeconds, 10..120, { onChange(c.copy(clipLimitSeconds = it)) }, step = 5, unit = { seconds(it) })
    if (includeMusicBrainz) {
        SwitchRow(stringResource(R.string.settings_musicbrainz), c.musicBrainzLookups, { onChange(c.copy(musicBrainzLookups = it)) }, hint = stringResource(R.string.settings_musicbrainz_hint))
    }
}

val TimelinePreset.label: Int
    get() = when (this) {
        TimelinePreset.FIXED_VETOES -> R.string.preset_fixed
        TimelinePreset.CLASSIC -> R.string.preset_classic
        TimelinePreset.CUSTOM -> R.string.preset_custom
    }

val NamingCallers.label: Int
    get() = when (this) {
        NamingCallers.ACTIVE -> R.string.naming_callers_active
        NamingCallers.VETOERS -> R.string.naming_callers_vetoers
        NamingCallers.ALL -> R.string.naming_callers_all
    }

private val NamingCallers.hint: Int
    get() = when (this) {
        NamingCallers.ACTIVE -> R.string.naming_callers_active_hint
        NamingCallers.VETOERS -> R.string.naming_callers_vetoers_hint
        NamingCallers.ALL -> R.string.naming_callers_all_hint
    }

val ClosestYearPreset.label: Int
    get() = when (this) {
        ClosestYearPreset.CLOSEST -> R.string.preset_closest
        ClosestYearPreset.RANKING -> R.string.preset_ranking
        ClosestYearPreset.DISTANCE -> R.string.preset_distance
        ClosestYearPreset.CUSTOM -> R.string.preset_custom
    }

private val ClosestYearPreset.hint: Int
    get() = when (this) {
        ClosestYearPreset.CLOSEST -> R.string.preset_closest_hint
        ClosestYearPreset.RANKING -> R.string.preset_ranking_hint
        ClosestYearPreset.DISTANCE -> R.string.preset_distance_hint
        ClosestYearPreset.CUSTOM -> R.string.preset_custom_hint
    }

private val ClosestScoring.hint: Int
    get() = when (this) {
        ClosestScoring.CLOSEST -> R.string.scoring_closest_hint
        ClosestScoring.RANK -> R.string.scoring_rank_hint
        ClosestScoring.DISTANCE -> R.string.scoring_distance_hint
    }

private val TieBreaker.hint: Int
    get() = when (this) {
        TieBreaker.BOTH_SCORE -> R.string.tie_both_hint
        TieBreaker.FIRST_SCORES -> R.string.tie_first_hint
    }

private val TimelinePreset.hint: Int
    get() = when (this) {
        TimelinePreset.FIXED_VETOES -> R.string.preset_fixed_hint
        TimelinePreset.CLASSIC -> R.string.preset_classic_hint
        TimelinePreset.CUSTOM -> R.string.preset_custom_hint
    }
