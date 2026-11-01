package com.trackrecord.app.library

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackrecord.app.game.sheetBody
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.library_songs
import com.trackrecord.app.resources.stats_artists
import com.trackrecord.app.resources.stats_busiest
import com.trackrecord.app.resources.stats_busiest_value
import com.trackrecord.app.resources.stats_filtered
import com.trackrecord.app.resources.stats_histogram
import com.trackrecord.app.resources.stats_histogram_description
import com.trackrecord.app.resources.stats_songs
import com.trackrecord.app.resources.stats_title
import com.trackrecord.app.resources.stats_without_year
import com.trackrecord.app.resources.stats_year_range
import com.trackrecord.app.resources.stats_years
import com.trackrecord.app.ui.components.AppSheet
import com.trackrecord.app.ui.components.YearBarChart
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.data.library.LibraryStats
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Numbers for the listed songs (playlist and filters applied): songs, distinct artists, earliest and
 * latest year, and a histogram of songs per year (feedback round 5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryStatsSheet(title: String, filtered: Boolean, stats: LibraryStats, onDismiss: () -> Unit) {
    // An AppSheet like the others, so it never reaches the status bar either (feedback round 50).
    AppSheet(stringResource(Res.string.stats_title, title), onDismiss = onDismiss) {
        Column(
            sheetBody(fixed = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filtered) Text(stringResource(Res.string.stats_filtered), style = MaterialTheme.typography.bodySmall)
            StatsContent(stats)
        }
    }
}

/** Songs, artists, years and the songs-per-year chart; also under the Song Pool step (feedback round 28). */
@Composable
fun StatsContent(stats: LibraryStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatLine(stringResource(Res.string.stats_songs), pluralStringResource(Res.plurals.library_songs, stats.songs, stats.songs))
            StatLine(stringResource(Res.string.stats_artists), stats.artists.toString())
            val minYear = stats.minYear
            val maxYear = stats.maxYear
            if (minYear != null && maxYear != null) {
                StatLine(stringResource(Res.string.stats_years), stringResource(Res.string.stats_year_range, formatYear(minYear), formatYear(maxYear)))
                stats.busiestYear?.let { (year, count) ->
                    StatLine(stringResource(Res.string.stats_busiest), stringResource(Res.string.stats_busiest_value, formatYear(year), pluralStringResource(Res.plurals.library_songs, count, count)))
                }
            }
            if (stats.withYear < stats.songs) {
                val missing = stats.songs - stats.withYear
                Text(pluralStringResource(Res.plurals.stats_without_year, missing, missing), style = MaterialTheme.typography.bodySmall)
            }
            if (stats.perYear.isNotEmpty()) {
                Text(stringResource(Res.string.stats_histogram), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                YearHistogram(stats)
            }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Songs per year from the earliest to the latest; a tapped bar shows its year and count. */
@Composable
private fun YearHistogram(stats: LibraryStats) {
    val bars = stats.perYear
    val top = bars.maxOf { it.second }.coerceAtLeast(1)
    val songs = bars.map { (_, count) -> pluralStringResource(Res.plurals.library_songs, count, count) }
    YearBarChart(
        years = bars.map { it.first },
        values = bars.map { it.second.toDouble().takeIf { c -> c > 0 } },
        max = top.toDouble(),
        topLabel = top.toString(),
        description = stringResource(Res.string.stats_histogram_description, formatYear(bars.first().first), formatYear(bars.last().first), top),
        detail = { i -> "${formatYear(bars[i].first)}: ${songs[i]}" },
    )
}
