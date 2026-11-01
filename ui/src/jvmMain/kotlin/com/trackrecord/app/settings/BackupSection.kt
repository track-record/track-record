package com.trackrecord.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.platform.LocalPlatformUi
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_cancel
import com.trackrecord.app.resources.action_ok
import com.trackrecord.app.resources.backup_exported
import com.trackrecord.app.resources.backup_exporting
import com.trackrecord.app.resources.backup_failed_export
import com.trackrecord.app.resources.backup_failed_import
import com.trackrecord.app.resources.backup_failed_unreadable
import com.trackrecord.app.resources.backup_import_action
import com.trackrecord.app.resources.backup_import_settings
import com.trackrecord.app.resources.backup_import_text
import com.trackrecord.app.resources.backup_import_title
import com.trackrecord.app.resources.backup_imported
import com.trackrecord.app.resources.backup_importing
import com.trackrecord.app.resources.backup_missing
import com.trackrecord.app.resources.backup_nothing_new
import com.trackrecord.app.resources.backup_players
import com.trackrecord.app.resources.backup_playlists
import com.trackrecord.app.resources.backup_settings_taken
import com.trackrecord.app.resources.backup_streamed
import com.trackrecord.app.resources.backup_summary
import com.trackrecord.app.resources.backup_years
import com.trackrecord.app.resources.list_separator
import com.trackrecord.app.resources.settings_backup
import com.trackrecord.app.resources.settings_backup_export
import com.trackrecord.app.resources.settings_backup_hint
import com.trackrecord.app.resources.settings_backup_import
import com.trackrecord.app.ui.components.FilledTonalButton
import com.trackrecord.app.ui.components.SectionCard
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.data.backup.ImportSummary
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Backup and move (PLATFORMS.md section 5): the library in a file, to keep or to open on another phone or a PC,
 * and that file read back in.
 */
@Composable
fun BackupSection() {
    val transfer = LocalAppContainer.current.libraryTransfer
    val state by transfer.state.collectAsStateWithLifecycle()
    val ui = LocalPlatformUi.current
    val save = ui.rememberFileSaver(transfer::export)
    val open = ui.rememberFileOpener(transfer::offer)
    SectionCard(stringResource(Res.string.settings_backup), spacing = 4.dp) {
        Text(stringResource(Res.string.settings_backup_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        // Filled, so they read on the theme's scene (feedback round 55).
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val idle = state !is TransferState.Running
            FilledTonalButton(onClick = { save(transfer.suggestedName()) }, enabled = idle, modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.settings_backup_export))
            }
            FilledTonalButton(onClick = { open() }, enabled = idle, modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.settings_backup_import))
            }
        }
        TransferDialog(state, transfer)
    }
}

@Composable
private fun TransferDialog(state: TransferState, transfer: LibraryTransfer) {
    when (state) {
        TransferState.Idle -> Unit
        is TransferState.Offered -> {
            var withSettings by rememberSaveable(state.file) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = transfer::dismiss,
                title = { Text(stringResource(Res.string.backup_import_title)) },
                text = {
                    Column {
                        Text(state.name, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(Res.string.backup_import_text), modifier = Modifier.padding(top = 8.dp))
                        if (state.hasSettings) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(withSettings, role = Role.Checkbox) { withSettings = it },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = withSettings, onCheckedChange = null)
                                Text(stringResource(Res.string.backup_import_settings), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { transfer.import(state.file, withSettings) }) { Text(stringResource(Res.string.backup_import_action)) } },
                dismissButton = { TextButton(onClick = transfer::dismiss) { Text(stringResource(Res.string.action_cancel)) } },
            )
        }
        is TransferState.Running -> AlertDialog(
            // Only Cancel ends it: a tap beside it must not leave an import running unseen.
            onDismissRequest = {},
            title = { Text(stringResource(if (state.export) Res.string.backup_exporting else Res.string.backup_importing)) },
            text = {
                if (state.total > 0) {
                    LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = transfer::cancel) { Text(stringResource(Res.string.action_cancel)) } },
        )
        TransferState.Exported -> Result(stringResource(Res.string.backup_exported), transfer)
        is TransferState.Imported -> Result(summaryText(state.summary), transfer, title = stringResource(Res.string.backup_imported))
        is TransferState.Failed -> Result(
            stringResource(
                when {
                    state.export -> Res.string.backup_failed_export
                    state.unreadable -> Res.string.backup_failed_unreadable
                    else -> Res.string.backup_failed_import
                },
            ),
            transfer,
        )
    }
}

@Composable
private fun Result(text: String, transfer: LibraryTransfer, title: String? = null) = AlertDialog(
    onDismissRequest = transfer::dismiss,
    title = title?.let { { Text(it) } },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = transfer::dismiss) { Text(stringResource(Res.string.action_ok)) } },
)

/** "Imported 3 playlists, 812 streamed songs, 4 players. 23 local songs aren't on this device yet. …" */
@Composable
private fun summaryText(s: ImportSummary): String {
    val parts = listOfNotNull(
        s.playlists.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.backup_playlists, it, it) },
        s.streamedSongs.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.backup_streamed, it, it) },
        s.yearCorrections.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.backup_years, it, it) },
        s.players.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.backup_players, it, it) },
    )
    val main = if (parts.isEmpty()) stringResource(Res.string.backup_nothing_new) else stringResource(Res.string.backup_summary, parts.joinToString(stringResource(Res.string.list_separator)))
    return listOfNotNull(
        main,
        s.localMissing.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.backup_missing, it, it) },
        stringResource(Res.string.backup_settings_taken).takeIf { s.settings },
    ).joinToString(" ")
}
