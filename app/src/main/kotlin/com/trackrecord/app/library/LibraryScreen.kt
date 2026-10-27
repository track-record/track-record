package com.trackrecord.app.library

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.contactEmailInUse
import com.trackrecord.app.game.GameRequestsPanel
import com.trackrecord.app.ui.components.BottomBarSurface
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.Card
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.app.ui.components.formatYear
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onSong: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
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
        if (container.exportify.hasAccess()) container.exportify.start()
        openExportify(context)
    }
    val allFilesAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { afterPermission() }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { afterPermission() }
    if (askingFiles) {
        AlertDialog(
            onDismissRequest = { askingFiles = false },
            title = { Text(stringResource(R.string.exportify_permission_title)) },
            text = { Text(stringResource(R.string.exportify_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askingFiles = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                        runCatching { allFilesAccess.launch(intent) }.onFailure { afterPermission() }
                    } else {
                        storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }) { Text(stringResource(R.string.exportify_permission_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askingFiles = false
                    openExportify(context)
                }) { Text(stringResource(R.string.exportify_permission_skip)) }
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
    val infoHint = stringResource(R.string.library_info_hint)
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
    val unreadable = stringResource(R.string.list_failed_unreadable)
    val pickList = rememberLauncherForActivityResult(OpenInDownloads()) { uri: Uri? ->
        if (uri != null) {
            listScope.launch {
                val files = withContext(Dispatchers.IO) { readSongLists(context, uri) }
                if (files.isNotEmpty()) container.deezerImports.importLists(files)
                else snackbar.showSnackbar(unreadable)
            }
        }
    }
    // Only CSV files in the picker (feedback round 20); text lists and Exportify's ZIP still import when
    // opened from a file manager or the download message.
    val listTypes = arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/x-csv", "application/vnd.ms-excel")

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.addFolder(uri.toString())
        }
    }

    val resources = LocalResources.current
    LaunchedEffect(vm) {
        vm.reports.collect { r ->
            if (r.added + r.updated + r.removed > 0) {
                snackbar.showSnackbar(resources.getString(R.string.library_scan_report, r.added, r.updated, r.removed))
            }
        }
    }
    BackHandler(enabled = state.selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_songs, state.selection.size, state.selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, stringResource(R.string.selection_clear)) }
                    },
                    actions = {
                        SelectAllButton(state.allListedSelected, onSelectAll = vm::selectAllListed, onDeselectAll = vm::clearSelection)
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.library_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                    },
                    actions = {
                        LibraryInfoButton(highlight = infoHighlight) {
                            infoHighlight = false
                            infoOpen = true
                        }
                        IconButton(onClick = vm::rescan, enabled = state.scan == ScanState.Idle) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.library_rescan))
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.selecting) {
                BottomBarSurface {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { addingTo = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(stringResource(R.string.selection_add_to_playlist), modifier = Modifier.padding(start = 4.dp))
                        }
                        OutlinedButton(onClick = { removingFrom = true }, enabled = state.selectionPlaylists.isNotEmpty() || state.selectionExcluded, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Text(stringResource(R.string.selection_remove_from_playlist), modifier = Modifier.padding(start = 4.dp))
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { ScanProgress(state.scan) }
            item { FoldersCard(folders = state.folders, onAdd = { pickFolder.launch(null) }, onRemove = { removing = it }) }
            // Requests started elsewhere show here too, with Stop (feedback round 48): a game's lookups and covers.
            if (gameRequests.running) {
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.requests_library_title), style = MaterialTheme.typography.titleMedium)
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
                    onImportList = { pickList.launch(listTypes) },
                    onExportify = {
                        // With the file permission the download is imported by itself; ask first (feedback round 32).
                        if (container.exportify.hasAccess()) {
                            container.exportify.start()
                            openExportify(context)
                        } else {
                            askingFiles = true
                        }
                    },
                )
            }
            item {
                Text(
                    text = stringResource(
                        R.string.library_counts,
                        pluralStringResource(R.plurals.library_songs, state.totalSongs, state.totalSongs),
                        state.playableSongs,
                        state.missingYearSongs,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = query.text,
                    onValueChange = { t -> vm.updateQuery { it.copy(text = t) } },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.library_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            item { FilterPane(query, state.playlists, state.builtIns, onChange = vm::updateQuery, onReset = vm::resetFilters, onStats = { showingStats = true }) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.listed_songs, state.songs.size, state.songs.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    SelectAllButton(state.allListedSelected, onSelectAll = vm::selectAllListed, onDeselectAll = vm::clearSelection, enabled = state.songs.isNotEmpty())
                }
            }
            if (state.loaded && state.songs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(if (state.totalSongs == 0) R.string.library_empty else R.string.library_no_matches),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(state.songs, key = { it.id }) { song ->
                SongRow(
                    song = song,
                    selecting = state.selecting,
                    selected = song.id in state.selection,
                    onClick = { if (state.selecting) vm.toggleSelection(song.id) else onSong(song.id) },
                    onLongClick = { vm.toggleSelection(song.id) },
                )
                HorizontalDivider()
            }
        }
    }

    if (showingStats) {
        val playlist = (state.builtIns + state.playlists).firstOrNull { it.id == state.query.playlistId }
        LibraryStatsSheet(
            title = playlist?.let { playlistName(it) } ?: stringResource(R.string.playlist_all),
            filtered = state.query.text.isNotBlank() || state.query.copy(playlistId = null).activeFilters > 0,
            stats = remember(state.songs) { LibraryStats.of(state.songs) },
            onDismiss = { showingStats = false },
        )
    }
    if (addingTo) {
        PlaylistPickerDialog(
            title = pluralStringResource(R.plurals.songs_selected, state.selection.size, state.selection.size),
            playlists = state.playlists,
            allowNew = true,
            excluded = stringResource(R.string.excluded_add),
            playlistsLabel = stringResource(R.string.add_to_playlist),
            onDismiss = { addingTo = false },
            onPick = { vm.addSelectionTo(it); addingTo = false },
            onNew = { vm.addSelectionToNew(it); addingTo = false },
        )
    }
    if (removingFrom) {
        PlaylistPickerDialog(
            title = pluralStringResource(R.plurals.songs_selected, state.selection.size, state.selection.size),
            // Only playlists that hold a selected song (feedback round 5).
            playlists = state.selectionPlaylists,
            allowNew = false,
            excluded = if (state.selectionExcluded) stringResource(R.string.excluded_remove) else null,
            playlistsLabel = stringResource(R.string.remove_from_playlist),
            onDismiss = { removingFrom = false },
            // A streamed song left on no playlist leaves the library (feedback round 28).
            onPick = { vm.removeSelectionFrom(it) { container.deezerImports.cleanUp() }; removingFrom = false },
        )
    }
    removing?.let { folder ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.library_remove_folder_title)) },
            text = { Text(stringResource(R.string.library_remove_folder_text, folder.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(Uri.parse(folder.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    vm.removeFolder(folder.id)
                    removing = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ScanProgress(scan: ScanState) {
    if (scan !is ScanState.Scanning) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = if (scan.total > 0) stringResource(R.string.library_scanning_progress, scan.folderName, scan.done, scan.total)
            else stringResource(R.string.library_scanning, scan.folderName),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (scan.total > 0) {
            LinearProgressIndicator(progress = { scan.done.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), drawStopIndicator = {})
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun FoldersCard(folders: List<FolderEntity>, onAdd: () -> Unit, onRemove: (FolderEntity) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.library_folders), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (folders.isEmpty()) {
                Text(stringResource(R.string.library_no_folders), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            for (folder in folders) {
                ListItem(
                    headlineContent = { Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (!folder.available) {
                        { Text(stringResource(R.string.library_folder_unavailable), color = MaterialTheme.colorScheme.error) }
                    } else null,
                    leadingContent = if (!folder.available) {
                        { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                    } else null,
                    trailingContent = {
                        Row {
                            if (!folder.available) TextButton(onClick = onAdd) { Text(stringResource(R.string.library_pick_again)) }
                            IconButton(onClick = { onRemove(folder) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.library_remove_folder, folder.displayName))
                            }
                        }
                    },
                )
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.library_add_folder), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun VerifyCard(state: VerifyState, onStart: (BulkTask) -> Unit, onCancel: () -> Unit, coversEnabled: Boolean, keyStatus: AcoustIdKeyStatus) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.verify_title), style = MaterialTheme.typography.titleMedium)
            AcoustIdWarning(keyStatus)
            when (state) {
                is VerifyState.Running -> {
                    val label = when (state.task) {
                        BulkTask.VERIFY -> R.string.verify_progress
                        BulkTask.FIND_MISSING -> R.string.find_missing_progress
                        BulkTask.LOAD_COVERS -> R.string.covers_progress
                        BulkTask.FINGERPRINT_ALL -> R.string.verify_progress
                    }
                    if (state.total == 0) {
                        // Picking the songs (feedback round 29): the bar shows right away.
                        Text(stringResource(R.string.verify_preparing))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    } else {
                        Text(stringResource(label, state.done, state.total, state.matched))
                        val fingerprinting = state.task == BulkTask.FINGERPRINT_ALL
                        if (fingerprinting && state.toFingerprint > 0) {
                            Text(stringResource(R.string.verify_fingerprinted, state.fingerprinted, state.toFingerprint), style = MaterialTheme.typography.bodySmall)
                        }
                        // Fingerprinting is part of each local song's work there, so the bar moves from the first file on.
                        val progress = if (fingerprinting) (state.fingerprinted + state.done) / (state.toFingerprint + state.total).toFloat() else state.done.toFloat() / state.total
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            drawStopIndicator = {},
                        )
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
                else -> {
                    if (state is VerifyState.Finished) {
                        if (state.task == BulkTask.LOAD_COVERS) Text(stringResource(R.string.covers_done, state.matched, state.total))
                        else LookupSummary(state)
                        if (state.noArtist > 0) {
                            Text(pluralStringResource(R.plurals.find_missing_no_artist, state.noArtist, state.noArtist), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(stringResource(R.string.verify_intro), style = MaterialTheme.typography.bodySmall)
                    }
                    // In this order (feedback round 30): missing years, covers, then the two verifications.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedButton(onClick = { onStart(BulkTask.FIND_MISSING) }) { Text(stringResource(R.string.find_missing_start)) }
                        if (coversEnabled) OutlinedButton(onClick = { onStart(BulkTask.LOAD_COVERS) }) { Text(stringResource(R.string.covers_start)) }
                        OutlinedButton(onClick = { onStart(BulkTask.VERIFY) }) { Text(stringResource(R.string.verify_start)) }
                        // Also songs already matched by name: different spellings can hide the right
                        // recording, which the audio finds (D-194). Needs a working AcoustID key.
                        if (keyStatus.fingerprinting) {
                            OutlinedButton(onClick = { onStart(BulkTask.FINGERPRINT_ALL) }) { Text(stringResource(R.string.verify_fingerprint_all)) }
                        }
                    }
                    if (keyStatus.fingerprinting) Text(stringResource(R.string.verify_fingerprint_all_hint), style = MaterialTheme.typography.bodySmall)
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
        Text(if (state.offline) stringResource(R.string.lookup_offline) else lookupsOffText(), color = MaterialTheme.colorScheme.error)
        return
    }
    val title = state.cancelledOf?.let { of -> pluralStringResource(R.plurals.summary_cancelled, of, state.total, of) }
        ?: pluralStringResource(R.plurals.summary_checked, state.total, state.total)
    Text(title, style = MaterialTheme.typography.titleSmall)
    val lines = buildList {
        if (state.newYears > 0) add(pluralStringResource(R.plurals.summary_new_years, state.newYears, state.newYears))
        if (state.earlier > 0) add(pluralStringResource(R.plurals.summary_earlier, state.earlier, state.earlier))
        if (state.failed > 0) add(pluralStringResource(R.plurals.summary_failed, state.failed, state.failed))
    }
    if (lines.isEmpty()) Text(stringResource(R.string.summary_no_changes))
    for (line in lines) Text("• $line")
}

/**
 * Why lookups don't run: switched off in Settings, or (only in a build without a built-in contact address, where
 * Settings asks for one) no contact email.
 */
@Composable
fun lookupsOffText(): String {
    val settings by LocalContext.current.appContainer.settings.appSettings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    return stringResource(if (s != null && s.musicBrainzEnabled && s.contactEmailInUse.isBlank()) R.string.lookup_no_contact else R.string.lookup_off)
}

/** MusicBrainz or AcoustID turned requests away, also at the slowest pace: try again later (feedback round 47). */
@Composable
fun serviceUnavailableText(service: LookupService): String = stringResource(
    R.string.service_unavailable,
    stringResource(if (service == LookupService.ACOUSTID) R.string.service_acoustid else R.string.service_musicbrainz),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(song: LibrarySong, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val artist = song.song.artist ?: stringResource(R.string.unknown_artist)
    val flags = buildList {
        if (song.edited) add(stringResource(R.string.song_flag_edited))
        if (song.excluded) add(stringResource(R.string.song_flag_excluded))
        if (!song.folderAvailable) add(stringResource(R.string.song_flag_folder_unavailable))
        if (song.song.source == SongSource.DEEZER) add(stringResource(R.string.song_flag_deezer))
    }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.selection_select)),
        leadingContent = if (selecting) {
            { Checkbox(checked = selected, onCheckedChange = null) }
        } else null,
        headlineContent = { Text(song.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (flags.isNotEmpty()) {
                    Text(flags.joinToString(stringResource(R.string.flag_separator)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailingContent = {
            val year = song.resolved.year
            if (year != null) {
                Text(formatYear(year), style = MaterialTheme.typography.titleLarge)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.missing_year), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    )
}

/** The document picker, opening in Downloads where the browser saves Exportify's files. */
/**
 * Opens the picker in "Downloads" (the downloads provider, as in the picker's menu), which lists files
 * of the accepted types first, rather than the storage folder Download, which lists everything mixed
 * (feedback round 20).
 */
private class OpenInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            android.provider.DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"),
        )
}

/**
 * Exportify as a Custom Tab of the default browser, so its Spotify login carries over (D-092); its
 * menu also opens the page in the browser app. Tapping "Open" on Chrome's download message sends the
 * file back to the app, which closes the tab and imports it (D-097).
 */
private fun openExportify(context: android.content.Context) {
    runCatching {
        androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse("https://exportify.app/"))
    }
}

/** "Select all listed", or "Deselect all" once everything listed is selected (feedback round 5). */
@Composable
private fun SelectAllButton(allSelected: Boolean, onSelectAll: () -> Unit, onDeselectAll: () -> Unit, enabled: Boolean = true) {
    if (allSelected) TextButton(onClick = onDeselectAll) { Text(stringResource(R.string.selection_deselect_all)) }
    else TextButton(onClick = onSelectAll, enabled = enabled) { Text(stringResource(R.string.selection_all_listed)) }
}
