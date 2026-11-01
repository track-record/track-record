package com.trackrecord.app.remote

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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.platform.LocalPlatformUi
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.name_with_detail
import com.trackrecord.app.resources.remote_block
import com.trackrecord.app.resources.remote_block_a11y
import com.trackrecord.app.resources.remote_close
import com.trackrecord.app.resources.remote_cohost
import com.trackrecord.app.resources.remote_cohost_a11y
import com.trackrecord.app.resources.remote_cohost_hint
import com.trackrecord.app.resources.remote_cohost_on
import com.trackrecord.app.resources.remote_copy_link
import com.trackrecord.app.resources.remote_disconnect
import com.trackrecord.app.resources.remote_disconnect_a11y
import com.trackrecord.app.resources.remote_firewall_hint
import com.trackrecord.app.resources.remote_internet_hint
import com.trackrecord.app.resources.remote_internet_invite
import com.trackrecord.app.resources.remote_internet_new
import com.trackrecord.app.resources.remote_internet_share
import com.trackrecord.app.resources.remote_internet_share_text
import com.trackrecord.app.resources.remote_internet_title
import com.trackrecord.app.resources.remote_internet_unsupported
import com.trackrecord.app.resources.remote_link_copied
import com.trackrecord.app.resources.remote_no_network
import com.trackrecord.app.resources.remote_none
import com.trackrecord.app.resources.remote_players_title
import com.trackrecord.app.resources.remote_qr_description
import com.trackrecord.app.resources.remote_retry
import com.trackrecord.app.resources.remote_scan_hint
import com.trackrecord.app.resources.remote_status_away
import com.trackrecord.app.resources.remote_status_connected
import com.trackrecord.app.resources.remote_stop
import com.trackrecord.app.resources.remote_takeover_allow
import com.trackrecord.app.resources.remote_takeover_deny
import com.trackrecord.app.resources.remote_takeover_text
import com.trackrecord.app.resources.remote_takeover_title
import com.trackrecord.app.resources.remote_title
import com.trackrecord.app.resources.remote_wifi_title
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * "Remote players": opening it generates the QR code and so starts remote play (SPEC v3; D-101).
 * Lists the players on guest phones, lets the host disconnect one, and stops remote play.
 */
