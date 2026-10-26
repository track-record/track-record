package com.trackrecord.app.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.remote.RemoteState
import com.trackrecord.app.remote.deviceName
import com.trackrecord.app.remote.remoteDevice
import com.trackrecord.app.ui.components.BottomBarSurface
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.PlayerChip
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.app.ui.theme.LocalComponentShape
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.Phase
import com.trackrecord.engine.Slots
import com.trackrecord.engine.reveal.RevealStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineRoundScreen(session: GameSession, vm: GameViewModel, snackbar: SnackbarHostState, onLeave: () -> Unit) {
    val state = session.state
    val round = state.round ?: return
    val rules = state.settings.timeline
    val active = session.player(state.activePlayer.id)
    val activeState = state.activePlayer
    val audio by vm.audio.collectAsStateWithLifecycle()
    val vetoer by vm.vetoer.collectAsStateWithLifecycle()
    val gate by vm.revealStatus.collectAsStateWithLifecycle()
    val pendingVeto by vm.pendingVeto.collectAsStateWithLifecycle()
    val appSettings = LocalContext.current.appContainer.settings.appSettings
    val twice by remember(appSettings) { appSettings.map { it.confirmByTappingTwice } }.collectAsStateWithLifecycle(initialValue = false)
    val remote by vm.remotePlayers.collectAsStateWithLifecycle()
    var scoreboard by rememberSaveable { mutableStateOf(false) }
    val placing = state.phase == Phase.PLACING
    // A connected guest phone places its own player's song; the host can't (feedback round 8).
    val remoteState by LocalContext.current.appContainer.remote.state.collectAsStateWithLifecycle()
    val connectedRemote = (remoteState as? RemoteState.On)?.clients?.claimed.orEmpty().filterValues { it }.keys
    val remoteTurn = placing && active.id in connectedRemote
    val activeDevice = deviceName(remoteDevice(active.id))
    val timeline = activeState.timeline

    Scaffold(
        topBar = { GameTopBar(stringResource(R.string.round_title, round.number), session, onLeave, onScoreboard = { scoreboard = true }, onAction = vm::dispatch) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            BottomBarSurface {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (placing) {
                        Button(
                            onClick = { vm.dispatch(GameAction.LockIn) },
                            enabled = round.selectedSlot != null && !remoteTurn,
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                        ) {
                            // The longer remote text in a smaller size, so it fits on one line.
                            Text(
                                if (remoteTurn) stringResource(R.string.timeline_remote_lock_in, active.name, activeDevice) else stringResource(R.string.timeline_lock_in),
                                style = if (remoteTurn) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else if (vetoer != null) {
                        // Mark a free gap, then Confirm (or tap it again with "tap twice"), as on guest phones (DESIGN-REVIEW IN-01).
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::cancelVeto, modifier = Modifier.weight(1f).height(60.dp)) {
                                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleLarge)
                            }
                            Button(onClick = vm::confirmVeto, enabled = pendingVeto != null, modifier = Modifier.weight(1f).height(60.dp)) {
                                Text(stringResource(R.string.action_confirm), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    } else {
                        // One player on this phone, the others on guest phones: the guests' Veto / Don't veto
                        // buttons (feedback round 16); with several players here, the veto bar.
                        val local = session.playing.map { it.id }.filter { it !in connectedRemote }
                        val single = local.singleOrNull()
                        if (single != null) {
                            SingleVetoChoice(session, single, vetoer, gate, onVeto = { vm.selectVetoer(single) }, onDecline = { vm.declineVeto(single) })
                        } else {
                            VetoBar(session, vetoer, remote, gate, connected = connectedRemote, onSelect = vm::selectVetoer)
                        }
                        // No Undo veto (feedback round 27). With one player on this phone, Reveal appears once
                        // they decided: Don't veto, or their veto placed.
                        val decided = single == null || single == state.activePlayer.id ||
                            single !in GameEngine.vetoEligible(state) || (gate != null && single in gate!!.declined)
                        if (decided) {
                            // Locked while players decide; counts down to auto-reveal (SPEC v3).
                            val status = gate
                            Button(
                                onClick = { vm.dispatch(GameAction.Reveal) },
                                enabled = status?.locked != true,
                                modifier = Modifier.fillMaxWidth().height(60.dp),
                            ) {
                                val autoIn = status?.autoRevealInMs
                                val label = when {
                                    status != null && status.picking.isNotEmpty() -> stringResource(R.string.reveal_waiting_veto)
                                    status != null && status.locked -> stringResource(R.string.reveal_locked, seconds(status.lockedForMs))
                                    autoIn != null && autoIn > 0 -> stringResource(R.string.reveal_auto, seconds(autoIn))
                                    else -> stringResource(R.string.action_reveal)
                                }
                                Text(label, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerChip(
                    active,
                    label = pluralStringResource(R.plurals.vetoes, activeState.vetoes, activeState.vetoes),
                    avatarSize = 52.dp,
                    modifier = Modifier.weight(1f),
                )
                // Skip and Buy wrap together, never one under the other (feedback round 43).
                val skipCost = rules.skipCost
                val buyCost = rules.buyCost
                if (placing && !remoteTurn && (skipCost != null || buyCost != null)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (skipCost != null) {
                            OutlinedButton(onClick = { vm.dispatch(GameAction.Skip) }, enabled = activeState.vetoes >= skipCost) {
                                Text(stringResource(R.string.timeline_skip, pluralStringResource(R.plurals.vetoes, skipCost, skipCost)))
                            }
                        }
                        // Buying a card beside Skip, as on guest phones (DESIGN-REVIEW IN-02); for everyone in the scoreboard.
                        if (buyCost != null) {
                            OutlinedButton(onClick = { vm.dispatch(GameAction.BuyCard(active.id)) }, enabled = activeState.vetoes >= buyCost) {
                                Text(stringResource(R.string.scoreboard_buy, pluralStringResource(R.plurals.vetoes, buyCost, buyCost)))
                            }
                        }
                    }
                }
            }
            PlaybackControls(audio, vm::togglePlay, vm::replay, preview = session.song(round.song.songId)?.streamed == true)
            UnplayableNotice(
                replaced = vm.replacedThisRound.collectAsStateWithLifecycle().value,
                unplayable = vm.unplayable.collectAsStateWithLifecycle().value || (audio.error && session.song(round.song.songId)?.streamed == true),
                onReplace = vm::replaceUnplayable,
            )
            val waiting = gate?.waitingFor.orEmpty()
            Text(
                if (remoteTurn) stringResource(R.string.timeline_remote_turn, active.name, activeDevice)
                else if (placing) stringResource(if (twice) R.string.timeline_place_hint_twice else R.string.timeline_place_hint, active.name)
                else if (vetoer != null) stringResource(if (twice) R.string.timeline_veto_pick_gap_twice else R.string.timeline_veto_pick_gap, session.player(vetoer!!).name)
                else if (gate?.picking.orEmpty().size == 1) stringResource(R.string.timeline_remote_picking, session.player(gate!!.picking.first()).name, deviceName(remoteDevice(gate!!.picking.first())))
                else if (gate?.picking.orEmpty().isNotEmpty()) stringResource(R.string.timeline_remote_picking_many, gate!!.picking.joinToString(stringResource(R.string.list_separator)) { session.player(it).name })
                else if (waiting.isNotEmpty()) stringResource(R.string.timeline_waiting_remote, waiting.joinToString(stringResource(R.string.list_separator)) { session.player(it).name })
                else stringResource(R.string.timeline_veto_hint),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            val listState = rememberLazyListState()
            val focusSlot = round.selectedSlot
            var dragging by remember { mutableStateOf(false) }
            LaunchedEffect(focusSlot) {
                // While dragging, the finger moves the list (dragToPlace scrolls at the edges).
                if (focusSlot == null || dragging) return@LaunchedEffect
                // List items: every card plus the visible gaps; keep the card above the chosen gap in view too.
                val gapIndex = focusSlot + (0 until focusSlot).count { Slots.isSelectable(timeline, it) }
                listState.animateScrollToItem((gapIndex - 1).coerceAtLeast(0))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).dragToPlace(listState, enabled = placing && !remoteTurn, onDragging = { dragging = it }) { slot ->
                    if (slot != round.selectedSlot) vm.dispatch(GameAction.SelectSlot(slot))
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (slot in 0..timeline.size) {
                    // No gap between two cards of the same year (DECISIONS.md D-056).
                    if (Slots.isSelectable(timeline, slot)) item(key = "gap-$slot") {
                        val veto = round.vetoes.firstOrNull { it.slot == slot }
                        val chosen = (if (placing) round.selectedSlot else round.lockedSlot) == slot
                        val enabled = when {
                            placing -> !remoteTurn
                            vetoer != null -> !chosen && veto == null
                            else -> false
                        }
                        Gap(
                            description = gapDescription(timeline.getOrNull(slot - 1)?.year, timeline.getOrNull(slot)?.year),
                            chosenBy = if (chosen) active else null,
                            tapAgain = placing && twice && chosen && !remoteTurn,
                            confirmTwice = twice,
                            vetoedBy = veto?.let { session.player(it.playerId) },
                            pendingVetoBy = if (!placing && pendingVeto == slot) vetoer?.let(session::player) else null,
                            enabled = enabled,
                            onClick = {
                                when {
                                    // With "tap twice", tapping the chosen gap again locks in (feedback round 11).
                                    placing && twice && round.selectedSlot == slot -> vm.dispatch(GameAction.LockIn)
                                    placing -> vm.dispatch(GameAction.SelectSlot(slot))
                                    else -> vm.placeVeto(slot, twice)
                                }
                            },
                        )
                    }
                    if (slot < timeline.size) {
                        item(key = "card-${timeline[slot].songId}") { TimelineCard(timeline[slot], session.song(timeline[slot].songId)) }
                    }
                }
            }
        }
    }

    if (scoreboard) ScoreboardSheet(session, onDismiss = { scoreboard = false }, onAction = vm::dispatch)
}

@Composable
private fun gapDescription(before: Int?, after: Int?): String = when {
    before == null && after == null -> stringResource(R.string.gap_only)
    before == null -> stringResource(R.string.gap_before, formatYear(after!!))
    after == null -> stringResource(R.string.gap_after, formatYear(before))
    else -> stringResource(R.string.gap_between, formatYear(before), formatYear(after))
}

/** A tappable gap between cards: the chosen gap in the active color, vetoes with the vetoer's initials. */
@Composable
private fun Gap(
    description: String,
    chosenBy: GamePlayer?,
    vetoedBy: GamePlayer?,
    enabled: Boolean,
    onClick: () -> Unit,
    pendingVetoBy: GamePlayer? = null,
    /** With "tap a gap twice to confirm": the chosen gap says that another tap places the card. */
    tapAgain: Boolean = false,
    /** "Tap a gap twice to confirm" is on: a marked veto says "Tap again to veto", else "Veto here?" (LY-04). */
    confirmTwice: Boolean = false,
) {
    val owner = chosenBy ?: vetoedBy ?: pendingVetoBy
    val color = owner?.let { PlayerColors.color(it.colorIndex) }
    val shape = LocalComponentShape.current
    val stateText: String? = when {
        chosenBy != null -> stringResource(R.string.gap_chosen_by, chosenBy.name)
        vetoedBy != null -> stringResource(R.string.gap_vetoed_by, vetoedBy.name)
        else -> null
    }
    val fullDescription = if (stateText == null) description else stringResource(R.string.a11y_with_detail, description, stateText)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (chosenBy != null && color != null) Modifier.background(color, shape) else Modifier)
            .border(
                BorderStroke(if (owner != null) 1.5.dp else 1.dp, color ?: MaterialTheme.colorScheme.outlineVariant),
                shape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = fullDescription }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            chosenBy != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerAvatar(chosenBy, 32.dp, ring = PlayerColors.onColor(chosenBy.colorIndex))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (tapAgain) R.string.gap_tap_again_place else R.string.gap_here),
                    color = PlayerColors.onColor(chosenBy.colorIndex),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            pendingVetoBy != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerAvatar(pendingVetoBy, 32.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (confirmTwice) R.string.gap_tap_again else R.string.gap_veto_here), style = MaterialTheme.typography.titleMedium)
            }
            vetoedBy != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerAvatar(vetoedBy, 32.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.gap_veto), style = MaterialTheme.typography.titleMedium)
            }
            enabled -> Text("+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
            else -> Spacer(Modifier.size(1.dp))
        }
    }
}

