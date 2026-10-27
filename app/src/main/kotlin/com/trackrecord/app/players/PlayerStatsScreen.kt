package com.trackrecord.app.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.game.SectionCard
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.YearBarChart
import com.trackrecord.app.ui.components.formatDecade
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.data.stats.PlayerStats
import com.trackrecord.data.stats.YearBar
import com.trackrecord.engine.GameMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil

/**
 * A saved player's stats (feedback round 47): games, wins and rounds per mode, how far their Timeline guesses
 * were off, and how often they named the song when they had the chance; both with a bar per song year.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerStatsScreen(profileId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val container = LocalContext.current.appContainer
    val profiles by container.profiles.profiles.collectAsStateWithLifecycle(initialValue = null)
    val profile = profiles?.firstOrNull { it.id == profileId }
    // Deleted in the editor: back to the list.
    LaunchedEffect(profiles, profile) { if (profiles != null && profile == null) onBack() }
    val stats by remember(profileId) { container.playerStats.stats(profileId) }.collectAsStateWithLifecycle(initialValue = null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.player_stats)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.profiles_edit)) } },
            )
        },
    ) { padding ->
        val s = stats ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            profile?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlayerAvatar(p.asGamePlayer(), 56.dp)
                    Text(p.name, style = MaterialTheme.typography.headlineSmall)
                }
            }
            GamesCard(s)
            AccuracyCard(s)
            NamingCard(s)
        }
    }
}

@Composable
private fun GamesCard(s: PlayerStats) {
    // Games and rounds side by side, each with Played and Won (feedback round 48: "Rounds won" wrapped).
    SectionCard(stringResource(R.string.player_stats_games_rounds)) {
        // One block without gaps, so the line between the groups runs through.
        Column {
            val groupStyle = MaterialTheme.typography.titleSmall
            TableRow("", listOf(stringResource(R.string.player_stats_games)), listOf(stringResource(R.string.player_stats_rounds)), groupStyle)
            val played = stringResource(R.string.player_stats_played)
            val won = stringResource(R.string.player_stats_won)
            TableRow(stringResource(R.string.player_stats_mode), listOf(played, won), listOf(played, won), MaterialTheme.typography.labelMedium)
            for ((mode, label) in listOf(GameMode.TIMELINE to R.string.mode_timeline, GameMode.CLOSEST_YEAR to R.string.mode_closest)) {
                val m = s.modes.getValue(mode)
                TableRow(stringResource(label), listOf(m.games, m.wins).map { it.toString() }, listOf(m.rounds, m.roundsWon).map { it.toString() }, MaterialTheme.typography.bodyLarge)
            }
        }
        Text(stringResource(R.string.player_stats_rounds_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** A row of the table: the label, then the games' cells and the rounds' cells, centred, with a line between them. */
@Composable
private fun TableRow(label: String, games: List<String>, rounds: List<String>, style: TextStyle) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(LABEL_WEIGHT).padding(vertical = 4.dp), style = style)
        Cells(games, style)
        VerticalDivider(Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Cells(rounds, style)
    }
}

/** A group's cells, two columns wide together: one cell spans both (the group's name). */
@Composable
private fun RowScope.Cells(values: List<String>, style: TextStyle) {
    for (value in values) {
        Text(value, Modifier.weight(2f / values.size).padding(vertical = 4.dp), style = style, textAlign = TextAlign.Center)
    }
}

private const val LABEL_WEIGHT = 1.6f

@Composable
private fun AccuracyCard(s: PlayerStats) {
    SectionCard(stringResource(R.string.player_stats_accuracy)) {
        val mean = s.meanYearsOff
        if (mean == null) {
            Text(stringResource(R.string.player_stats_accuracy_none), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Hero(stringResource(R.string.player_stats_years_off, oneDecimal(mean)))
        Text(stringResource(R.string.player_stats_accuracy_hint, pluralStringResource(R.plurals.player_stats_guesses, s.guesses, s.guesses)), style = MaterialTheme.typography.bodySmall)
        Breakdown(s.yearsOffByDecade, s.yearsOffByYear) { bars, decades ->
            val label = barLabel(decades)
            val guesses = bars.map { pluralStringResource(R.plurals.player_stats_guesses, it.count, it.count) }
            // The scale ends at a whole year, so the label above the chart is a round number.
            val top = ceil(bars.maxOf { it.value ?: 0.0 }).coerceAtLeast(1.0)
            Chart(
                bars = bars,
                max = top,
                topLabel = stringResource(R.string.player_stats_accuracy_scale, top.toInt().toString()),
                description = stringResource(R.string.player_stats_accuracy_description, label(bars.first().year), label(bars.last().year)),
                decades = decades,
            ) { i -> bars[i].let { b -> stringResource(R.string.player_stats_accuracy_detail, label(b.year), oneDecimal(b.value ?: 0.0), guesses[i]) } }
        }
    }
}

@Composable
private fun NamingCard(s: PlayerStats) {
    SectionCard(stringResource(R.string.player_stats_naming)) {
        val rate = s.namingRate
        if (rate == null) {
            Text(stringResource(R.string.player_stats_naming_none), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Hero(percent(rate))
        Text(stringResource(R.string.player_stats_naming_hint, s.named, pluralStringResource(R.plurals.player_stats_chances, s.namingChances, s.namingChances)), style = MaterialTheme.typography.bodySmall)
        Breakdown(s.namingByDecade, s.namingByYear) { bars, decades ->
            val label = barLabel(decades)
            val chances = bars.map { pluralStringResource(R.plurals.player_stats_chances, it.count, it.count) }
            Chart(
                bars = bars,
                max = 1.0,
                topLabel = percent(1.0),
                description = stringResource(R.string.player_stats_naming_description, label(bars.first().year), label(bars.last().year)),
                decades = decades,
            ) { i ->
                bars[i].let { b ->
                    val named = Math.round((b.value ?: 0.0) * b.count).toInt()
                    stringResource(R.string.player_stats_naming_detail, label(b.year), percent(b.value ?: 0.0), named, chances[i])
                }
            }
        }
    }
}

/**
 * The breakdown below a headline number: a bar per decade, or per year when chosen (feedback round 48). [chart]
 * gets the bars and whether they are decades.
 */
@Composable
private fun Breakdown(byDecade: List<YearBar>, byYear: List<YearBar>, chart: @Composable (List<YearBar>, Boolean) -> Unit) {
    var years by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (years) R.string.player_stats_by_year else R.string.player_stats_by_decade), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        FilterChip(selected = !years, onClick = { years = false }, label = { Text(stringResource(R.string.player_stats_decades)) })
        FilterChip(selected = years, onClick = { years = true }, label = { Text(stringResource(R.string.player_stats_years)) })
    }
    if (years) chart(byYear, false) else chart(byDecade, true)
}

private fun barLabel(decades: Boolean): (Int) -> String = if (decades) ::formatDecade else ::formatYear

@Composable
private fun Chart(bars: List<YearBar>, max: Double, topLabel: String, description: String, decades: Boolean, detail: @Composable (Int) -> String) {
    val details = bars.indices.map { detail(it) }
    YearBarChart(bars.map { it.year }, bars.map { it.value }, max, topLabel, description, decades) { details[it] }
}

@Composable
private fun Hero(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
}

private fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

private fun percent(value: Double): String = NumberFormat.getPercentInstance().format(value)
