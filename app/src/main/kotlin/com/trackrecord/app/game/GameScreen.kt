package com.trackrecord.app.game

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.ui.components.HideNavigationBar
import com.trackrecord.app.ui.components.KeepScreenOn
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.Phase

/** Hosts every in-game screen and switches on the game phase. */
@Composable
fun GameScreen(onExit: () -> Unit) {
    val container = LocalContext.current.appContainer
    val vm: GameViewModel = viewModel(factory = viewModelFactory { initializer { GameViewModel(container.game) } })
    val session by vm.session.collectAsStateWithLifecycle()
    val restored by container.game.restored.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    KeepScreenOn()
    // In a game the navigation bar is out of the way; the menus keep it (feedback round 31).
    HideNavigationBar()
    // Guests can play only while the host is here, not in the menu or another app (feedback round 9).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> container.game.setHostOnGame(true)
                Lifecycle.Event.ON_STOP -> container.game.setHostOnGame(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            container.game.setHostOnGame(false)
        }
    }
    LaunchedEffect(vm) {
        vm.rejections.collect { snackbar.showSnackbar(resources.getString(it.message)) }
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
                Text(stringResource(R.string.game_next_song), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    SongEventDialogs(s)

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.game_leave_title)) },
            text = { Text(stringResource(R.string.game_leave_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.pauseAudio()
                    onExit()
                }) { Text(stringResource(R.string.game_leave_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

val GameSession.isTimeline: Boolean get() = state.mode == GameMode.TIMELINE
