package com.trackrecord.app.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.contactEmailInUse
import com.trackrecord.app.game.GameRequestsPanel
import com.trackrecord.app.platform.LocalPlatformUi
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_back
import com.trackrecord.app.resources.action_cancel
import com.trackrecord.app.resources.action_remove
import com.trackrecord.app.resources.add_to_playlist
import com.trackrecord.app.resources.covers_done
import com.trackrecord.app.resources.covers_progress
import com.trackrecord.app.resources.covers_start
import com.trackrecord.app.resources.excluded_add
import com.trackrecord.app.resources.excluded_remove
import com.trackrecord.app.resources.exportify_permission_allow
import com.trackrecord.app.resources.exportify_permission_skip
import com.trackrecord.app.resources.exportify_permission_text
import com.trackrecord.app.resources.exportify_permission_title
import com.trackrecord.app.resources.find_missing_no_artist
import com.trackrecord.app.resources.find_missing_progress
import com.trackrecord.app.resources.find_missing_start
import com.trackrecord.app.resources.flag_separator
import com.trackrecord.app.resources.library_add_folder
import com.trackrecord.app.resources.library_counts
import com.trackrecord.app.resources.library_empty
import com.trackrecord.app.resources.library_folder_unavailable
import com.trackrecord.app.resources.library_folders
import com.trackrecord.app.resources.library_info_hint
import com.trackrecord.app.resources.library_no_folders
import com.trackrecord.app.resources.library_no_matches
import com.trackrecord.app.resources.library_pick_again
import com.trackrecord.app.resources.library_remove_folder
import com.trackrecord.app.resources.library_remove_folder_text
import com.trackrecord.app.resources.library_remove_folder_title
import com.trackrecord.app.resources.library_rescan
import com.trackrecord.app.resources.library_scan_report
import com.trackrecord.app.resources.library_scanning
import com.trackrecord.app.resources.library_scanning_progress
import com.trackrecord.app.resources.library_search
import com.trackrecord.app.resources.library_songs
import com.trackrecord.app.resources.library_title
import com.trackrecord.app.resources.list_failed_unreadable
import com.trackrecord.app.resources.listed_songs
import com.trackrecord.app.resources.lookup_no_contact
import com.trackrecord.app.resources.lookup_off
import com.trackrecord.app.resources.lookup_offline
import com.trackrecord.app.resources.missing_year
import com.trackrecord.app.resources.playlist_all
import com.trackrecord.app.resources.remove_from_playlist
import com.trackrecord.app.resources.requests_library_title
import com.trackrecord.app.resources.selected_songs
import com.trackrecord.app.resources.selection_add_to_playlist
import com.trackrecord.app.resources.selection_all_listed
import com.trackrecord.app.resources.selection_clear
import com.trackrecord.app.resources.selection_deselect_all
import com.trackrecord.app.resources.selection_remove_from_playlist
import com.trackrecord.app.resources.selection_select
import com.trackrecord.app.resources.service_acoustid
import com.trackrecord.app.resources.service_musicbrainz
import com.trackrecord.app.resources.service_unavailable
import com.trackrecord.app.resources.song_flag_deezer
import com.trackrecord.app.resources.song_flag_edited
import com.trackrecord.app.resources.song_flag_excluded
import com.trackrecord.app.resources.song_flag_folder_unavailable
import com.trackrecord.app.resources.songs_selected
import com.trackrecord.app.resources.summary_cancelled
import com.trackrecord.app.resources.summary_checked
import com.trackrecord.app.resources.summary_earlier
import com.trackrecord.app.resources.summary_failed
import com.trackrecord.app.resources.summary_new_years
import com.trackrecord.app.resources.summary_no_changes
import com.trackrecord.app.resources.unknown_artist
import com.trackrecord.app.resources.verify_fingerprint_all
import com.trackrecord.app.resources.verify_fingerprint_all_hint
import com.trackrecord.app.resources.verify_fingerprinted
import com.trackrecord.app.resources.verify_intro
import com.trackrecord.app.resources.verify_preparing
import com.trackrecord.app.resources.verify_progress
import com.trackrecord.app.resources.verify_start
import com.trackrecord.app.resources.verify_title
import com.trackrecord.app.ui.Texts
import com.trackrecord.app.ui.components.BackHandler
import com.trackrecord.app.ui.components.BottomBarButtonHeight
import com.trackrecord.app.ui.components.BottomBarSurface
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.Card
import com.trackrecord.app.ui.components.ListItem
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.app.ui.components.PanelGap
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.app.ui.components.panelRow
import com.trackrecord.data.db.FolderEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.deezer.DefaultPlaylists
import com.trackrecord.data.library.LibrarySong
import com.trackrecord.data.library.LibraryStats
import com.trackrecord.data.library.ScanState
import com.trackrecord.data.musicbrainz.AcoustIdKeyStatus
import com.trackrecord.data.musicbrainz.LookupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onSong: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val ui = LocalPlatformUi.current
    val vm: LibraryViewModel = viewModel(factory = viewModelFactory {
        initializer { LibraryViewModel(container.library, container.musicBrainz, container.playlists) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val verify by container.verifier.state.collectAsStateWithLifecycle()
    val keyStatus by container.fingerprints.keyStatus.collectAsStateWithLifecycle()
    val deezer by container.deezerImports.state.collectAsStateWithLifecycle()
    val gameRequests by container.game.requests.collectAsStateWithLifecycle()
    val appSettings by container.settings.appSettings.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf<FolderEntity?>(null) }
    val query by vm.query.collectAsStateWithLifecycle()
    var askingFiles by rememberSaveable { mutableStateOf(false) }
    // After the permission screen: watch the downloads if it was given, then open Exportify either way.
    val afterPermission = {
        container.exportify?.let { if (it.hasAccess()) it.start() }
        ui.openExportify()
    }
    val askAccess = ui.rememberDownloadsAccess(afterPermission)
    if (askingFiles) {
        AlertDialog(
            onDismissRequest = { askingFiles = false },
            title = { Text(stringResource(Res.string.exportify_permission_title)) },
            text = { Text(stringResource(Res.string.exportify_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askingFiles = false
                    askAccess?.invoke() ?: afterPermission()
                }) { Text(stringResource(Res.string.exportify_permission_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askingFiles = false
                    ui.openExportify()
                }) { Text(stringResource(Res.string.exportify_permission_skip)) }
            },
        )
    }
    var showingStats by rememberSaveable { mutableStateOf(false) }
    var addingTo by rememberSaveable { mutableStateOf(false) }
    var removingFrom by rememberSaveable { mutableStateOf(false) }

    val listScope = rememberCoroutineScope()
    // First visit: the explanation opens by itself; once closed, the info button is highlighted (and a
    // message says where it is) until it's tapped, so it's clear where to read it again (feedback round 45).
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var infoHighlight by rememberSaveable { mutableStateOf(false) }
    var firstInfo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(appSettings?.libraryInfoSeen) {
        if (appSettings?.libraryInfoSeen == false && !firstInfo) {
            firstInfo = true
            infoOpen = true
            container.settings.update { it.copy(libraryInfoSeen = true) }
        }
    }
    val infoHint = stringResource(Res.string.library_info_hint)
    if (infoOpen) {
        val closeInfo = {
            infoOpen = false
            if (firstInfo) {
                firstInfo = false
                infoHighlight = true
                listScope.launch { snackbar.showSnackbar(infoHint) }
            }
        }
        LibraryInfoSheet(
            onDismiss = closeInfo,
            defaultsMissing = DefaultPlaylists.ALL.any { d -> state.playlists.none { it.deezerPlaylistId == d.deezerId } },
            importing = deezer is DeezerImportState.Running,
            // Closed straight away: the progress shows in the Playlists card.
            onGetDefaults = {
                container.deezerImports.importDefaults()
                closeInfo()
            },
        )
    }
    val unreadable = stringResource(Res.string.list_failed_unreadable)
    val pickList = ui.rememberListPicker { file ->
        listScope.launch {
            val files = withContext(Dispatchers.IO) { readSongLists(container.platform.files, file) }
            if (files.isNotEmpty()) container.deezerImports.importLists(files)
            else snackbar.showSnackbar(unreadable)
        }
    }
    val pickFolder = ui.rememberFolderPicker { folder -> vm.addFolder(folder) }

    LaunchedEffect(vm) {
        vm.reports.collect { r ->
            if (r.added + r.updated + r.removed > 0) {
                snackbar.showSnackbar(Texts.get(Res.string.library_scan_report, r.added, r.updated, r.removed))
            }
        }
    }
    BackHandler(enabled = state.selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(Res.plurals.selected_songs, state.selection.size, state.selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, stringResource(Res.string.selection_clear)) }
                    },
                    actions = {
                        SelectAllButton(state.allListedSelected, onSelectAll = vm::selectAllListed, onDeselectAll = vm::clearSelection)
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(Res.string.library_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) }
                    },
                    actions = {
                        LibraryInfoButton(highlight = infoHighlight) {
                            infoHighlight = false
                            infoOpen = true
                        }
                        IconButton(onClick = vm::rescan, enabled = state.scan == ScanState.Idle) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.library_rescan))
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.selecting) {
                BottomBarSurface {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { addingTo = true }, modifier = Modifier.weight(1f).height(BottomBarButtonHeight)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(stringResource(Res.string.selection_add_to_playlist), modifier = Modifier.padding(start = 4.dp))
                        }
                        OutlinedButton(onClick = { removingFrom = true }, enabled = state.selectionPlaylists.isNotEmpty() || state.selectionExcluded, modifier = Modifier.weight(1f).height(BottomBarButtonHeight)) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Text(stringResource(Res.string.selection_remove_from_playlist), modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The list is built in the background: until then a spinner, not an empty library (feedback round 30).
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        // Every part in a panel, all with the same margins (feedback round 58); the songs' rows make one panel.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 16.dp - PanelGap / 2, bottom = 24.dp),
        ) {
            item { ScanProgress(state.scan) }
            item { FoldersCard(folders = state.folders, onAdd = { pickFolder() }, onRemove = { removing = it }) }
            // Requests started elsewhere show here too, with Stop (feedback round 48): a game's lookups and covers.
            if (gameRequests.running) {
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = PanelGap / 2)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(Res.string.requests_library_title), style = MaterialTheme.typography.titleMedium)
                            GameRequestsPanel(gameRequests, onStop = container.game::stopRequests, modifier = Modifier.padding(top = 4.dp), title = false)
                        }
                    }
                }
            }
            item {
                VerifyCard(
                    state = verify,
                    onStart = container.verifier::start,
                    onCancel = container.verifier::cancel,
                    coversEnabled = appSettings?.loadCovers == true,
                    keyStatus = keyStatus,
                )
            }
            item {
                PlaylistsCard(
                    playlists = state.playlists,
                    builtIns = state.builtIns,
                    totalSongs = state.totalSongs,
                    selected = state.query.playlistId,
                    onSelect = { id -> vm.updateQuery { it.copy(playlistId = id) } },
                    onCreate = { vm.createPlaylist(it) },
                    onRename = { id, name -> vm.renamePlaylist(id, name) },
                    onDelete = { id -> vm.deletePlaylist(id) { container.deezerImports.cleanUp() } },
                    deezer = deezer,
                    onAddDeezer = container.deezerImports::import,
                    onUpdateDeezer = container.deezerImports::update,
                    onDismissDeezer = container.deezerImports::dismiss,
                    onCancelDeezer = container.deezerImports::cancel,
                    onImportList = { pickList() },
                    onExportify = {
                        // With the file permission the download is imported by itself; ask first (feedback round 32).
                        val watcher = container.exportify
                        when {
                            watcher == null -> ui.openExportify()
                            watcher.hasAccess() -> {
                                watcher.start()
                                ui.openExportify()
                            }
                            else -> askingFiles = true
                        }
                    },
                )
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = PanelGap / 2)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(
                                Res.string.library_counts,
                                pluralStringResource(Res.plurals.library_songs, state.totalSongs, state.totalSongs),
                                state.playableSongs,
                                state.missingYearSongs,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = query.text,
                            onValueChange = { t -> vm.updateQuery { it.copy(text = t) } },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            placeholder = { Text(stringResource(Res.string.library_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item { FilterPane(query, state.playlists, state.builtIns, onChange = vm::updateQuery, onReset = vm::resetFilters, onStats = { showingStats = true }) }
            // The listed songs as one panel: its header row, then a row per song (feedback round 58).
            item {
                Column(Modifier.padding(start = 16.dp, top = PanelGap / 2, end = 16.dp).panelRow(first = true, last = state.songs.isEmpty())) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pluralStringResource(Res.plurals.listed_songs, state.songs.size, state.songs.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        SelectAllButton(state.allListedSelected, onSelectAll = vm::selectAllListed, onDeselectAll = vm::clearSelection, enabled = state.songs.isNotEmpty())
                    }
                    if (state.loaded && state.songs.isEmpty()) {
                        Text(
                            text = stringResource(if (state.totalSongs == 0) Res.string.library_empty else Res.string.library_no_matches),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        )
                    }
                }
            }
            itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                Column(Modifier.padding(horizontal = 16.dp).panelRow(first = false, last = index == state.songs.lastIndex)) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SongRow(
                        song = song,
                        selecting = state.selecting,
                        selected = song.id in state.selection,
                        onClick = { if (state.selecting) vm.toggleSelection(song.id) else onSong(song.id) },
                        onLongClick = { vm.toggleSelection(song.id) },
                    )
                }
            }
        }
    }

    if (showingStats) {
        val playlist = (state.builtIns + state.playlists).firstOrNull { it.id == state.query.playlistId }
        LibraryStatsSheet(
            title = playlist?.let { playlistName(it) } ?: stringResource(Res.string.playlist_all),
            filtered = state.query.text.isNotBlank() || state.query.copy(playlistId = null).activeFilters > 0,
            stats = remember(state.songs) { LibraryStats.of(state.songs) },
            onDismiss = { showingStats = false },
        )
    }
    if (addingTo) {
        PlaylistPickerDialog(
            title = pluralStringResource(Res.plurals.songs_selected, state.selection.size, state.selection.size),
            playlists = state.playlists,
            allowNew = true,
            excluded = stringResource(Res.string.excluded_add),
            playlistsLabel = stringResource(Res.string.add_to_playlist),
            onDismiss = { addingTo = false },
            onPick = { vm.addSelectionTo(it); addingTo = false },
            onNew = { vm.addSelectionToNew(it); addingTo = false },
        )
    }
    if (removingFrom) {
        PlaylistPickerDialog(
            title = pluralStringResource(Res.plurals.songs_selected, state.selection.size, state.selection.size),
            // Only playlists that hold a selected song (feedback round 5).
            playlists = state.selectionPlaylists,
            allowNew = false,
            excluded = if (state.selectionExcluded) stringResource(Res.string.excluded_remove) else null,
            playlistsLabel = stringResource(Res.string.remove_from_playlist),
            onDismiss = { removingFrom = false },
            // A streamed song left on no playlist leaves the library (feedback round 28).
            onPick = { vm.removeSelectionFrom(it) { container.deezerImports.cleanUp() }; removingFrom = false },
        )
    }
    removing?.let { folder ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(Res.string.library_remove_folder_title)) },
            text = { Text(stringResource(Res.string.library_remove_folder_text, folder.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    ui.releaseFolder(folder.treeUri)
                    vm.removeFolder(folder.id)
                    removing = null
                }) { Text(stringResource(Res.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ScanProgress(scan: ScanState) {
    if (scan !is ScanState.Scanning) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = PanelGap / 2)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = if (scan.total > 0) stringResource(Res.string.library_scanning_progress, scan.folderName, scan.done, scan.total)
                else stringResource(Res.string.library_scanning, scan.folderName),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (scan.total > 0) {
                LinearProgressIndicator(progress = { scan.done.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), drawStopIndicator = {})
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun FoldersCard(folders: List<FolderEntity>, onAdd: () -> Unit, onRemove: (FolderEntity) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = PanelGap / 2)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(Res.string.library_folders), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (folders.isEmpty()) {
                Text(stringResource(Res.string.library_no_folders), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            for (folder in folders) {
                ListItem(
                    headlineContent = { Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (!folder.available) {
                        { Text(stringResource(Res.string.library_folder_unavailable), color = MaterialTheme.colorScheme.error) }
                    } else null,
                    leadingContent = if (!folder.available) {
                        { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                    } else null,
                    trailingContent = {
                        Row {
                            if (!folder.available) TextButton(onClick = onAdd) { Text(stringResource(Res.string.library_pick_again)) }
                            IconButton(onClick = { onRemove(folder) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.library_remove_folder, folder.displayName))
                            }
                        }
                    },
                )
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(Res.string.library_add_folder), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun VerifyCard(state: VerifyState, onStart: (BulkTask) -> Unit, onCancel: () -> Unit, coversEnabled: Boolean, keyStatus: AcoustIdKeyStatus) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = PanelGap / 2)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.verify_title), style = MaterialTheme.typography.titleMedium)
            AcoustIdWarning(keyStatus)
            when (state) {
                is VerifyState.Running -> {
                    val label = when (state.task) {
                        BulkTask.VERIFY -> Res.string.verify_progress
                        BulkTask.FIND_MISSING -> Res.string.find_missing_progress
                        BulkTask.LOAD_COVERS -> Res.string.covers_progress
                        BulkTask.FINGERPRINT_ALL -> Res.string.verify_progress
                    }
                    if (state.total == 0) {
                        // Picking the songs (feedback round 29): the bar shows right away.
                        Text(stringResource(Res.string.verify_preparing))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    } else {
                        Text(stringResource(label, state.done, state.total, state.matched))
                        val fingerprinting = state.task == BulkTask.FINGERPRINT_ALL
                        if (fingerprinting && state.toFingerprint > 0) {
                            Text(stringResource(Res.string.verify_fingerprinted, state.fingerprinted, state.toFingerprint), style = MaterialTheme.typography.bodySmall)
                        }
                        // Fingerprinting is part of each local song's work there, so the bar moves from the first file on.
                        val progress = if (fingerprinting) (state.fingerprinted + state.done) / (state.toFingerprint + state.total).toFloat() else state.done.toFloat() / state.total
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            drawStopIndicator = {},
                        )
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) }
                }
                else -> {
                    if (state is VerifyState.Finished) {
                        if (state.task == BulkTask.LOAD_COVERS) Text(stringResource(Res.string.covers_done, state.matched, state.total))
                        else LookupSummary(state)
                        if (state.noArtist > 0) {
                            Text(pluralStringResource(Res.plurals.find_missing_no_artist, state.noArtist, state.noArtist), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(stringResource(Res.string.verify_intro), style = MaterialTheme.typography.bodySmall)
                    }
                    // In this order (feedback round 30): missing years, covers, then the two verifications.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedButton(onClick = { onStart(BulkTask.FIND_MISSING) }) { Text(stringResource(Res.string.find_missing_start)) }
                        if (coversEnabled) OutlinedButton(onClick = { onStart(BulkTask.LOAD_COVERS) }) { Text(stringResource(Res.string.covers_start)) }
                        OutlinedButton(onClick = { onStart(BulkTask.VERIFY) }) { Text(stringResource(Res.string.verify_start)) }
                        // Also songs already matched by name: different spellings can hide the right
                        // recording, which the audio finds (D-194). Needs a working AcoustID key.
                        if (keyStatus.fingerprinting) {
                            OutlinedButton(onClick = { onStart(BulkTask.FINGERPRINT_ALL) }) { Text(stringResource(Res.string.verify_fingerprint_all)) }
                        }
                    }
                    if (keyStatus.fingerprinting) Text(stringResource(Res.string.verify_fingerprint_all_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * What a lookup changed (feedback rounds 29, 30): songs checked, new years, earlier years and failures.
 */
@Composable
private fun LookupSummary(state: VerifyState.Finished) {
    // A service kept refusing even at the slowest pace: say so first (feedback round 47).
    state.unavailable?.let { Text(serviceUnavailableText(it), color = MaterialTheme.colorScheme.error) }
    // Nothing could be checked at all: say why (feedback round 47: the reason is known, no list of guesses).
    if (state.unavailable == null && state.total > 0 && state.failed == state.total) {
        Text(if (state.offline) stringResource(Res.string.lookup_offline) else lookupsOffText(), color = MaterialTheme.colorScheme.error)
        return
    }
    val title = state.cancelledOf?.let { of -> pluralStringResource(Res.plurals.summary_cancelled, of, state.total, of) }
        ?: pluralStringResource(Res.plurals.summary_checked, state.total, state.total)
    Text(title, style = MaterialTheme.typography.titleSmall)
    val lines = buildList {
        if (state.newYears > 0) add(pluralStringResource(Res.plurals.summary_new_years, state.newYears, state.newYears))
        if (state.earlier > 0) add(pluralStringResource(Res.plurals.summary_earlier, state.earlier, state.earlier))
        if (state.failed > 0) add(pluralStringResource(Res.plurals.summary_failed, state.failed, state.failed))
    }
    if (lines.isEmpty()) Text(stringResource(Res.string.summary_no_changes))
    for (line in lines) Text("• $line")
}

/**
 * Why lookups don't run: switched off in Settings, or (only in a build without a built-in contact address, where
 * Settings asks for one) no contact email.
 */
@Composable
fun lookupsOffText(): String {
    val settings by LocalAppContainer.current.settings.appSettings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    return stringResource(if (s != null && s.musicBrainzEnabled && s.contactEmailInUse.isBlank()) Res.string.lookup_no_contact else Res.string.lookup_off)
}

/** MusicBrainz or AcoustID turned requests away, also at the slowest pace: try again later (feedback round 47). */
@Composable
fun serviceUnavailableText(service: LookupService): String = stringResource(
    Res.string.service_unavailable,
    stringResource(if (service == LookupService.ACOUSTID) Res.string.service_acoustid else Res.string.service_musicbrainz),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(song: LibrarySong, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val artist = song.song.artist ?: stringResource(Res.string.unknown_artist)
    val flags = buildList {
        if (song.edited) add(stringResource(Res.string.song_flag_edited))
        if (song.excluded) add(stringResource(Res.string.song_flag_excluded))
        if (!song.folderAvailable) add(stringResource(Res.string.song_flag_folder_unavailable))
        if (song.song.source == SongSource.DEEZER) add(stringResource(Res.string.song_flag_deezer))
    }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(Res.string.selection_select)),
        leadingContent = if (selecting) {
            { Checkbox(checked = selected, onCheckedChange = null) }
        } else null,
        headlineContent = { Text(song.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (flags.isNotEmpty()) {
                    Text(flags.joinToString(stringResource(Res.string.flag_separator)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailingContent = {
            val year = song.resolved.year
            if (year != null) {
                Text(formatYear(year), style = MaterialTheme.typography.titleLarge)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(Res.string.missing_year), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    )
}

/** "Select all listed", or "Deselect all" once everything listed is selected (feedback round 5). */
@Composable
private fun SelectAllButton(allSelected: Boolean, onSelectAll: () -> Unit, onDeselectAll: () -> Unit, enabled: Boolean = true) {
    if (allSelected) TextButton(onClick = onDeselectAll) { Text(stringResource(Res.string.selection_deselect_all)) }
    else TextButton(onClick = onSelectAll, enabled = enabled) { Text(stringResource(Res.string.selection_all_listed)) }
}
