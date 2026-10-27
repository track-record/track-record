package com.trackrecord.app.library

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R
import com.trackrecord.app.game.SectionCard
import com.trackrecord.app.game.sheetBody
import com.trackrecord.app.ui.components.AppSheet
import com.trackrecord.app.ui.components.Button

/**
 * The library's info button (feedback round 45). [highlight] after the first visit's explanation: a
 * pulsing circle in the primary colour shows where to find it again.
 */
@Composable
fun LibraryInfoButton(highlight: Boolean, onClick: () -> Unit) {
    val glow = if (highlight) {
        val pulse by rememberInfiniteTransition(label = "info").animateFloat(
            initialValue = 0.15f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
            label = "pulse",
        )
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = pulse))
    } else {
        Modifier
    }
    IconButton(onClick = onClick, modifier = glow) {
        Icon(
            Icons.Outlined.Info,
            stringResource(R.string.library_info_title),
            tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val PULSE_MS = 900

/** How songs get into the library: own files, Deezer and Spotify, years and verification, playlists (feedback round 45). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryInfoSheet(onDismiss: () -> Unit, defaultsMissing: Boolean = true, importing: Boolean = false, onGetDefaults: () -> Unit = {}) {
    val sections = listOf(
        R.string.library_info_files to listOf(R.string.library_info_files_1, R.string.library_info_files_2),
        R.string.library_info_streamed to listOf(R.string.library_info_streamed_1, R.string.library_info_streamed_2, R.string.library_info_streamed_3),
        R.string.library_info_years to listOf(R.string.library_info_years_1, R.string.library_info_years_2, R.string.library_info_years_3),
        R.string.library_info_playlists to listOf(R.string.library_info_playlists_1, R.string.library_info_playlists_2),
    )
    // Fully open at a fixed height, like the game's info sheet (feedback rounds 37 to 39).
    AppSheet(stringResource(R.string.library_info_title), onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            sheetBody().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for ((title, paragraphs) in sections) {
                SectionCard(stringResource(title)) {
                    paragraphs.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    // One playlist of hits per decade from Deezer, for a library to start with (feedback round 48).
                    if (title == R.string.library_info_playlists) {
                        Button(onClick = onGetDefaults, enabled = defaultsMissing && !importing, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (defaultsMissing) R.string.library_get_defaults else R.string.library_defaults_added))
                        }
                    }
                }
            }
        }
    }
}
