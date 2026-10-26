package com.trackrecord.app.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Remote players": opening it generates the QR code and so starts remote play (SPEC v3; D-101).
 * Lists the players on guest phones, lets the host disconnect one, and stops remote play.
 */
@Composable
fun RemotePlayDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val remote = container.remote
    val state by remote.state.collectAsStateWithLifecycle()
    val session by container.game.session.collectAsStateWithLifecycle()
    var attempt by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        link = withContext(Dispatchers.IO) { remote.start() }
        checked = true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.remote_wifi_title), style = MaterialTheme.typography.titleMedium)
                val url = link
                when {
                    !checked -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    url == null -> {
                        Text(stringResource(R.string.remote_no_network), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { checked = false; attempt++ }) { Text(stringResource(R.string.remote_retry)) }
                    }
                    else -> {
                        val qr = remember(url) { qrCode(url) }
                        val description = stringResource(R.string.remote_qr_description)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            // Dark modules on white, whatever the theme, so every camera reads it.
                            Image(
                                qr,
                                contentDescription = null,
                                filterQuality = FilterQuality.None,
                                modifier = Modifier.size(260.dp).background(Color.White).semantics { contentDescription = description },
                            )
                        }
                        // Tap to copy (feedback round 11).
                        var copied by remember(url) { mutableStateOf(false) }
                        val copyLabel = stringResource(R.string.remote_copy_link)
                        val clipLabel = stringResource(R.string.remote_title)
                        Column(Modifier.fillMaxWidth().clickable(onClickLabel = copyLabel) {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText(clipLabel, url))
                            copied = true
                        }) {
                            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(if (copied) R.string.remote_link_copied else R.string.remote_copy_link),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(stringResource(R.string.remote_scan_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                InternetInvite(remote, (state as? RemoteState.On)?.internetLink)

                Text(stringResource(R.string.remote_players_title), style = MaterialTheme.typography.titleMedium)
                val coHost = (state as? RemoteState.On)?.clients?.coHost
                val claimed = (state as? RemoteState.On)?.clients?.claimed.orEmpty()
                val players = session?.players.orEmpty().filter { it.id in claimed }
                if (players.isEmpty()) {
                    Text(stringResource(R.string.remote_none), style = MaterialTheme.typography.bodyMedium)
                }
                val devices = (state as? RemoteState.On)?.clients?.devices.orEmpty()
                for (p in players) {
                    val device = deviceName(devices[p.id])
                    // The name on its own line, the buttons below it, so neither is squeezed (DESIGN-REVIEW UX-04).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerAvatar(p, 36.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            val status = stringResource(if (claimed[p.id] == true) R.string.remote_status_connected else R.string.remote_status_away)
                            Text(
                                if (p.id in devices) stringResource(R.string.name_with_detail, status, device) else status,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(Modifier.padding(start = 40.dp)) {
                        val label = stringResource(R.string.remote_disconnect_a11y, p.name)
                        TextButton(onClick = { remote.disconnect(p.id) }, modifier = Modifier.semantics { contentDescription = label }) {
                            Text(stringResource(R.string.remote_disconnect))
                        }
                        // The host controls on that phone too (D-189); tap again to take them back.
                        val coHostLabel = stringResource(R.string.remote_cohost_a11y, p.name, device)
                        TextButton(
                            onClick = { remote.setCoHost(if (coHost == p.id) null else p.id) },
                            modifier = Modifier.semantics { contentDescription = coHostLabel },
                        ) { Text(stringResource(if (coHost == p.id) R.string.remote_cohost_on else R.string.remote_cohost)) }
                        // For good this session (D-182).
                        val blockLabel = stringResource(R.string.remote_block_a11y, p.name, device)
                        TextButton(onClick = { remote.block(p.id) }, modifier = Modifier.semantics { contentDescription = blockLabel }) {
                            Text(stringResource(R.string.remote_block))
                        }
                    }
                }

                if (players.isNotEmpty()) Text(stringResource(R.string.remote_cohost_hint), style = MaterialTheme.typography.bodySmall)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state is RemoteState.On) {
                        TextButton(onClick = { remote.stop(); onDismiss() }) { Text(stringResource(R.string.remote_stop)) }
                    }
                    Spacer(Modifier.weight(1f).width(8.dp))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_close)) }
                }
            }
        }
    }
}

/**
 * A guest's phone asks to take over a player whose phone is still connected (D-182): the host allows
 * or denies it, wherever it is in the app.
 */
@Composable
fun TakeoverPrompt() {
    val container = LocalContext.current.appContainer
    val state by container.remote.state.collectAsStateWithLifecycle()
    val session by container.game.session.collectAsStateWithLifecycle()
    val request = (state as? RemoteState.On)?.clients?.takeovers?.firstOrNull() ?: return
    val name = session?.players?.firstOrNull { it.id == request.player }?.name ?: return
    val asking = deviceName(request.device)
    val playing = deviceName((state as? RemoteState.On)?.clients?.devices?.get(request.player))
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.remote_takeover_title, name, asking)) },
        text = { Text(stringResource(R.string.remote_takeover_text, name, asking, playing)) },
        confirmButton = { TextButton(onClick = { container.remote.approveTakeover(request.client) }) { Text(stringResource(R.string.remote_takeover_allow)) } },
        dismissButton = { TextButton(onClick = { container.remote.denyTakeover(request.client) }) { Text(stringResource(R.string.remote_takeover_deny)) } },
    )
}

/**
 * Over the internet (SPEC v4): invite opens the room and shows the link with Share; "New link" makes a
 * new room, so the old link stops working.
 */
@Composable
private fun InternetInvite(remote: RemotePlay, link: String?) {
    val context = LocalContext.current
    Text(stringResource(R.string.remote_internet_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.remote_internet_hint), style = MaterialTheme.typography.bodyMedium)
    // A link that could never work isn't offered (REVIEW-2 S-15).
    if (!InternetRoom.supported()) {
        Text(stringResource(R.string.remote_internet_unsupported), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        return
    }
    if (link == null) {
        Button(onClick = { remote.inviteOverInternet() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.remote_internet_invite)) }
        return
    }
    var copied by remember(link) { mutableStateOf(false) }
    val copyLabel = stringResource(R.string.remote_copy_link)
    val clipLabel = stringResource(R.string.remote_title)
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = copyLabel) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(clipLabel, link))
        copied = true
    }) {
        Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(if (copied) R.string.remote_link_copied else R.string.remote_copy_link), style = MaterialTheme.typography.labelSmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val shareText = stringResource(R.string.remote_internet_share_text, link)
        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
            context.startActivity(Intent.createChooser(send, null))
        }) { Text(stringResource(R.string.remote_internet_share)) }
        TextButton(onClick = { remote.newInternetLink() }) { Text(stringResource(R.string.remote_internet_new)) }
    }
}

/** The QR code as one pixel per module (with a quiet zone); drawn scaled up without smoothing. */
private fun qrCode(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
