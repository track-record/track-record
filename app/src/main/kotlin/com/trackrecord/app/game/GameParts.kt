package com.trackrecord.app.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.remote.RemotePlayDialog
import com.trackrecord.app.ui.components.AppSheet
import com.trackrecord.app.ui.components.Card
import com.trackrecord.app.ui.components.FilledIconButton
import com.trackrecord.app.ui.components.FilledTonalIconButton
import com.trackrecord.app.ui.components.OptionalStepper
import com.trackrecord.app.ui.components.Stepper
import com.trackrecord.app.ui.components.SwitchRow
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.app.ui.components.formatDuration
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.app.ui.components.rememberSongCover
import com.trackrecord.app.ui.theme.LocalCardColors
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.Phase
import com.trackrecord.playback.AudioState
import kotlinx.coroutines.launch
import com.trackrecord.engine.Card as EngineCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameTopBar(title: String, session: GameSession, onLeave: () -> Unit, onScoreboard: () -> Unit, onAction: (GameAction) -> Unit) {
    var remoteOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var settingsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var infoOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onLeave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.game_leave_title)) }
        },
        actions = {
            IconButton(onClick = { remoteOpen = true }) { Icon(painterResource(R.drawable.ic_qr_code), stringResource(R.string.remote_title)) }
            // The rules, settings and playlists of this game (feedback round 36).
            IconButton(onClick = { infoOpen = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.info_title)) }
            IconButton(onClick = onScoreboard) { Icon(painterResource(R.drawable.ic_scoreboard), stringResource(R.string.scoreboard_title)) }
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, stringResource(R.string.settings_title)) }
        },
    )
    if (remoteOpen) RemotePlayDialog(onDismiss = { remoteOpen = false })
    if (settingsOpen) GameSettingsSheet(session, onDismiss = { settingsOpen = false }, onAction = onAction)
    if (infoOpen) InfoSheet(session, onDismiss = { infoOpen = false })
}

