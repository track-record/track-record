package com.trackrecord.app.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_cancel
import com.trackrecord.app.resources.game_leave_confirm
import com.trackrecord.app.resources.game_leave_text
import com.trackrecord.app.resources.game_leave_title
import com.trackrecord.app.resources.game_next_song
import com.trackrecord.app.ui.Texts
import com.trackrecord.app.ui.components.BackHandler
import com.trackrecord.app.ui.components.HideNavigationBar
import com.trackrecord.app.ui.components.KeepScreenOn
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.Phase
import com.trackrecord.engine.RejectReason
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

/** Rejections of a year guess: shown briefly, as players try the next year right away. */
private val BRIEF_REJECTIONS = setOf(RejectReason.YEAR_OUT_OF_RANGE, RejectReason.DUPLICATE_GUESS)
private const val BRIEF_REJECTION_MS = 1_000L

/** Hosts every in-game screen and switches on the game phase. */
@Composable
fun GameScreen(onExit: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: GameViewModel = viewModel(factory = viewModelFactory { initializer { GameViewModel(container.game) } })
    val session by vm.session.collectAsStateWithLifecycle()
    val restored by container.game.restored.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    KeepScreenOn()
    // In a game the navigation bar is out of the way; the menus keep it (feedback round 31).
    HideNavigationBar()
    // Guests can play only while the host is on the game, not in the menu (feedback round 9); another app in
    // front doesn't pause them (feedback round 51).
    DisposableEffect(Unit) {
        container.game.setHostOnGame(true)
        onDispose { container.game.setHostOnGame(false) }
    }
    LaunchedEffect(vm) {
        // The newest rejection replaces the one shown; the year checks of a guess go after a second (feedback round 49).
        vm.rejections.collectLatest { reason ->
            val text = Texts.get(reason.message)
            if (reason in BRIEF_REJECTIONS) withTimeoutOrNull(BRIEF_REJECTION_MS) { snackbar.showSnackbar(text) }
            else snackbar.showSnackbar(text)
        }
    }
    BackHandler { confirmLeave = true }

    val s = session
    if (s == null) {
        // After process death the saved game may still be loading.
        if (restored) LaunchedEffect(Unit) { onExit() }
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LaunchedEffect(s.state.round?.number, s.state.round?.song?.songId) { container.game.ensureAudio() }
    when (s.state.phase) {
        Phase.PLACING, Phase.VETOING -> TimelineRoundScreen(s, vm, snackbar, onLeave = { confirmLeave = true })
        Phase.GUESSING -> ClosestYearRoundScreen(s, vm, snackbar, onLeave = { confirmLeave = true })
        Phase.REVEALED -> ResultScreen(s, vm, snackbar, onLeave = { confirmLeave = true })
        Phase.GAME_OVER -> GameOverScreen(s, vm, onHome = { vm.endGame(); onExit() })
        Phase.AWAITING_DRAW -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // As on guest phones (DESIGN-REVIEW LY-07).
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(Res.string.game_next_song), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    SongEventDialogs(s)

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(Res.string.game_leave_title)) },
            text = { Text(stringResource(Res.string.game_leave_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.pauseAudio()
                    onExit()
                }) { Text(stringResource(Res.string.game_leave_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

val GameSession.isTimeline: Boolean get() = state.mode == GameMode.TIMELINE
