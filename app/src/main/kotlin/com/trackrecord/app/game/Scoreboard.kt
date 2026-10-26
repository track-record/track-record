package com.trackrecord.app.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.remote.RemoteState
import com.trackrecord.app.remote.deviceName
import com.trackrecord.app.ui.components.AppSheet
import com.trackrecord.app.ui.components.FilledTonalButton
import com.trackrecord.app.ui.components.FilledTonalIconButton
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.SwitchRow
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.engine.ClosestScoring
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameState
import com.trackrecord.engine.Phase
import com.trackrecord.engine.ReduceResult

// The scoreboards: the sheet with corrections, buying and removing players, and the card on the
// reveal screens (split from GameParts.kt, REVIEW.md C-05; DECISIONS.md D-274).

/**
 * Score label for a player: cards and vetoes (Timeline), wins or points and skips (Closest Year); with
 * [withTarget] the score reads "3 of 10 cards" (feedback round 36).
 */
@Composable
fun scoreLabel(session: GameSession, playerId: String, withTarget: Boolean = false): String = scoreLabel(session.state, playerId, withTarget)

@Composable
fun scoreLabel(state: GameState, playerId: String, withTarget: Boolean = false): String {
    val p = state.anyPlayer(playerId) ?: return ""
    val score = GameEngine.score(state, p)
    val target = GameEngine.target(state)
    return when (state.mode) {
        GameMode.TIMELINE -> stringResource(
            R.string.score_timeline,
            if (withTarget) stringResource(R.string.score_cards_of, score, target) else pluralStringResource(R.plurals.cards, score, score),
            pluralStringResource(R.plurals.vetoes, p.vetoes, p.vetoes),
        )
        GameMode.CLOSEST_YEAR -> {
            val cy = state.settings.closestYear
            val main = when {
                // Cards, as the rules call them (DESIGN-REVIEW WD-03).
                cy.scoring == ClosestScoring.CLOSEST && withTarget -> stringResource(R.string.score_cards_of, score, target)
                cy.scoring == ClosestScoring.CLOSEST -> pluralStringResource(R.plurals.cards, score, score)
                withTarget -> stringResource(R.string.score_points_of, score, target)
                else -> pluralStringResource(R.plurals.points, score, score)
            }
            if (cy.startingSkips > 0 || cy.namingEarnsSkip) stringResource(R.string.score_timeline, main, pluralStringResource(R.plurals.skips, p.skips, p.skips)) else main
        }
    }
}

/** Cards are collected in Timeline and in Closest scoring; Rank and Distance only count points (feedback round 36). */
fun GameState.collectsCards(): Boolean = mode == GameMode.TIMELINE || settings.closestYear.scoring == ClosestScoring.CLOSEST

