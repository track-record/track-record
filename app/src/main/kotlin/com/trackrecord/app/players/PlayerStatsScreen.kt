package com.trackrecord.app.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.game.SectionCard
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.YearBarChart
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.data.stats.PlayerStats
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
    SectionCard(stringResource(R.string.player_stats_games)) {
        TableRow(stringResource(R.string.player_stats_mode), stringResource(R.string.player_stats_played), stringResource(R.string.player_stats_won), stringResource(R.string.player_stats_rounds), header = true)
        for ((mode, label) in listOf(GameMode.TIMELINE to R.string.mode_timeline, GameMode.CLOSEST_YEAR to R.string.mode_closest)) {
            val m = s.modes.getValue(mode)
            TableRow(stringResource(label), m.games.toString(), m.wins.toString(), m.rounds.toString())
        }
        Text(stringResource(R.string.player_stats_rounds_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TableRow(label: String, games: String, wins: String, rounds: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.6f), style = style)
        for (value in listOf(games, wins, rounds)) Text(value, Modifier.weight(1f), style = style, textAlign = TextAlign.End)
    }
}

@Composable
private fun AccuracyCard(s: PlayerStats) {
    SectionCard(stringResource(R.string.player_stats_accuracy)) {
        val mean = s.meanYearsOff
        if (mean == null) {
            Text(stringResource(R.string.player_stats_accuracy_none), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val guesses = s.yearsOffByYear.map { pluralStringResource(R.plurals.player_stats_guesses, it.count, it.count) }
        Hero(stringResource(R.string.player_stats_years_off, oneDecimal(mean)))
        Text(stringResource(R.string.player_stats_accuracy_hint, pluralStringResource(R.plurals.player_stats_guesses, s.guesses, s.guesses)), style = MaterialTheme.typography.bodySmall)
        val bars = s.yearsOffByYear
        // The scale ends at a whole year, so the label above the chart is a round number.
        val top = ceil(bars.maxOf { it.value ?: 0.0 }).coerceAtLeast(1.0)
        ByYear(
            years = bars.map { it.year },
            values = bars.map { it.value },
            max = top,
            topLabel = stringResource(R.string.player_stats_accuracy_scale, top.toInt().toString()),
            description = stringResource(R.string.player_stats_accuracy_description, formatYear(bars.first().year), formatYear(bars.last().year)),
            detail = { i -> bars[i].let { b -> stringResource(R.string.player_stats_accuracy_detail, formatYear(b.year), oneDecimal(b.value ?: 0.0), guesses[i]) } },
        )
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
        val bars = s.namingByYear
        val chances = bars.map { pluralStringResource(R.plurals.player_stats_chances, it.count, it.count) }
        Hero(percent(rate))
        Text(stringResource(R.string.player_stats_naming_hint, s.named, pluralStringResource(R.plurals.player_stats_chances, s.namingChances, s.namingChances)), style = MaterialTheme.typography.bodySmall)
        ByYear(
            years = bars.map { it.year },
            values = bars.map { it.value },
            max = 1.0,
            topLabel = percent(1.0),
            description = stringResource(R.string.player_stats_naming_description, formatYear(bars.first().year), formatYear(bars.last().year)),
            detail = { i ->
                bars[i].let { b ->
                    val named = Math.round((b.value ?: 0.0) * b.count).toInt()
                    stringResource(R.string.player_stats_naming_detail, formatYear(b.year), percent(b.value ?: 0.0), named, chances[i])
                }
            },
        )
    }
}

@Composable
private fun Hero(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
}

/** The breakdown below a headline number. */
@Composable
private fun ByYear(years: List<Int>, values: List<Double?>, max: Double, topLabel: String, description: String, detail: @Composable (Int) -> String) {
    Text(stringResource(R.string.player_stats_by_year), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    val details = years.indices.map { detail(it) }
    YearBarChart(years, values, max, topLabel, description) { details[it] }
}

private fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

private fun percent(value: Double): String = NumberFormat.getPercentInstance().format(value)