/**
 * Settings during a game (feedback round 26): the clip length, also for the song playing now (D-191),
 * and "Tap a gap twice to confirm". Guests have the same on their phones; cover art, being cosmetic,
 * stays in Settings (and in the guests' player menu).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSettingsSheet(session: GameSession, onDismiss: () -> Unit, onAction: (GameAction) -> Unit) {
    val container = LocalContext.current.appContainer
    val repo = container.settings
    val app by repo.appSettings.collectAsStateWithLifecycle(initialValue = null)
    val requests by container.game.requests.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val state = session.state
    AppSheet(stringResource(R.string.settings_title), onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (state.phase != Phase.GAME_OVER) {
                OptionalStepper(
                    stringResource(R.string.settings_clip_limit),
                    state.settings.common.clipLimitSeconds,
                    10..120,
                    { onAction(GameAction.SetClipLimit(it)) },
                    step = 5,
                    unit = { pluralStringResource(R.plurals.seconds, it, it) },
                )
                // From the next song: the one playing now keeps its place (feedback round 34).
                Stepper(
                    stringResource(R.string.settings_start_offset),
                    state.settings.common.startOffsetSeconds,
                    0..60,
                    { onAction(GameAction.SetStartOffset(it)) },
                    step = 5,
                    hint = stringResource(R.string.start_offset_next_song),
                    format = { pluralStringResource(R.plurals.seconds, it, it) },
                )
            }
            app?.let { s ->
                SwitchRow(
                    stringResource(R.string.settings_confirm_twice),
                    s.confirmByTappingTwice,
                    { on -> scope.launch { repo.update { it.copy(confirmByTappingTwice = on) } } },
                    hint = stringResource(R.string.settings_confirm_twice_hint) + " " + stringResource(R.string.settings_confirm_twice_guests),
                )
            }
            if (state.settings.common.musicBrainzLookups) {
                GameRequestsPanel(requests, onStop = container.game::stopRequests, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/**
 * Play/pause and replay-from-start-offset with the clip progress. Nothing identifying is shown.
 * On the reveal screen [onSeek] makes the bar draggable and [onFullSong] offers the whole song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackControls(
    audio: AudioState,
    onToggle: () -> Unit,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
    onSeek: ((Long) -> Unit)? = null,
    onFullSong: (() -> Unit)? = null,
    /** v2: a Deezer preview plays ("full song" = the whole preview). */
    preview: Boolean = false,
    /** A streamed song: open the whole track in Deezer (feedback round 21). */
    onOpenInDeezer: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Play and replay stay round whatever the button radius (feedback round 7).
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp), enabled = audio.loaded, shape = CircleShape) {
                if (audio.isPlaying) Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.playback_pause), Modifier.size(36.dp))
                else Icon(Icons.Default.PlayArrow, stringResource(R.string.playback_play), Modifier.size(36.dp))
            }
            FilledTonalIconButton(onClick = onReplay, modifier = Modifier.size(56.dp), enabled = audio.loaded, shape = CircleShape) {
                Icon(painterResource(R.drawable.ic_replay), stringResource(R.string.playback_replay))
            }
            Column(Modifier.weight(1f)) {
                val duration = audio.clipDurationMs
                val progress = if (duration > 0) (audio.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
                if (onSeek != null && duration > 0) {
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    val seekLabel = stringResource(R.string.playback_seek)
                    Slider(
                        value = dragging ?: if (audio.ended) 1f else progress,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { onSeek((it * duration).toLong()) }
                            dragging = null
                        },
                        modifier = Modifier.semantics { contentDescription = seekLabel },
                        // No stop dot at the end of the track (feedback round 3); half the default gap
                        // around the thumb, which looked like a thick outline (feedback round 8).
                        track = { state -> SliderDefaults.Track(sliderState = state, drawStopIndicator = null, thumbTrackGapSize = 3.dp) },
                    )
                    Text(
                        stringResource(R.string.playback_time, formatDuration(((dragging ?: progress) * duration).toLong()), formatDuration(duration)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    // No stop dot at the end: it looked like an already-played part of the song.
                    LinearProgressIndicator(progress = { if (audio.ended) 1f else progress }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                }
                // Only when it can't play: "Clip finished" and "Preview from Deezer" were noise (feedback round 43).
                if (audio.error) Text(stringResource(R.string.playback_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        val fullSong = onFullSong != null && audio.clipped && !audio.fullSong
        if (fullSong || onOpenInDeezer != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fullSong) TextButton(onClick = onFullSong!!) { Text(stringResource(if (preview) R.string.playback_full_preview else R.string.playback_full_song)) }
                if (onOpenInDeezer != null) TextButton(onClick = onOpenInDeezer) { Text(stringResource(R.string.playback_open_deezer)) }
            }
        }
    }
}

/**
 * v2: what happened to streamed songs that couldn't be played this round, and the host's way out when
 * the app couldn't replace one by itself.
 */
@Composable
fun UnplayableNotice(replaced: Int, unplayable: Boolean, onReplace: () -> Unit) {
    if (replaced > 0) {
        Text(pluralStringResource(R.plurals.songs_replaced, replaced, replaced), style = MaterialTheme.typography.bodySmall)
    }
    if (unplayable) {
        Text(stringResource(R.string.song_unplayable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onReplace) { Text(stringResource(R.string.song_replace)) }
    }
}

/**
 * A revealed card in a timeline: year large, title and artist small, filled with the song's cover
 * (MusicBrainz first, then embedded) behind a dark gradient, or the theme's card colour without a cover.
 */
@Composable
fun TimelineCard(card: EngineCard, info: SongInfo?, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val cover = rememberSongCover(card.songId)
    val cardColors = LocalCardColors.current
    val textColor = if (cover != null) Color.White else cardColors.text
    Card(
        modifier.fillMaxWidth(),
        border = if (highlight) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.35f)))))
            } else {
                Box(Modifier.matchParentSize().background(cardColors.fillFor(card.songId)))
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatYear(card.year), style = MaterialTheme.typography.headlineMedium, color = textColor, modifier = Modifier.width(96.dp))
                Column(Modifier.weight(1f)) {
                    Text(info?.title.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(info?.artist ?: stringResource(R.string.unknown_artist), style = MaterialTheme.typography.bodySmall, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * A titled card on the result screens: the title close to the top edge with a little room below it
 * (feedback round 21). With [onToggle] the title row opens and closes the card's [content].
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        // The header's tap area reaches the card's edges, so a closed card is one big button (feedback round 43).
        val header = Modifier.fillMaxWidth().then(
            if (onToggle != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.section_collapse else R.string.section_expand), onClick = onToggle) else Modifier,
        )
        Row(header.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onToggle != null) Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

/** A bought card, or a skipped song (feedback round 27), is shown to everyone until dismissed. */
@Composable
fun SongEventDialogs(session: GameSession) {
    val state = session.state
    state.lastPurchase?.let { p ->
        key(p) { SongDialog(session, stringResource(R.string.purchase_title, session.player(p.playerId).name), p.card) }
    }
    state.lastSkip?.let { p ->
        key(p) { SongDialog(session, stringResource(R.string.skip_title, session.player(p.playerId).name), p.card) }
    }
}

@Composable
private fun SongDialog(session: GameSession, title: String, card: EngineCard) {
    var shown by remember { mutableStateOf(true) }
    if (!shown) return
    val onDismiss = { shown = false }
    val info = session.song(card.songId)
    // The song as a card, as on guest phones (DESIGN-REVIEW LY-03).
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimelineCard(card, info) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

/** Cover for the reveal screen: MusicBrainz first, then embedded (null if none). */
@Composable
fun rememberCover(info: SongInfo?): ImageBitmap? = rememberSongCover(info?.songId)

/** The game's banners: a tap dismisses one (feedback round 49). */
@Composable
fun GameSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(state) { data -> Snackbar(data, Modifier.clickable { data.dismiss() }) }
}