/**
 * Every player with score and the target; tap to see their timeline or collection, and to remove them
 * from the game (feedback round 36); Buy card when allowed. Removed players are listed last, greyed out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreboardSheet(session: GameSession, onDismiss: () -> Unit, onAction: (GameAction) -> Unit) {
    var expanded by remember { mutableStateOf<String?>(null) }
    var adjusting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val state = session.state
    // Vetoes in Timeline, skips in Closest Year (feedback round 27); before the song is placed or revealed.
    val timelineMode = state.mode == GameMode.TIMELINE
    val buyCost = if (timelineMode) state.settings.timeline.buyCost else state.settings.closestYear.buyCost
    val buyPhase = if (timelineMode) Phase.PLACING else Phase.GUESSING
    val buyAllowed = buyCost != null && (state.phase == buyPhase || state.phase == Phase.AWAITING_DRAW)
    val remoteState by LocalContext.current.appContainer.remote.state.collectAsStateWithLifecycle()
    val onGuestPhone = (remoteState as? RemoteState.On)?.clients?.claimed.orEmpty()
    val devices = (remoteState as? RemoteState.On)?.clients?.devices.orEmpty()
    // During the reveal, the scores after Next, as on the scoreboard card below (DESIGN-REVIEW UX-01).
    val shown = if (state.phase == Phase.REVEALED) (GameEngine.reduce(state, GameAction.Next) as? ReduceResult.Accepted)?.state ?: state else state

    AppSheet(stringResource(R.string.scoreboard_title), onDismiss = onDismiss) {
        // Corrections: vetoes in Timeline, skips in Closest Year when skips are in play (feedback round 12).
        val cy = state.settings.closestYear
        val skipsInPlay = state.mode == GameMode.CLOSEST_YEAR && (cy.startingSkips > 0 || cy.namingEarnsSkip)
        if (state.mode == GameMode.TIMELINE) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(stringResource(R.string.scoreboard_adjust_vetoes), adjusting, { adjusting = it }, hint = stringResource(R.string.scoreboard_adjust_vetoes_hint))
            }
        } else if (skipsInPlay) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(stringResource(R.string.scoreboard_adjust_skips), adjusting, { adjusting = it }, hint = stringResource(R.string.scoreboard_adjust_skips_hint))
            }
        }
        LazyColumn(Modifier.heightIn(max = sheetHeight())) {
            items(session.playing + session.removed, key = { it.id }) { player ->
                val ps = state.anyPlayer(player.id) ?: return@items
                val gone = state.player(player.id) == null
                Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == player.id) null else player.id }.padding(16.dp)) {
                    Row(Modifier.alpha(if (gone) REMOVED_ALPHA else 1f), verticalAlignment = Alignment.CenterVertically) {
                        PlayerAvatar(player, 44.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(player.name, style = MaterialTheme.typography.titleMedium)
                            val score = scoreLabel(shown, player.id, withTarget = true)
                            Text(if (gone) stringResource(R.string.name_with_detail, stringResource(R.string.scoreboard_removed), score) else score, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (gone) {
                            // Out of the game: nothing to adjust or buy.
                        } else if (adjusting && state.mode == GameMode.CLOSEST_YEAR) {
                            val removeLabel = stringResource(R.string.scoreboard_remove_skip, player.name)
                            val addLabel = stringResource(R.string.scoreboard_add_skip, player.name)
                            FilledTonalIconButton(
                                onClick = { onAction(GameAction.AdjustSkips(player.id, -1)) },
                                enabled = ps.skips > 0,
                                modifier = Modifier.semantics { contentDescription = removeLabel },
                            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                            FilledTonalIconButton(
                                onClick = { onAction(GameAction.AdjustSkips(player.id, 1)) },
                                enabled = state.settings.closestYear.maxSkips.let { it == null || ps.skips < it },
                                modifier = Modifier.semantics { contentDescription = addLabel },
                            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        } else if (adjusting) {
                            val removeLabel = stringResource(R.string.scoreboard_remove_veto, player.name)
                            val addLabel = stringResource(R.string.scoreboard_add_veto, player.name)
                            FilledTonalIconButton(
                                onClick = { onAction(GameAction.AdjustVetoes(player.id, -1)) },
                                enabled = ps.vetoes > 0,
                                modifier = Modifier.semantics { contentDescription = removeLabel },
                            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                            FilledTonalIconButton(
                                onClick = { onAction(GameAction.AdjustVetoes(player.id, 1)) },
                                enabled = ps.vetoes < state.settings.timeline.maxVetoes,
                                modifier = Modifier.semantics { contentDescription = addLabel },
                            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        } else if (buyAllowed && buyCost != null && onGuestPhone[player.id] != true) {
                            // A connected guest buys on their own phone (feedback round 14).
                            val funds = if (timelineMode) ps.vetoes else ps.skips
                            val currency = if (timelineMode) R.plurals.vetoes else R.plurals.skips
                            FilledTonalButton(onClick = { onAction(GameAction.BuyCard(player.id)) }, enabled = funds >= buyCost) {
                                Text(stringResource(R.string.scoreboard_buy, pluralStringResource(currency, buyCost, buyCost)))
                            }
                        }
                    }
                    // Played from a guest's device; disconnecting is in the QR sheet (feedback round 8).
                    onGuestPhone[player.id]?.takeIf { !gone }?.let { connected ->
                        Text(
                            stringResource(if (connected) R.string.scoreboard_remote_connected else R.string.scoreboard_remote_away, deviceName(devices[player.id])),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 56.dp, top = 4.dp),
                        )
                    }
                    if (expanded == player.id) {
                        val cardsOf = shown.anyPlayer(player.id) ?: ps
                        val cards = if (state.mode == GameMode.TIMELINE) cardsOf.timeline else cardsOf.collection
                        Spacer(Modifier.size(8.dp))
                        if (state.collectsCards()) {
                            if (cards.isEmpty()) Text(stringResource(R.string.scoreboard_no_cards), style = MaterialTheme.typography.bodySmall)
                            cards.forEach { c -> TimelineCard(c, session.song(c.songId), Modifier.padding(vertical = 2.dp)) }
                        }
                        // Mid-game, while more than the minimum play (feedback round 36).
                        if (!gone && state.phase != Phase.GAME_OVER && state.players.size > GameEngine.MIN_PLAYERS) {
                            TextButton(onClick = { removing = player.id }) {
                                Text(stringResource(R.string.scoreboard_remove), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
    removing?.let { id ->
        val name = session.player(id).name
        // Their own Timeline turn ends with them (the song leaves the game unless a veto already won it).
        val turn = state.mode == GameMode.TIMELINE && state.round != null && state.activePlayer.id == id
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.scoreboard_remove_title, name)) },
            text = {
                Text(
                    stringResource(R.string.scoreboard_remove_text, name) +
                        (if (turn) " " + stringResource(R.string.scoreboard_remove_turn) else ""),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(GameAction.RemovePlayer(id))
                    removing = null
                    expanded = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * The most a bottom sheet's content may take: 70 % of the screen, so with the drag handle and the bottom
 * padding the sheet never reaches the status bar. One that did jumped up and down without end on Felix's
 * phone (feedback rounds 38, 39).
 */
@Composable
fun sheetHeight(): Dp = LocalConfiguration.current.screenHeightDp.dp * SHEET_FRACTION

private const val SHEET_FRACTION = 0.7f

/** Removed players on the scoreboards: greyed out (feedback round 36). */
const val REMOVED_ALPHA = 0.5f

/**
 * Scores as they will be after this round's Next (the card or win just revealed included), so the
 * reveal screen shows the current ranking. A change since before the round is marked.
 */
@Composable
fun ScoreboardCard(session: GameSession, modifier: Modifier = Modifier) {
    val before = session.state
    val after = (GameEngine.reduce(before, GameAction.Next) as? ReduceResult.Accepted)?.state ?: before
    val score: (GameState, String) -> Int = { st, id -> st.anyPlayer(id)?.let { GameEngine.score(st, it) } ?: 0 }
    SectionCard(stringResource(R.string.scoreboard_card_title), modifier, spacing = 6.dp) {
        run {
            // Removed players last and greyed out, without a rank (feedback round 36).
            val ranked = session.playing.sortedByDescending { score(after, it.id) }
            (ranked + session.removed).forEachIndexed { rank, player ->
                val gained = score(after, player.id) - score(before, player.id)
                val gone = rank >= ranked.size
                Row(Modifier.alpha(if (gone) REMOVED_ALPHA else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (gone) "–" else stringResource(R.string.scoreboard_rank, rank + 1), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
                    PlayerAvatar(player, 32.dp)
                    Text(player.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (gained > 0) Text(stringResource(R.string.scoreboard_gained, gained), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(scoreLabel(after, player.id), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
