package com.trackrecord.app.game

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.library.YearSourcesTable
import com.trackrecord.app.remote.RemoteState
import com.trackrecord.app.ui.components.BottomBarSurface
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.Card
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.YearEditorInline
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.app.ui.theme.LocalComponentShape
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.NamingCallers
import com.trackrecord.engine.Slots
import com.trackrecord.engine.TimelineOutcome
import com.trackrecord.remote.OutcomeTone
import com.trackrecord.remote.OutcomeTones
import kotlin.math.abs

@Composable
fun ResultScreen(session: GameSession, vm: GameViewModel, snackbar: SnackbarHostState, onLeave: () -> Unit) {
    val state = session.state
    val round = state.round ?: return
    val info = session.song(round.song.songId)
    val audio by vm.audio.collectAsStateWithLifecycle()
    var yearSheet by rememberSaveable { mutableStateOf(false) }
    var scoreboard by rememberSaveable { mutableStateOf(false) }
    val cover = rememberCover(info)
    val context = LocalContext.current
    // Timeline: the guesses section starts open unless the scoreboard then no longer fits on screen; decided
    // once per round after the first layout, while the screen is still invisible (feedback round 21).
    val timelineMode = state.mode == GameMode.TIMELINE
    var guessesOpen by rememberSaveable(round.number) { mutableStateOf<Boolean?>(null) }
    var viewportBottom by remember { mutableStateOf<Float?>(null) }
    var scoreboardBottom by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(viewportBottom, scoreboardBottom) {
        val bottom = viewportBottom
        val board = scoreboardBottom
        if (guessesOpen == null && bottom != null && board != null) guessesOpen = board <= bottom
    }

    Scaffold(
        topBar = { GameTopBar(stringResource(R.string.round_title, round.number), session, onLeave, onScoreboard = { scoreboard = true }, onAction = vm::dispatch) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            BottomBarSurface {
                Button(
                    onClick = { vm.dispatch(GameAction.Next) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(60.dp),
                ) { Text(stringResource(R.string.action_next), style = MaterialTheme.typography.titleLarge) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .onGloballyPositioned { viewportBottom = it.boundsInRoot().bottom }
                .alpha(if (timelineMode && guessesOpen == null) 0f else 1f)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // One card like the guests' reveal: cover, year, song, outcome (feedback round 16). Outlined from the
            // view of the one player on this phone when all others play on guest phones; else not at all.
            val remoteState by LocalContext.current.appContainer.remote.state.collectAsStateWithLifecycle()
            val onGuestPhones = (remoteState as? RemoteState.On)?.clients?.claimed.orEmpty().filterValues { it }.keys
            val tone = OutcomeTones.forHost(state, onGuestPhones)
            Card(Modifier.fillMaxWidth(), border = tone?.let { BorderStroke(3.dp, it.color) }) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (cover != null) {
                        Image(cover, contentDescription = stringResource(R.string.result_cover), contentScale = ContentScale.Crop, modifier = Modifier.size(160.dp).clip(LocalComponentShape.current))
                    }
                    // The year is centred on its own; the pencil sits to its right (feedback round 6).
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Text(formatYear(round.song.year), style = MaterialTheme.typography.displayLarge)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            IconButton(onClick = { yearSheet = !yearSheet }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.result_year_details))
                            }
                        }
                    }
                    SourcesLine(info, round.song.year, round.yearCorrected)
                    Text(info?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(info?.artist ?: stringResource(R.string.unknown_artist), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    info?.album?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(4.dp))
                    when (state.mode) {
                        GameMode.TIMELINE -> TimelineOutcomeView(session)
                        GameMode.CLOSEST_YEAR -> RoundWinners(session)
                    }
                }
            }
            if (yearSheet) {
                // In the screen itself, not a bottom sheet: on Felix's phone the sheet window stuck
                // halfway behind the system bars (D-091).
                YearDetailsCard(
                    songId = round.song.songId,
                    roundYear = round.song.year,
                    currentYear = state.currentYear,
                    onClose = { yearSheet = false },
                    onSave = { y -> vm.correctYear(y); yearSheet = false },
                )
            }
            // The host's own part: who named song and artist.
            val naming = (state.mode == GameMode.TIMELINE && state.settings.timeline.namingBonus) ||
                (state.mode == GameMode.CLOSEST_YEAR && state.settings.closestYear.namingEarnsSkip)
            if (naming) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.mode == GameMode.TIMELINE && state.settings.timeline.namingBonus) {
                        // Part of the outcome card instead of a separate switch row (feedback round 6).
                        // Everyone allowed to call out, in calling order; one can be marked (feedback round 19).
                        val namer = round.namedById
                        val candidates = GameEngine.namingCandidates(state)
                        if (candidates.size > 1) Text(stringResource(R.string.result_named_who), style = MaterialTheme.typography.titleSmall)
                        for (id in candidates) {
                            val named = namer == id
                            NamedToggle(stringResource(R.string.result_named, session.player(id).name), named) { vm.dispatch(GameAction.SetNamedBy(id, !named)) }
                        }
                        Text(
                            stringResource(
                                when (state.settings.timeline.namingCallers) {
                                    NamingCallers.ACTIVE -> R.string.result_named_hint
                                    NamingCallers.VETOERS -> R.string.result_named_hint_vetoers
                                    NamingCallers.ALL -> R.string.result_named_hint_all
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.mode == GameMode.CLOSEST_YEAR && state.settings.closestYear.namingEarnsSkip) {
                        // One toggle per player in guessing order; only one can have named it (feedback rounds 11, 12).
                        Text(stringResource(R.string.result_named_who), style = MaterialTheme.typography.titleSmall)
                        for (id in round.guesses.map { it.playerId }) {
                            val named = round.namedById == id
                            NamedToggle(stringResource(R.string.result_named, session.player(id).name), named) { vm.dispatch(GameAction.SetNamedBy(id, !named)) }
                        }
                        Text(stringResource(R.string.result_named_skip_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            val deezerUrl = info?.deezerUrl
            PlaybackControls(
                audio, vm::togglePlay, vm::replay, onSeek = vm::seekTo, onFullSong = { vm.playFullSong() },
                preview = info?.streamed == true,
                onOpenInDeezer = deezerUrl?.let { url ->
                    {
                        if (audio.isPlaying) vm.togglePlay()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                },
            )
            // Timeline: the active player's timeline with the guess, the vetoes and where the song fits (feedback round 21).
            if (timelineMode) {
                SectionCard(stringResource(R.string.result_guesses), expanded = guessesOpen != false, onToggle = { guessesOpen = guessesOpen == false }) {
                    RevealedTimeline(session)
                }
            }
            // Closest Year: the guesses on the axis with the song's year, then the list (as on the guests' phones), below playback (feedback round 17).
            if (state.mode == GameMode.CLOSEST_YEAR) {
                GuessAxis(session, state.settings.closestYear.lowestGuessableYear, state.currentYear, revealedYear = round.song.year)
                SectionCard(stringResource(R.string.result_guesses)) { ClosestOutcomeView(session) }
            }

            ScoreboardCard(session, Modifier.onGloballyPositioned { scoreboardBottom = it.positionInRoot().y + it.size.height })
        }
    }

    if (scoreboard) ScoreboardSheet(session, onDismiss = { scoreboard = false }, onAction = vm::dispatch)
}

/**
 * The active player's timeline as it was revealed: the gap they chose, each veto, and in green the
 * gap(s) where the song fits. Gaps nobody chose are left out to keep it short (feedback round 21).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RevealedTimeline(session: GameSession) {
    val state = session.state
    val round = state.round ?: return
    val timeline = state.activePlayer.timeline
    val active = session.player(state.activePlayer.id)
    val year = round.song.year
    val fitsColor = OutcomeTone.WON.color
    val shape = LocalComponentShape.current
    for (slot in 0..timeline.size) {
        if (Slots.isSelectable(timeline, slot)) {
            val guess = round.lockedSlot == slot
            val vetoes = round.vetoes.filter { it.slot == slot }
            val fits = Slots.isValid(timeline, slot, year)
            if (guess || vetoes.isNotEmpty() || fits) {
                FlowRow(
                    Modifier.fillMaxWidth()
                        .border(if (fits) BorderStroke(2.dp, fitsColor) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (guess) GuessChip(active, stringResource(R.string.result_guess_chip))
                    for (v in vetoes) GuessChip(session.player(v.playerId), stringResource(R.string.gap_veto))
                    if (fits) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = fitsColor, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.result_fits_here, formatYear(year)), color = fitsColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        if (slot < timeline.size) TimelineCard(timeline[slot], session.song(timeline[slot].songId))
    }
}

@Composable
private fun GuessChip(player: GamePlayer, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayerAvatar(player, 28.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * "<player> named song and artist": full width, the text centred and the check mark on the right,
 * so toggling doesn't move the text (feedback round 8).
 */
@Composable
private fun NamedToggle(label: String, selected: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        checked = selected,
        onCheckedChange = { onToggle() },
        shape = LocalComponentShape.current,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 40.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
        if (selected) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            }
        }
    }
}

/** All year sources of the revealed song (as in the library), with "Edit year" for a correction. */
@Composable
private fun YearDetailsCard(songId: Long, roundYear: Int, currentYear: Int, onClose: () -> Unit, onSave: (Int) -> Unit) {
    val library = LocalContext.current.appContainer.library
    val song by library.song(songId).collectAsStateWithLifecycle(initialValue = null)
    var editing by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.year_sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
            }
            Text(stringResource(R.string.year_sheet_round, formatYear(roundYear)), style = MaterialTheme.typography.bodyLarge)
            if (editing) {
                YearEditorInline(initialYear = roundYear, currentYear = currentYear, onCancel = { editing = false }, onConfirm = onSave)
            } else {
                song?.let { YearSourcesTable(it) }
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text(stringResource(R.string.song_edit_year), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** When tags and MusicBrainz disagree, both appear under the big year ("Tag 2011 · MusicBrainz 1975"). */
@Composable
private fun SourcesLine(info: SongInfo?, year: Int, corrected: Boolean) {
    if (corrected) {
        Text(stringResource(R.string.result_corrected), style = MaterialTheme.typography.bodySmall)
        return
    }
    val base = info?.baseYear
    val mb = info?.musicBrainzYear
    if (base != null && mb != null && base != mb) {
        Text(stringResource(R.string.result_sources, formatYear(base), formatYear(mb)), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TimelineOutcomeView(session: GameSession) {
    val round = session.state.round ?: return
    when (val o = round.timelineOutcome) {
        is TimelineOutcome.Kept -> OutcomeLine(session.player(o.playerId), stringResource(R.string.outcome_kept, session.player(o.playerId).name))
        is TimelineOutcome.Stolen -> OutcomeLine(session.player(o.playerId), stringResource(R.string.outcome_stolen, session.player(o.playerId).name))
        TimelineOutcome.Discarded, null -> Text(stringResource(R.string.outcome_discarded), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    if (round.vetoes.isNotEmpty() && round.timelineOutcome is TimelineOutcome.Kept) {
        Text(stringResource(R.string.outcome_vetoes_lost), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Closest Year: who won the round; several on a tie that both score (feedback round 36). */
@Composable
private fun RoundWinners(session: GameSession) {
    val winners = session.state.round?.winnerIds.orEmpty().map(session::player)
    when (winners.size) {
        0 -> Text(stringResource(R.string.outcome_nobody_won), style = MaterialTheme.typography.titleMedium)
        1 -> OutcomeLine(winners[0], stringResource(R.string.outcome_round_won, winners[0].name))
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { winners.forEach { PlayerAvatar(it, 32.dp) } }
            Text(
                stringResource(R.string.outcome_round_won_shared, winners.joinToString(stringResource(R.string.list_separator)) { it.name }),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ClosestOutcomeView(session: GameSession) {
    val round = session.state.round ?: return
    for (g in round.guesses) {
        val p = session.player(g.playerId)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerAvatar(p, 32.dp)
            Text(p.name, modifier = Modifier.weight(1f))
            // A fixed-width column for "x years off", so the years line up (feedback round 15).
            Text(formatYear(g.year), style = MaterialTheme.typography.titleMedium)
            Text(
                abs(g.year - round.song.year).let { d -> pluralStringResource(R.plurals.years_off, d, d) },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(96.dp),
            )
            // Rank and Distance: the points this guess earns (feedback round 36).
            round.points[g.playerId]?.let { points ->
                Text(
                    stringResource(R.string.scoreboard_gained, points),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(36.dp),
                )
            }
        }
    }
}

@Composable
private fun OutcomeLine(player: GamePlayer, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerAvatar(player, 32.dp)
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** Outline colours of the reveal card: green won, red lost, orange someone else's success (as on guest phones). */
private val OutcomeTone.color: Color
    get() = when (this) {
        OutcomeTone.WON -> Color(0xFF43A047)
        OutcomeTone.OTHER -> Color(0xFFF57C00)
        OutcomeTone.LOST -> Color(0xFFE53935)
    }