@Composable
fun RemotePlayDialog(onDismiss: () -> Unit) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val container = LocalAppContainer.current
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
    // The system asks whether guests may reach the app as the server starts (Windows' firewall): explained the first
    // time, beside its prompt (PLATFORMS.md phase 7).
    var networkAccessHint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (container.platform.asksForNetworkAccess && !container.settings.current().networkAccessHintSeen) {
            networkAccessHint = true
            container.settings.update { it.copy(networkAccessHintSeen = true) }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Phone-sized in a desktop window too (feedback rounds 55, 56): the limit first, as fillMaxWidth fixes the
            // width for whatever follows.
            modifier = Modifier.widthIn(max = MAX_DIALOG_WIDTH).fillMaxWidth(0.92f),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.remote_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(Res.string.remote_wifi_title), style = MaterialTheme.typography.titleMedium)
                val url = link
                when {
                    !checked -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    url == null -> {
                        Text(stringResource(Res.string.remote_no_network), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { checked = false; attempt++ }) { Text(stringResource(Res.string.remote_retry)) }
                    }
                    else -> {
                        val qr = remember(url) { qrCode(url) }
                        val description = stringResource(Res.string.remote_qr_description)
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
                        val copyLabel = stringResource(Res.string.remote_copy_link)
                        Column(Modifier.fillMaxWidth().clickable(onClickLabel = copyLabel) {
                            clipboard.setText(AnnotatedString(url))
                            copied = true
                        }) {
                            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(if (copied) Res.string.remote_link_copied else Res.string.remote_copy_link),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(stringResource(Res.string.remote_scan_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (networkAccessHint) Text(stringResource(Res.string.remote_firewall_hint), style = MaterialTheme.typography.bodyMedium)

                InternetInvite(remote, (state as? RemoteState.On)?.internetLink)

                Text(stringResource(Res.string.remote_players_title), style = MaterialTheme.typography.titleMedium)
                val coHost = (state as? RemoteState.On)?.clients?.coHost
                val claimed = (state as? RemoteState.On)?.clients?.claimed.orEmpty()
                val players = session?.players.orEmpty().filter { it.id in claimed }
                if (players.isEmpty()) {
                    Text(stringResource(Res.string.remote_none), style = MaterialTheme.typography.bodyMedium)
                }
                val devices = (state as? RemoteState.On)?.clients?.devices.orEmpty()
                for (p in players) {
                    val device = deviceName(devices[p.id])
                    // The name on its own line, the buttons below it, so neither is squeezed (DESIGN-REVIEW UX-04).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerAvatar(p, 36.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            val status = stringResource(if (claimed[p.id] == true) Res.string.remote_status_connected else Res.string.remote_status_away)
                            Text(
                                if (p.id in devices) stringResource(Res.string.name_with_detail, status, device) else status,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(Modifier.padding(start = 40.dp)) {
                        val label = stringResource(Res.string.remote_disconnect_a11y, p.name)
                        TextButton(onClick = { remote.disconnect(p.id) }, modifier = Modifier.semantics { contentDescription = label }) {
                            Text(stringResource(Res.string.remote_disconnect))
                        }
                        // The host controls on that phone too (D-189); tap again to take them back.
                        val coHostLabel = stringResource(Res.string.remote_cohost_a11y, p.name, device)
                        TextButton(
                            onClick = { remote.setCoHost(if (coHost == p.id) null else p.id) },
                            modifier = Modifier.semantics { contentDescription = coHostLabel },
                        ) { Text(stringResource(if (coHost == p.id) Res.string.remote_cohost_on else Res.string.remote_cohost)) }
                        // For good this session (D-182).
                        val blockLabel = stringResource(Res.string.remote_block_a11y, p.name, device)
                        TextButton(onClick = { remote.block(p.id) }, modifier = Modifier.semantics { contentDescription = blockLabel }) {
                            Text(stringResource(Res.string.remote_block))
                        }
                    }
                }

                if (players.isNotEmpty()) Text(stringResource(Res.string.remote_cohost_hint), style = MaterialTheme.typography.bodySmall)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state is RemoteState.On) {
                        TextButton(onClick = { remote.stop(); onDismiss() }) { Text(stringResource(Res.string.remote_stop)) }
                    }
                    Spacer(Modifier.weight(1f).width(8.dp))
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.remote_close)) }
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
    val container = LocalAppContainer.current
    val state by container.remote.state.collectAsStateWithLifecycle()
    val session by container.game.session.collectAsStateWithLifecycle()
    val request = (state as? RemoteState.On)?.clients?.takeovers?.firstOrNull() ?: return
    val name = session?.players?.firstOrNull { it.id == request.player }?.name ?: return
    val asking = deviceName(request.device)
    val playing = deviceName((state as? RemoteState.On)?.clients?.devices?.get(request.player))
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.remote_takeover_title, name, asking)) },
        text = { Text(stringResource(Res.string.remote_takeover_text, name, asking, playing)) },
        confirmButton = { TextButton(onClick = { container.remote.approveTakeover(request.client) }) { Text(stringResource(Res.string.remote_takeover_allow)) } },
        dismissButton = { TextButton(onClick = { container.remote.denyTakeover(request.client) }) { Text(stringResource(Res.string.remote_takeover_deny)) } },
    )
}

/**
 * Over the internet (SPEC v4): invite opens the room and shows the link with Share; "New link" makes a
 * new room, so the old link stops working.
 */
@Composable
private fun InternetInvite(remote: RemotePlay, link: String?) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val share = LocalPlatformUi.current.share
    Text(stringResource(Res.string.remote_internet_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(Res.string.remote_internet_hint), style = MaterialTheme.typography.bodyMedium)
    // A link that could never work isn't offered (REVIEW-2 S-15).
    if (!LocalAppContainer.current.platform.canHostInternetRoom) {
        Text(stringResource(Res.string.remote_internet_unsupported), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        return
    }
    if (link == null) {
        Button(onClick = { remote.inviteOverInternet() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.remote_internet_invite)) }
        return
    }
    var copied by remember(link) { mutableStateOf(false) }
    val copyLabel = stringResource(Res.string.remote_copy_link)
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = copyLabel) {
        clipboard.setText(AnnotatedString(link))
        copied = true
    }) {
        Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(if (copied) Res.string.remote_link_copied else Res.string.remote_copy_link), style = MaterialTheme.typography.labelSmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val shareText = stringResource(Res.string.remote_internet_share_text, link)
        // Where there is no share sheet, tapping the link copies it.
        share?.let { Button(onClick = { it(shareText) }) { Text(stringResource(Res.string.remote_internet_share)) } }
        TextButton(onClick = { remote.newInternetLink() }) { Text(stringResource(Res.string.remote_internet_new)) }
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
    val image = ImageBitmap(matrix.width, matrix.height)
    val canvas = Canvas(image)
    val white = Paint().apply { color = Color.White }
    val black = Paint().apply { color = Color.Black }
    canvas.drawRect(0f, 0f, matrix.width.toFloat(), matrix.height.toFloat(), white)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) if (matrix.get(x, y)) canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, black)
    }
    return image
}

/** The widest the dialog gets: a phone's width, as the app's centred column on a computer. */
private val MAX_DIALOG_WIDTH = 520.dp
