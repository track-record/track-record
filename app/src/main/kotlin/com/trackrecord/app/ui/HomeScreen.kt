package com.trackrecord.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.BuildConfig
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.remote.RemoteState
import com.trackrecord.app.settings.UpdateStatus
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.Card
import com.trackrecord.app.ui.components.CenteredAboveNavigationBar
import com.trackrecord.app.ui.components.FilledTonalButton
import com.trackrecord.app.ui.components.TextButton

@Composable
fun HomeScreen(canContinue: Boolean, onNewGame: () -> Unit, onContinue: () -> Unit, onLibrary: () -> Unit, onSettings: () -> Unit, onPlayers: () -> Unit) {
    // Only the status bar pads the screen; the navigation bar is handled by CenteredAboveNavigationBar.
    Scaffold(contentWindowInsets = WindowInsets.statusBars) { padding ->
        CenteredAboveNavigationBar(Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(36.dp))
            if (canContinue) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text(stringResource(R.string.home_continue), style = MaterialTheme.typography.titleLarge)
                }
            }
            Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(stringResource(R.string.home_new_game), style = MaterialTheme.typography.titleLarge)
            }
            FilledTonalButton(onClick = onLibrary, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(stringResource(R.string.home_library), style = MaterialTheme.typography.titleLarge)
            }
            FilledTonalButton(onClick = onPlayers, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(stringResource(R.string.home_players), style = MaterialTheme.typography.titleLarge)
            }
            FilledTonalButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(stringResource(R.string.home_settings), style = MaterialTheme.typography.titleLarge)
            }
            RemotePlayNotice()
        }
        }
    }
    UpdateDialog()
}

/** A newer release is out: asked once per app start (feedback round 47); Settings keeps the download. */
@Composable
private fun UpdateDialog() {
    val updates = LocalContext.current.appContainer.updates
    val status by updates.status.collectAsStateWithLifecycle()
    val dismissed by updates.dismissed.collectAsStateWithLifecycle()
    val release = (status as? UpdateStatus.Available)?.release ?: return
    if (dismissed) return
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = updates::dismiss,
        title = { Text(stringResource(R.string.update_title)) },
        text = { Text(stringResource(R.string.update_text, release.versionName, BuildConfig.VERSION_NAME)) },
        confirmButton = {
            TextButton(onClick = {
                updates.dismiss()
                runCatching { uriHandler.openUri(release.apkUrl ?: release.pageUrl) }
            }) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = { TextButton(onClick = updates::dismiss) { Text(stringResource(R.string.update_later)) } },
    )
}

/** While remote play runs, the home screen says so and offers Stop (SPEC v3; D-101). */
@Composable
private fun RemotePlayNotice() {
    val remote = LocalContext.current.appContainer.remote
    val state by remote.state.collectAsStateWithLifecycle()
    val on = state as? RemoteState.On ?: return
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_remote_on), style = MaterialTheme.typography.titleSmall)
                val count = on.clients.claimed.size
                Text(pluralStringResource(R.plurals.home_remote_players, count, count), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = remote::stop) { Text(stringResource(R.string.remote_stop)) }
        }
    }
}
