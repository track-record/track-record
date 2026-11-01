package com.trackrecord.app.newgame

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.game.PoolInfo
import com.trackrecord.app.game.message
import com.trackrecord.app.library.StatsContent
import com.trackrecord.app.library.playlistName
import com.trackrecord.app.players.asGamePlayer
import com.trackrecord.app.remote.RemotePlayDialog
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_back
import com.trackrecord.app.resources.action_cancel
import com.trackrecord.app.resources.action_next
import com.trackrecord.app.resources.ic_qr_code
import com.trackrecord.app.resources.library_songs
import com.trackrecord.app.resources.mode_closest
import com.trackrecord.app.resources.mode_closest_hint
import com.trackrecord.app.resources.mode_timeline
import com.trackrecord.app.resources.mode_timeline_hint
import com.trackrecord.app.resources.name_with_detail
import com.trackrecord.app.resources.new_game_reset
import com.trackrecord.app.resources.new_game_start
import com.trackrecord.app.resources.new_game_step
import com.trackrecord.app.resources.new_game_title
import com.trackrecord.app.resources.players_add_guest
import com.trackrecord.app.resources.players_add_saved
import com.trackrecord.app.resources.players_drag_hint
import com.trackrecord.app.resources.players_hint
import com.trackrecord.app.resources.players_remove
import com.trackrecord.app.resources.players_seat
import com.trackrecord.app.resources.players_shuffle
import com.trackrecord.app.resources.playlist_all
import com.trackrecord.app.resources.pool_count
import com.trackrecord.app.resources.pool_counting
import com.trackrecord.app.resources.pool_exclude
import com.trackrecord.app.resources.pool_exclude_hint
import com.trackrecord.app.resources.pool_include
import com.trackrecord.app.resources.pool_playlists_hint
import com.trackrecord.app.resources.pool_streamed
import com.trackrecord.app.resources.pool_too_small
import com.trackrecord.app.resources.pool_year_any
import com.trackrecord.app.resources.pool_year_filter
import com.trackrecord.app.resources.pool_year_from
import com.trackrecord.app.resources.pool_year_to
import com.trackrecord.app.resources.remote_button
import com.trackrecord.app.resources.step_mode
import com.trackrecord.app.resources.step_players
import com.trackrecord.app.resources.step_pool
import com.trackrecord.app.resources.step_settings
import com.trackrecord.app.ui.components.BackHandler
import com.trackrecord.app.ui.components.BottomBarButtonHeight
import com.trackrecord.app.ui.components.BottomBarSurface
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.Card
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.app.ui.components.PanelGap
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.SectionCard
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.data.db.PlaylistWithCount
import com.trackrecord.data.db.ProfileEntity
import com.trackrecord.engine.CommonSettings
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.year.MIN_PLAYABLE_YEAR
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NewGameScreen(onBack: () -> Unit, onStarted: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: NewGameViewModel = viewModel(factory = viewModelFactory {
        initializer { NewGameViewModel(container.game, container.library, container.setups, container.profiles, container.playlists) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val pool by vm.pool.collectAsStateWithLifecycle()
    val poolSize = pool?.size

    val steps = SetupStep.entries
    val index = steps.indexOf(state.step)
    val back = { if (index == 0) onBack() else vm.goTo(steps[index - 1]) }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.new_game_title)) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) }
                },
            )
        },
        bottomBar = {
            BottomBarSurface {
                Column(Modifier.fillMaxWidth()) {
                    state.error?.let { Text(stringResource(it.message), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                    val canContinue = when (state.step) {
                        SetupStep.MODE -> true
                        SetupStep.PLAYERS -> state.enoughPlayers
                        SetupStep.POOL -> poolSize != null && poolSize >= state.songsNeeded
                        SetupStep.SETTINGS -> state.settings.validate(state.mode).isEmpty() && poolSize != null && poolSize >= state.songsNeeded && !state.starting
                    }
                    Button(
                        onClick = { if (state.step == SetupStep.SETTINGS) vm.start(onStarted) else vm.goTo(steps[index + 1]) },
                        enabled = canContinue,
                        modifier = Modifier.fillMaxWidth().height(BottomBarButtonHeight),
                    ) {
                        Text(
                            stringResource(if (state.step == SetupStep.SETTINGS) Res.string.new_game_start else Res.string.action_next),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        },
    ) { padding ->
        // The step and everything in it in panels (feedback round 58).
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(PanelGap),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.new_game_step, index + 1, steps.size, stringResource(state.step.title)),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        // Small, next to the title (feedback round 10).
                        if (state.step == SetupStep.SETTINGS) {
                            OutlinedButton(onClick = { vm.resetToDefaults() }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp)) {
                                Text(stringResource(Res.string.new_game_reset))
                            }
                        }
                    }
                    if (state.step == SetupStep.PLAYERS) {
                        Text(stringResource(Res.string.players_hint, GameEngine.MIN_PLAYERS, GameEngine.MAX_PLAYERS), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            when (state.step) {
                SetupStep.MODE -> ModeStep(state.mode, vm::setMode)
                SetupStep.PLAYERS -> PlayersStep(state, vm)
                SetupStep.POOL -> PoolStep(state.settings.common, playlists, pool, state.songsNeeded) { c -> vm.updateSettings { it.copy(common = c) } }
                SetupStep.SETTINGS -> GameSettingsEditor(state.mode, state.settings, vm::updateSettings)
            }
        }
    }
}

private val SetupStep.title: StringResource
    get() = when (this) {
        SetupStep.MODE -> Res.string.step_mode
        SetupStep.PLAYERS -> Res.string.step_players
        SetupStep.POOL -> Res.string.step_pool
        SetupStep.SETTINGS -> Res.string.step_settings
    }

@Composable
private fun ModeStep(mode: GameMode, onSelect: (GameMode) -> Unit) {
    for ((m, title, hint) in listOf(
        Triple(GameMode.TIMELINE, Res.string.mode_timeline, Res.string.mode_timeline_hint),
        Triple(GameMode.CLOSEST_YEAR, Res.string.mode_closest, Res.string.mode_closest_hint),
    )) {
        Card(Modifier.fillMaxWidth().selectable(selected = m == mode, role = Role.RadioButton, onClick = { onSelect(m) })) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = m == mode, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlayersStep(state: NewGameState, vm: NewGameViewModel) {
    var editing by remember { mutableStateOf<GamePlayer?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var pickingProfile by rememberSaveable { mutableStateOf(false) }
    var recoloring by remember { mutableStateOf<ProfileEntity?>(null) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val available = profiles.filter { p -> state.players.none { it.profileId == p.id } }

    if (state.players.isNotEmpty()) SeatList(state.players, onMove = vm::movePlayer, onEdit = { editing = it }, onRemove = vm::removePlayer)
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { adding = true }, enabled = state.canAddPlayer) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.players_add_guest))
                }
                if (available.isNotEmpty()) {
                    OutlinedButton(onClick = { pickingProfile = true }, enabled = state.canAddPlayer) {
                        Text(stringResource(Res.string.players_add_saved))
                    }
                }
            }
            // Own row, so the button keeps its natural height (feedback round 2).
            OutlinedButton(onClick = vm::shuffleSeats, enabled = state.players.size > 1) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.players_shuffle))
            }
            // The earliest point where the QR code (and so remote play) can start (SPEC v3; D-101).
            OutlinedButton(onClick = { remoteOpen = true }) {
                Icon(painterResource(Res.drawable.ic_qr_code), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.remote_button))
            }
        }
    }
    if (remoteOpen) RemotePlayDialog(onDismiss = { remoteOpen = false })

    if (pickingProfile) {
        AlertDialog(
            onDismissRequest = { pickingProfile = false },
            title = { Text(stringResource(Res.string.players_add_saved)) },
            text = {
                Column {
                    for (p in available) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                pickingProfile = false
                                if (!vm.addProfile(p)) recoloring = p
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayerAvatar(p.asGamePlayer(), 40.dp)
                            Text(p.name, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingProfile = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    recoloring?.let { profile ->
        // The saved color is used by someone else: pick another one for this game only.
        PlayerEditorDialog(
            player = profile.asGamePlayer(),
            takenColors = state.players.map { it.colorIndex }.toSet(),
            onDismiss = { recoloring = null },
            onSave = { name, initials, color -> vm.addProfileWithColor(profile, name, initials, color); recoloring = null },
        )
    }

    val target = editing
    if (adding || target != null) {
        PlayerEditorDialog(
            player = target,
            takenColors = state.players.filter { it.id != target?.id }.map { it.colorIndex }.toSet(),
            onDismiss = { adding = false; editing = null },
            onSave = { name, initials, color ->
                vm.savePlayer(target?.id, name, initials, color)
                adding = false
                editing = null
            },
        )
    }
}

/** Seats in order; long-press and drag a row to reorder. Seat 1 starts. */
@Composable
private fun SeatList(players: List<GamePlayer>, onMove: (Int, Int) -> Unit, onEdit: (GamePlayer) -> Unit, onRemove: (String) -> Unit) {
    val rowHeight = 72.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val current by rememberUpdatedState(players)

    Column {
        players.forEachIndexed { index, player ->
            androidx.compose.runtime.key(player.id) {
                val dragging = draggingId == player.id
                Card(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .padding(vertical = 4.dp)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) offset else 0f; shadowElevation = if (dragging) 12f else 0f }
                        .clickable { onEdit(player) },
                ) {
                    Row(Modifier.fillMaxSize().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Drag the handle right away, no long press (feedback round 10); the rest of the
                        // row still scrolls the page and opens the editor.
                        Box(
                            Modifier.size(48.dp).pointerInput(player.id) {
                                detectDragGestures(
                                    onDragStart = { draggingId = player.id; offset = 0f },
                                    onDragEnd = { draggingId = null; offset = 0f },
                                    onDragCancel = { draggingId = null; offset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        offset += amount.y
                                        val i = current.indexOfFirst { it.id == player.id }
                                        if (offset > rowPx / 2 && i < current.lastIndex) { onMove(i, i + 1); offset -= rowPx }
                                        else if (offset < -rowPx / 2 && i > 0) { onMove(i, i - 1); offset += rowPx }
                                    },
                                )
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(Res.string.players_drag_hint))
                        }
                        Text(
                            stringResource(Res.string.players_seat, index + 1),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        PlayerAvatar(player, 40.dp)
                        Text(player.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        IconButton(onClick = { onRemove(player.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.players_remove, player.name))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PoolStep(common: CommonSettings, playlists: List<PlaylistWithCount>, pool: PoolInfo?, needed: Int, onChange: (CommonSettings) -> Unit) {
    // Included playlists ("All songs" by default) minus every song on an excluded playlist (DECISIONS.md D-065).
    val included = common.playlistIds?.toSet()
    val excluded = common.excludedPlaylistIds.toSet()
    SectionCard(stringResource(Res.string.pool_include), spacing = 0.dp) {
        CheckRow(stringResource(Res.string.playlist_all), included == null) { onChange(common.copy(playlistIds = null)) }
        for (p in playlists) {
            val checked = included != null && p.id in included
            CheckRow(stringResource(Res.string.name_with_detail, playlistName(p), pluralStringResource(Res.plurals.library_songs, p.songs, p.songs)), checked) {
                val next = if (checked) included.orEmpty() - p.id else included.orEmpty() + p.id
                onChange(common.copy(playlistIds = next.toList().takeIf { it.isNotEmpty() }, excludedPlaylistIds = (excluded - p.id).toList()))
            }
        }
        if (playlists.isEmpty()) Text(stringResource(Res.string.pool_playlists_hint), style = MaterialTheme.typography.bodySmall)
    }
    if (playlists.isNotEmpty()) {
        SectionCard(stringResource(Res.string.pool_exclude), spacing = 0.dp) {
            Text(stringResource(Res.string.pool_exclude_hint), style = MaterialTheme.typography.bodySmall)
            for (p in playlists) {
                val checked = p.id in excluded
                CheckRow(playlistName(p), checked) {
                    val next = if (checked) excluded - p.id else excluded + p.id
                    onChange(common.copy(excludedPlaylistIds = next.toList(), playlistIds = included?.minus(p.id)?.toList()?.takeIf { it.isNotEmpty() }))
                }
            }
        }
    }
    SectionCard(stringResource(Res.string.pool_year_filter)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YearField(stringResource(Res.string.pool_year_from), common.yearFrom, Modifier.weight(1f)) { onChange(common.copy(yearFrom = it)) }
            YearField(stringResource(Res.string.pool_year_to), common.yearTo, Modifier.weight(1f)) { onChange(common.copy(yearTo = it)) }
        }
    }
    // The songs the game draws from, and what they look like: artists, years and songs per year (feedback round 28).
    SectionCard(if (pool == null) stringResource(Res.string.pool_counting) else pluralStringResource(Res.plurals.pool_count, pool.size, pool.size)) {
        if (pool != null) {
            if (pool.size < needed) Text(stringResource(Res.string.pool_too_small, needed), color = MaterialTheme.colorScheme.error)
            if (pool.streamed > 0) Text(pluralStringResource(Res.plurals.pool_streamed, pool.streamed, pool.streamed), style = MaterialTheme.typography.bodyMedium)
            if (pool.size > 0) StatsContent(pool.stats)
        }
    }
}

@Composable
private fun YearField(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let(::formatYear).orEmpty()) }
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
        placeholder = { Text(stringResource(Res.string.pool_year_any)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
