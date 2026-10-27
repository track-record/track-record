package com.trackrecord.app.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.Card
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.data.db.PlaylistWithCount
import com.trackrecord.data.deezer.DeezerImportException
import com.trackrecord.data.library.LibraryQuery
import com.trackrecord.data.library.SongStatus
import com.trackrecord.data.library.StatusCategory
import com.trackrecord.data.playlists.BuiltInPlaylists
import com.trackrecord.engine.year.MIN_PLAYABLE_YEAR

/** Playlists: the built-in "All" plus the user's own. Tapping one filters the list to it. */
@Composable
fun PlaylistsCard(
    playlists: List<PlaylistWithCount>,
    builtIns: List<PlaylistWithCount>,
    totalSongs: Int,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    deezer: DeezerImportState = DeezerImportState.Idle,
    onAddDeezer: (String) -> Unit = {},
    onUpdateDeezer: (Long) -> Unit = {},
    onDismissDeezer: () -> Unit = {},
    onCancelDeezer: () -> Unit = {},
    onImportList: () -> Unit = {},
    /** Opens Exportify in a Custom Tab of the default browser. */
    onExportify: () -> Unit = {},
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var addingDeezer by rememberSaveable { mutableStateOf(false) }
    var choosingList by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistWithCount?>(null) }
    var deleting by remember { mutableStateOf<PlaylistWithCount?>(null) }
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.playlists_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            ListItem(
                modifier = Modifier.clickable { onSelect(null) },
                headlineContent = { Text(stringResource(R.string.playlist_all)) },
                supportingContent = { Text(pluralStringResource(R.plurals.library_songs, totalSongs, totalSongs)) },
                trailingContent = { if (selected == null) Text(stringResource(R.string.playlist_showing), color = MaterialTheme.colorScheme.primary) },
            )
            for (p in builtIns) {
                ListItem(
                    modifier = Modifier.clickable { onSelect(p.id) },
                    headlineContent = { Text(playlistName(p)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.library_songs, p.songs, p.songs)) },
                    trailingContent = { if (selected == p.id) Text(stringResource(R.string.playlist_showing), color = MaterialTheme.colorScheme.primary) },
                )
            }
            for (p in playlists) {
                ListItem(
                    modifier = Modifier.clickable { onSelect(p.id) },
                    headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val count = pluralStringResource(R.plurals.library_songs, p.songs, p.songs)
                        Text(if (p.deezerPlaylistId != null) stringResource(R.string.playlist_from_deezer, count) else count)
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected == p.id) Text(stringResource(R.string.playlist_showing), color = MaterialTheme.colorScheme.primary)
                            if (p.deezerPlaylistId != null) {
                                IconButton(onClick = { onUpdateDeezer(p.id) }, enabled = deezer !is DeezerImportState.Running) {
                                    Icon(Icons.Default.Refresh, stringResource(R.string.deezer_update_named, p.name))
                                }
                            }
                            IconButton(onClick = { renaming = p }) { Icon(Icons.Default.Edit, stringResource(R.string.playlist_rename_named, p.name)) }
                            IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, stringResource(R.string.playlist_delete_named, p.name)) }
                        }
                    },
                )
            }
            // Three equal actions, one per row (feedback round 5: the Deezer button looked bigger).
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlaylistAction(Icons.Default.Add, stringResource(R.string.playlist_new), enabled = true) { creating = true }
                PlaylistAction(Icons.Default.Add, stringResource(R.string.deezer_add), enabled = deezer !is DeezerImportState.Running) { addingDeezer = true }
                PlaylistAction(Icons.Default.Add, stringResource(R.string.list_import), enabled = deezer !is DeezerImportState.Running) { choosingList = true }
            }
            Text(stringResource(R.string.list_import_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            DeezerStatus(deezer, onDismissDeezer, onCancelDeezer)
            Text(stringResource(R.string.playlists_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
    if (creating) PlaylistNameDialog(null, onDismiss = { creating = false }) { onCreate(it); creating = false }
    if (addingDeezer) DeezerLinkDialog(onDismiss = { addingDeezer = false }) { onAddDeezer(it); addingDeezer = false }
    if (choosingList) {
        // Spotify playlists come in through Exportify, opened inside the app (D-090); any other list from a file.
        AlertDialog(
            onDismissRequest = { choosingList = false },
            title = { Text(stringResource(R.string.list_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.list_import_choose), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { choosingList = false; onExportify() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.list_from_spotify)) }
                    OutlinedButton(onClick = { choosingList = false; onImportList() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.list_from_file)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingList = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    renaming?.let { p -> PlaylistNameDialog(p.name, onDismiss = { renaming = null }) { onRename(p.id, it); renaming = null } }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.playlist_delete_title, p.name)) },
            text = { Text(stringResource(if (p.streamed > 0) R.string.playlist_delete_text_deezer else R.string.playlist_delete_text)) },
            confirmButton = { TextButton(onClick = { onDelete(p.id); deleting = null }) { Text(stringResource(R.string.playlist_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PlaylistAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null)
        Text(label, modifier = Modifier.padding(start = 8.dp).weight(1f))
    }
}

/** Progress and outcome of a Deezer import or update. */
@Composable
private fun DeezerStatus(state: DeezerImportState, onDismiss: () -> Unit, onCancel: () -> Unit) {
    val modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    when (state) {
        DeezerImportState.Idle -> Unit
        is DeezerImportState.Running -> Column(modifier) {
            Text(if (state.total == 0) stringResource(R.string.deezer_reading) else stringResource(R.string.deezer_progress, state.done, state.total))
            LinearProgressIndicator(
                progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                drawStopIndicator = {},
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
        is DeezerImportState.Done -> Column(modifier) {
            val r = state.result
            val songs = pluralStringResource(R.plurals.library_songs, r.total - r.unavailable, r.total - r.unavailable)
            Text(stringResource(if (state.update) R.string.deezer_updated else R.string.deezer_done, r.name, songs, r.added, r.local))
            var showing by rememberSaveable { mutableStateOf(false) }
            if (r.notFound.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.list_not_found, r.notFound.size, r.notFound.size), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showing = true }) { Text(stringResource(R.string.list_show_not_found)) }
            } else if (r.unavailable > 0) {
                Text(pluralStringResource(R.plurals.deezer_unavailable_songs, r.unavailable, r.unavailable), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            if (showing) {
                AlertDialog(
                    onDismissRequest = { showing = false },
                    title = { Text(stringResource(R.string.list_not_found_title)) },
                    text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { items(r.notFound) { Text(it, modifier = Modifier.padding(vertical = 4.dp)) } } },
                    confirmButton = { TextButton(onClick = { showing = false }) { Text(stringResource(R.string.action_ok)) } },
                )
            }
        }
        is DeezerImportState.DoneMany -> Column(modifier) {
            val songs = state.results.sumOf { it.total - it.unavailable }
            Text(
                stringResource(
                    R.string.list_done_many,
                    pluralStringResource(R.plurals.playlists_count, state.results.size, state.results.size),
                    pluralStringResource(R.plurals.library_songs, songs, songs),
                    state.results.sumOf { it.added },
                    state.results.sumOf { it.local },
                ),
            )
            val notFound = state.results.flatMap { r -> r.notFound.map { "${r.name}: $it" } }
            var showing by rememberSaveable { mutableStateOf(false) }
            if (notFound.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.list_not_found, notFound.size, notFound.size), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showing = true }) { Text(stringResource(R.string.list_show_not_found)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            if (showing) {
                AlertDialog(
                    onDismissRequest = { showing = false },
                    title = { Text(stringResource(R.string.list_not_found_title)) },
                    text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { items(notFound) { Text(it, modifier = Modifier.padding(vertical = 4.dp)) } } },
                    confirmButton = { TextButton(onClick = { showing = false }) { Text(stringResource(R.string.action_ok)) } },
                )
            }
        }
        is DeezerImportState.Failed -> Column(modifier) {
            Text(
                stringResource(
                    when (state.reason) {
                        DeezerImportException.Reason.INVALID_LINK -> R.string.deezer_failed_link
                        DeezerImportException.Reason.NOT_FOUND -> R.string.deezer_failed_not_found
                        DeezerImportException.Reason.UNAVAILABLE -> R.string.deezer_failed_unavailable
                        DeezerImportException.Reason.EMPTY_LIST -> R.string.list_failed_empty
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        }
    }
}

@Composable
private fun DeezerLinkDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deezer_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.deezer_add_text), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(stringResource(R.string.deezer_link)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(link.trim()) }, enabled = link.isNotBlank()) { Text(stringResource(R.string.deezer_import)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun PlaylistNameDialog(initial: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.playlist_new else R.string.playlist_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.playlist_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Choose a playlist to add the selection to (or a new one), or to remove it from. With [excluded] (the
 * button's label), "Excluded songs" comes first as a red-outlined button; the playlists follow under
 * [playlistsLabel] (feedback rounds 28, 30, 31).
 */
@Composable
fun PlaylistPickerDialog(
    title: String,
    playlists: List<PlaylistWithCount>,
    allowNew: Boolean,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onNew: (String) -> Unit = {},
    excluded: String? = null,
    playlistsLabel: String? = null,
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    if (naming) {
        PlaylistNameDialog(null, onDismiss = { naming = false }) { onNew(it); naming = false }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                if (excluded != null) {
                    item(key = "excluded") {
                        val red = MaterialTheme.colorScheme.error
                        OutlinedButton(
                            onClick = { onPick(BuiltInPlaylists.EXCLUDED) },
                            border = BorderStroke(1.dp, red),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = red),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(painterResource(R.drawable.ic_block), contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(excluded, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (playlists.isNotEmpty() && playlistsLabel != null) {
                    item(key = "label") {
                        Text(playlistsLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = if (excluded != null) 16.dp else 0.dp, bottom = 4.dp))
                    }
                }
                if (playlists.isEmpty() && excluded == null) item { Text(stringResource(R.string.playlist_none_yet)) }
                items(playlists, key = { it.id }) { p ->
                    Text(
                        p.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            if (allowNew) TextButton(onClick = { naming = true }) { Text(stringResource(R.string.playlist_new)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Collapsible filter pane: song status, year range and playlist. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterPane(
    query: LibraryQuery,
    playlists: List<PlaylistWithCount>,
    builtIns: List<PlaylistWithCount> = emptyList(),
    onChange: ((LibraryQuery) -> LibraryQuery) -> Unit,
    onReset: () -> Unit,
    onStats: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.titleMedium)
                val active = query.activeFilters
                Text(
                    if (active == 0) stringResource(R.string.filters_none) else pluralStringResource(R.plurals.filters_active, active, active),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Stats for what the filters currently list (feedback round 5: "besides the filters").
            TextButton(onClick = onStats) { Text(stringResource(R.string.stats_button)) }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.filters_collapse else R.string.filters_expand),
            )
        }
        if (!expanded) return@Card
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.filters_status), style = MaterialTheme.typography.labelLarge)
            FilterChip(
                selected = query.status == SongStatus.ALL,
                onClick = { onChange { it.copy(status = SongStatus.ALL) } },
                label = { Text(stringResource(SongStatus.ALL.label)) },
            )
            // One state at a time, grouped by what it says about the song (feedback round 5).
            for (category in StatusCategory.entries) {
                Text(stringResource(category.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (status in SongStatus.entries.filter { it.category == category }) {
                        FilterChip(
                            selected = query.status == status,
                            onClick = { onChange { it.copy(status = status) } },
                            label = { Text(stringResource(status.label)) },
                        )
                    }
                }
            }
            Text(stringResource(R.string.filters_years), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterYearField(stringResource(R.string.pool_year_from), query.yearFrom, Modifier.weight(1f)) { y -> onChange { it.copy(yearFrom = y) } }
                FilterYearField(stringResource(R.string.pool_year_to), query.yearTo, Modifier.weight(1f)) { y -> onChange { it.copy(yearTo = y) } }
            }
            Text(stringResource(R.string.filters_playlist), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = query.playlistId == null, onClick = { onChange { it.copy(playlistId = null) } }, label = { Text(stringResource(R.string.playlist_all)) })
                for (p in builtIns) {
                    FilterChip(selected = query.playlistId == p.id, onClick = { onChange { it.copy(playlistId = p.id) } }, label = { Text(playlistName(p)) })
                }
                for (p in playlists) {
                    FilterChip(selected = query.playlistId == p.id, onClick = { onChange { it.copy(playlistId = p.id) } }, label = { Text(p.name) })
                }
            }
            TextButton(onClick = onReset, enabled = query.activeFilters > 0) { Text(stringResource(R.string.filters_reset)) }
        }
    }
}

@Composable
private fun FilterYearField(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    // Local text so typing partial years isn't overwritten; only complete years (or empty) are applied.
    // Re-keyed when the value is cleared (e.g. "Reset filters"), so the field empties too.
    var text by rememberSaveable(value == null) { mutableStateOf(value?.let(::formatYear).orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            if (v.length <= 4 && v.all(Char::isDigit)) {
                text = v
                val year = v.toIntOrNull()
                if (v.isEmpty()) onChange(null) else if (v.length == 4 && year != null && year >= MIN_PLAYABLE_YEAR) onChange(year)
            }
        },
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.pool_year_any)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

val SongStatus.label: Int
    get() = when (this) {
        SongStatus.ALL -> R.string.status_all
        SongStatus.PLAYABLE -> R.string.status_playable
        SongStatus.MISSING_YEAR -> R.string.status_missing_year
        SongStatus.EDITED -> R.string.status_edited
        SongStatus.EXCLUDED -> R.string.status_excluded
        SongStatus.SOURCES_DISAGREE -> R.string.status_sources_disagree
        SongStatus.NOT_CHECKED -> R.string.status_not_checked
        SongStatus.VERIFIED_BY_NAME -> R.string.status_verified_name
        SongStatus.VERIFIED_BY_FINGERPRINT -> R.string.status_verified_fingerprint
        SongStatus.NO_MATCH -> R.string.status_no_match
        SongStatus.FOLDER_UNAVAILABLE -> R.string.status_folder_unavailable
        SongStatus.UNREADABLE -> R.string.status_unreadable
    }

private val StatusCategory.label: Int
    get() = when (this) {
        StatusCategory.PLAYABILITY -> R.string.status_group_playability
        StatusCategory.DATA_QUALITY -> R.string.status_group_data_quality
        StatusCategory.VERIFICATION -> R.string.status_group_verification
    }