/**
 * Veto / Don't veto for the one player on the host's phone, like on a guest phone. While they pick a
 * gap, Cancel stops; after "Don't veto" the choice is shown.
 */
@Composable
private fun SingleVetoChoice(session: GameSession, playerId: String, vetoer: String?, gate: RevealStatus?, onVeto: () -> Unit, onDecline: () -> Unit) {
    val state = session.state
    val player = session.player(playerId)
    val eligible = playerId in GameEngine.vetoEligible(state)
    val declined = gate != null && playerId in gate.declined
    when {
        playerId == state.activePlayer.id -> Unit
        !eligible || declined -> Text(
            stringResource(if (declined) R.string.timeline_declined else R.string.timeline_no_vetoes_left),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        else -> Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerAvatar(player, 36.dp)
            Button(onClick = onVeto, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.timeline_veto_button), style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.timeline_no_veto_button), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/**
 * Press a gap and drag (feedback round 27): the chosen gap follows the finger, also into the part of the
 * timeline scrolled out of view, as the list scrolls near its edges. Drags that start on a card scroll
 * the list as before. [onSlot] gets each gap the finger reaches.
 */
private fun Modifier.dragToPlace(
    listState: LazyListState,
    enabled: Boolean,
    onDragging: (Boolean) -> Unit,
    onSlot: (Int) -> Unit,
): Modifier = if (!enabled) this else pointerInput(listState) {
    // The gap nearest to [y] (list coordinates) among the visible ones.
    fun slotAt(y: Float): Int? = listState.layoutInfo.visibleItemsInfo
        .filter { (it.key as? String)?.startsWith("gap-") == true }
        .minByOrNull { abs(it.offset + it.size / 2f - y) }
        ?.let { (it.key as String).removePrefix("gap-").toInt() }

    fun onGap(y: Float): Boolean = listState.layoutInfo.visibleItemsInfo.any {
        (it.key as? String)?.startsWith("gap-") == true && y >= it.offset && y < it.offset + it.size
    }
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            if (!onGap(down.position.y)) return@awaitEachGesture
            var y = down.position.y
            var scroller: Job? = null
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    y = change.position.y
                    if (scroller == null && abs(y - down.position.y) > viewConfiguration.touchSlop) {
                        onDragging(true)
                        // Near the top or bottom edge the list scrolls, and the chosen gap with it.
                        scroller = launch {
                            val edge = EDGE.toPx()
                            while (isActive) {
                                val height = size.height.toFloat()
                                val speed = when {
                                    y < edge -> -(edge - y) / 4
                                    y > height - edge -> (y - (height - edge)) / 4
                                    else -> 0f
                                }
                                if (speed != 0f) listState.scrollBy(speed)
                                slotAt(y)?.let(onSlot)
                                delay(16)
                            }
                        }
                    }
                    // Consumed before the list sees it: no scrolling, and no tap on the gap afterwards.
                    if (scroller != null) change.consume()
                }
            } finally {
                scroller?.cancel()
                if (scroller != null) onDragging(false)
            }
        }
    }
}

