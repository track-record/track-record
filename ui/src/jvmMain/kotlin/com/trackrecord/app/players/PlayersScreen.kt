package com.trackrecord.app.players

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.game.Initials
import com.trackrecord.app.newgame.NameAndInitialsFields
import com.trackrecord.app.platform.LocalPlatformUi
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_back
import com.trackrecord.app.resources.action_cancel
import com.trackrecord.app.resources.action_save
import com.trackrecord.app.resources.player_color
import com.trackrecord.app.resources.player_stats_open
import com.trackrecord.app.resources.players_title
import com.trackrecord.app.resources.profiles_delete
import com.trackrecord.app.resources.profiles_delete_title
import com.trackrecord.app.resources.profiles_edit
import com.trackrecord.app.resources.profiles_empty
import com.trackrecord.app.resources.profiles_new
import com.trackrecord.app.resources.profiles_remove_photo
import com.trackrecord.app.ui.components.BackHandler
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.Card
import com.trackrecord.app.ui.components.ColorPicker
import com.trackrecord.app.ui.components.ListItem
import com.trackrecord.app.ui.components.PanelGap
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.app.ui.components.panelRow
import com.trackrecord.app.ui.theme.LocalButtonShape
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Saved players; a tap opens their stats (feedback round 47), where they are edited too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(onBack: () -> Unit, onEdit: (Long?) -> Unit, onStats: (Long) -> Unit) {
    val repo = LocalAppContainer.current.profiles
    val profiles by repo.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.players_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) } },
            )
        },
        floatingActionButton = {
            // The buttons' corner radius, like every other button (feedback round 26).
            ExtendedFloatingActionButton(onClick = { onEdit(null) }, shape = LocalButtonShape.current, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(Res.string.profiles_new)) })
        },
    ) { padding ->
        // The players in one panel, as the other screens' lists (feedback round 58); room at the bottom for the button.
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp)) {
            if (profiles.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.profiles_empty), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            itemsIndexed(profiles, key = { _, p -> p.id }) { index, p ->
                Column(Modifier.panelRow(first = index == 0, last = index == profiles.lastIndex)) {
                    ListItem(
                        modifier = Modifier.clickable(onClickLabel = stringResource(Res.string.player_stats_open, p.name)) { onStats(p.id) },
                        leadingContent = { PlayerAvatar(p.asGamePlayer(), 48.dp) },
                        headlineContent = { Text(p.name, style = MaterialTheme.typography.titleMedium) },
                    )
                    if (index < profiles.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** Create or edit a profile: name, initials, color and an optional photo (camera or gallery). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(profileId: Long?, onDone: () -> Unit) {
    val repo = LocalAppContainer.current.profiles
    val photoButtons = LocalPlatformUi.current.photoButtons
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(profileId == null) }
    var name by rememberSaveable { mutableStateOf("") }
    var initials by rememberSaveable { mutableStateOf("") }
    var initialsEdited by rememberSaveable { mutableStateOf(false) }
    var color by rememberSaveable { mutableIntStateOf(0) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var savedPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        if (profileId != null && !loaded) {
            repo.get(profileId)?.let {
                name = it.name; initials = it.initials; initialsEdited = true; color = it.colorIndex; photo = it.photoPath; savedPhoto = it.photoPath
            }
            loaded = true
        }
    }

    fun setPhoto(path: String?) {
        if (photo != savedPhoto) discardPhoto(photo)
        photo = path
    }
    val cancel = {
        if (photo != savedPhoto) discardPhoto(photo)
        onDone()
    }
    BackHandler(onBack = cancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (profileId == null) Res.string.profiles_new else Res.string.profiles_edit)) },
                navigationIcon = { IconButton(onClick = cancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) } },
                actions = {
                    if (profileId != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(Res.string.profiles_delete)) }
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(PanelGap),
        ) {
            // The player in a panel, Save below it (feedback round 58).
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlayerAvatar(GamePlayer("preview", name, initials.ifBlank { "?" }, color, photo), 96.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Where the platform takes or chooses photos (both apps since feedback round 55).
                            photoButtons?.invoke(::setPhoto)
                            if (photo != null) TextButton(onClick = { setPhoto(null) }) { Text(stringResource(Res.string.profiles_remove_photo)) }
                        }
                    }
                    NameAndInitialsFields(
                        name = name,
                        initials = initials,
                        onName = { name = it; if (!initialsEdited) initials = Initials.from(it) },
                        onInitials = { initials = it; initialsEdited = true },
                    )
                    Text(stringResource(Res.string.player_color), style = MaterialTheme.typography.labelLarge)
                    ColorPicker(selected = color, taken = emptySet(), onSelect = { color = it })
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        repo.save(profileId, name, initials, color, photo)
                        onDone()
                    }
                },
                enabled = name.isNotBlank() && initials.trim().length in 1..3,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.action_save)) }
        }
    }

    if (confirmDelete && profileId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.profiles_delete_title, name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (photo != savedPhoto) discardPhoto(photo)
                    scope.launch { repo.delete(profileId); onDone() }
                }) { Text(stringResource(Res.string.profiles_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
