package com.trackrecord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_cancel
import com.trackrecord.app.resources.camera_starting
import com.trackrecord.app.resources.camera_take
import com.trackrecord.app.resources.camera_unavailable
import com.trackrecord.app.resources.profiles_take_photo
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.OutlinedButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cef.browser.CefBrowser
import org.jetbrains.compose.resources.stringResource
import java.util.Base64
import org.jetbrains.skia.Image as SkiaImage

/**
 * The webcam for a player's photo (feedback round 55): desktop/camera.js in a hidden browser, opened for as long as
 * the dialog shows, so the camera is on only then. Its frames come as small JPEGs for the preview; [take] asks for one
 * at full size, which goes to [onPhoto].
 */
private class CameraSession(private val chromium: Chromium, private val onPhoto: (ImageBitmap) -> Unit) {
    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val port = PagePort("camera", ::received)
    private val browser: CefBrowser = chromium.open("desktop/camera.html", port)

    fun take() = port.send("""{"t":"take"}""")

    fun close() = chromium.close(browser)

    private fun received(text: String) {
        val m = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        when (m["t"]?.jsonPrimitive?.content) {
            "frame" -> _preview.value = image(m) ?: return
            "photo" -> image(m)?.let(onPhoto) ?: run { _failed.value = true }
            "failed" -> {
                Log.w("camera: ${m["reason"]?.jsonPrimitive?.content}")
                _failed.value = true
            }
        }
    }

    private fun image(m: JsonObject): ImageBitmap? = runCatching {
        SkiaImage.makeFromEncoded(Base64.getDecoder().decode(m.getValue("data").jsonPrimitive.content)).toComposeImageBitmap()
    }.getOrNull()
}

/** The camera's live picture with Take photo; [onPhoto] gets the photo, which then goes through the crop dialog. */
@Composable
fun CameraDialog(chromium: Chromium, onCancel: () -> Unit, onPhoto: (ImageBitmap) -> Unit) {
    val session = remember { CameraSession(chromium, onPhoto) }
    DisposableEffect(session) { onDispose { session.close() } }
    val preview by session.preview.collectAsState()
    val failed by session.failed.collectAsState()
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.profiles_take_photo), style = MaterialTheme.typography.headlineSmall)
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentAlignment = Alignment.Center) {
                    val picture = preview
                    when {
                        failed -> Text(stringResource(Res.string.camera_unavailable), style = MaterialTheme.typography.bodyLarge)
                        picture != null -> Image(picture, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(Res.string.camera_starting), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.action_cancel)) }
                    Button(onClick = session::take, enabled = preview != null && !failed, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.camera_take))
                    }
                }
            }
        }
    }
}
