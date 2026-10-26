package com.trackrecord.app.newgame

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R
import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.game.Initials
import com.trackrecord.app.game.PlayerColors
import com.trackrecord.app.ui.components.ColorPicker
import com.trackrecord.app.ui.components.TextButton

/** Add or edit a player for this game: name, initials (generated, editable, 1–3) and a color unique in the game. */
@Composable
fun PlayerEditorDialog(
    player: GamePlayer?,
    takenColors: Set<Int>,
    onDismiss: () -> Unit,
    onSave: (name: String, initials: String, colorIndex: Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(player?.name.orEmpty()) }
    var initials by rememberSaveable { mutableStateOf(player?.initials.orEmpty()) }
    var initialsEdited by rememberSaveable { mutableStateOf(player != null) }
    val firstFree = PlayerColors.colors.indices.firstOrNull { it !in takenColors } ?: 0
    var color by rememberSaveable { mutableIntStateOf(player?.colorIndex?.takeIf { it !in takenColors } ?: firstFree) }
    val valid = name.isNotBlank() && initials.trim().length in 1..3 && color !in takenColors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (player == null) R.string.player_add_title else R.string.player_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NameAndInitialsFields(
                    name = name,
                    initials = initials,
                    onName = {
                        name = it
                        if (!initialsEdited) initials = Initials.from(it)
                    },
                    onInitials = { initials = it; initialsEdited = true },
                )
                Text(stringResource(R.string.player_color), style = MaterialTheme.typography.labelLarge)
                ColorPicker(selected = color, taken = takenColors, onSelect = { color = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, initials, color) }, enabled = valid) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun NameAndInitialsFields(name: String, initials: String, onName: (String) -> Unit, onInitials: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.player_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )
    OutlinedTextField(
        value = initials,
        onValueChange = { if (it.length <= 3) onInitials(it.uppercase()) },
        label = { Text(stringResource(R.string.player_initials)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
    )
}
