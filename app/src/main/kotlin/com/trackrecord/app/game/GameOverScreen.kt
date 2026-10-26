package com.trackrecord.app.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R
import com.trackrecord.app.ui.components.BottomBarSurface
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.engine.EndReason
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode

@Composable
fun GameOverScreen(session: GameSession, vm: GameViewModel, onHome: () -> Unit) {
    val state = session.state
    val result = state.result ?: return
    val winners = result.winnerIds.map { session.player(it) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val score: (String) -> Int = { id -> GameEngine.score(state, state.anyPlayer(id)!!) }
    // Removed players last, greyed out (feedback round 36).
    val ranked = session.playing.sortedByDescending { score(it.id) } + session.removed

    Scaffold(
        bottomBar = {
            BottomBarSurface {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onHome, modifier = Modifier.weight(1f).height(60.dp)) { Text(stringResource(R.string.game_over_home)) }
                    Button(onClick = { vm.playAgain() }, modifier = Modifier.weight(1f).height(60.dp)) {
                        Text(stringResource(R.string.game_over_play_again), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.game_over_title), style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { winners.forEach { PlayerAvatar(it, 72.dp) } }
                    Text(
                        if (winners.size == 1) stringResource(R.string.game_over_winner, winners[0].name)
                        else stringResource(R.string.game_over_shared, winners.joinToString(stringResource(R.string.list_separator)) { it.name }),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (result.reason == EndReason.POOL_EMPTY) Text(stringResource(R.string.game_over_pool_empty), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.game_over_final_scores), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                }
            }
            items(ranked, key = { it.id }) { player ->
                // Rank and Distance have no cards to show (DESIGN-REVIEW LY-06).
                val open = Modifier.clickable(enabled = state.collectsCards()) { expanded = if (expanded == player.id) null else player.id }
                Column(Modifier.fillMaxWidth().then(open).padding(vertical = 12.dp)) {
                    val gone = state.player(player.id) == null
                    Row(Modifier.alpha(if (gone) REMOVED_ALPHA else 1f), verticalAlignment = Alignment.CenterVertically) {
                        PlayerAvatar(player, 44.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(player.name, style = MaterialTheme.typography.titleMedium)
                            val label = scoreLabel(session, player.id)
                            Text(if (gone) stringResource(R.string.name_with_detail, stringResource(R.string.scoreboard_removed), label) else label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (expanded == player.id) {
                        val ps = state.anyPlayer(player.id)!!
                        val cards = if (state.mode == GameMode.TIMELINE) ps.timeline else ps.collection
                        cards.forEach { TimelineCard(it, session.song(it.songId), Modifier.padding(top = 4.dp)) }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
