package com.trackrecord.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R
import com.trackrecord.app.game.PlayerColors

/** The 12-color palette; [taken] colors are disabled (colors are unique within a game). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(selected: Int, taken: Set<Int>, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerColors.colors.indices.forEach { index ->
            val isTaken = index in taken
            val colorName = stringResource(PlayerColors.names[index])
            val description = if (isTaken) stringResource(R.string.player_color_taken, colorName) else colorName
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PlayerColors.color(index))
                    .border(if (index == selected) 4.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable(enabled = !isTaken, role = Role.RadioButton) { onSelect(index) }
                    .semantics { contentDescription = description; this.selected = index == selected },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    index == selected -> Icon(Icons.Default.Check, contentDescription = null, tint = PlayerColors.onColor(index))
                    isTaken -> Icon(Icons.Default.Clear, contentDescription = null, tint = PlayerColors.onColor(index))
                }
            }
        }
    }
}
