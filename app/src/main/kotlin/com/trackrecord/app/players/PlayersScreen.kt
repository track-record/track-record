package com.trackrecord.app.players

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.game.Initials
import com.trackrecord.app.newgame.NameAndInitialsFields
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.ColorPicker
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.app.ui.theme.LocalButtonShape
import com.trackrecord.data.db.ProfileEntity
import kotlinx.coroutines.launch

fun ProfileEntity.asGamePlayer() = GamePlayer(
    id = "p-$id",
    name = name,
    initials = initials,
    colorIndex = colorIndex,
    photoPath = photoPath,
    profileId = id,
)

/** Saved players; a tap opens their stats (feedback round 47), where they are edited too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(onBack: () -> Unit, onEdit: (Long?) -> Unit, onStats: (Long) -> Unit) {
    val repo = LocalContext.current.appContainer.profiles
    val profiles by repo.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.players_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
        floatingActionButton = {
            // The buttons' corner radius, like every other button (feedback round 26).
            ExtendedFloatingActionButton(onClick = { onEdit(null) }, shape = LocalButtonShape.current, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.profiles_new)) })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (profiles.isEmpty()) {
                item { Text(stringResource(R.string.profiles_empty), modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            items(profiles, key = { it.id }) { p ->
                ListItem(
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.player_stats_open, p.name)) { onStats(p.id) },
                    leadingContent = { PlayerAvatar(p.asGamePlayer(), 48.dp) },
                    headlineContent = { Text(p.name, style = MaterialTheme.typography.titleMedium) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** Create or edit a profile: name, initials, color and an optional photo (camera or gallery). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(profileId: Long?, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = context.appContainer.profiles
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
        if (photo != savedPhoto) ProfilePhotos.discard(photo)
        photo = path
    }
    // A new photo first goes through the crop dialog (feedback round 16).
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) cropping = bitmap
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { cropping = ProfilePhotos.decode(context, uri) }
    }
    val cancel = {
        if (photo != savedPhoto) ProfilePhotos.discard(photo)
        onDone()
    }
    androidx.activity.compose.BackHandler(onBack = cancel)

    cropping?.let { bitmap ->
        PhotoCropDialog(
            bitmap,
            onCancel = { cropping = null },
            onDone = { rect ->
                cropping = null
                scope.launch { ProfilePhotos.save(context, bitmap, rect)?.let(::setPhoto) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (profileId == null) R.string.profiles_new else R.string.profiles_edit)) },
                navigationIcon = { IconButton(onClick = cancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (profileId != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.profiles_delete)) }
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlayerAvatar(GamePlayer("preview", name, initials.ifBlank { "?" }, color, photo), 96.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { camera.launch(null) }) { Text(stringResource(R.string.profiles_take_photo)) }
                    OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(stringResource(R.string.profiles_choose_photo))
                    }
                    if (photo != null) TextButton(onClick = { setPhoto(null) }) { Text(stringResource(R.string.profiles_remove_photo)) }
                }
            }
            NameAndInitialsFields(
                name = name,
                initials = initials,
                onName = { name = it; if (!initialsEdited) initials = Initials.from(it) },
                onInitials = { initials = it; initialsEdited = true },
            )
            Text(stringResource(R.string.player_color), style = MaterialTheme.typography.labelLarge)
            ColorPicker(selected = color, taken = emptySet(), onSelect = { color = it })
            Button(
                onClick = {
                    scope.launch {
                        repo.save(profileId, name, initials, color, photo)
                        onDone()
                    }
                },
                enabled = name.isNotBlank() && initials.trim().length in 1..3,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }

    if (confirmDelete && profileId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.profiles_delete_title, name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (photo != savedPhoto) ProfilePhotos.discard(photo)
                    scope.launch { repo.delete(profileId); onDone() }
                }) { Text(stringResource(R.string.profiles_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