private val EDGE = 72.dp

/** Whole seconds left, rounded up, for countdowns. */
private fun seconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

/**
 * Players who may still veto; tap one, then a free gap. Remote players show whether they are still
 * deciding on their phone or declined (SPEC v3); the host can still veto for them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VetoBar(session: GameSession, selected: String?, remote: Set<String>, gate: RevealStatus?, connected: Set<String>, onSelect: (String) -> Unit) {
    val state = session.state
    val round = state.round ?: return
    val perSong = state.settings.timeline.vetoesPerPlayerPerSong
    val eligible = session.playing.filter { p ->
        val ps = state.player(p.id)!!
        p.id != state.activePlayer.id && ps.vetoes > 0 && round.vetoes.count { it.playerId == p.id } < perSong
    }
    if (eligible.isEmpty()) {
        Text(stringResource(R.string.timeline_no_vetoes_left), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    // Wrapping, so no name is cut off (DESIGN-REVIEW UX-03).
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in eligible) {
            val vetoes = state.player(p.id)!!.vetoes
            var detail = pluralStringResource(R.plurals.vetoes, vetoes, vetoes)
            if (p.id in remote) {
                val decision = stringResource(if (gate != null && p.id in gate.declined) R.string.veto_status_declined else R.string.veto_status_deciding)
                detail = stringResource(R.string.name_with_detail, detail, decision)
            }
            // A connected guest vetoes on their own phone; the host can't do it for them (feedback round 13).
            FilterChip(
                selected = selected == p.id,
                onClick = { onSelect(p.id) },
                enabled = p.id !in connected,
                leadingIcon = { PlayerAvatar(p, 28.dp) },
                label = { Text(stringResource(R.string.name_with_detail, p.name, detail)) },
                modifier = Modifier.height(48.dp),
            )
        }
    }
}
