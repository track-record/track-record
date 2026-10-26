package com.trackrecord.app.players

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trackrecord.app.R
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.OutlinedButton
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Choose the part of a photo for a profile picture (feedback round 16): the photo fills a square, the
 * round frame shows what is kept; drag to move, pinch to zoom (up to 5×). [onDone] gets the chosen
 * square in the bitmap's pixels.
 */
@Composable
fun PhotoCropDialog(bitmap: Bitmap, onCancel: () -> Unit, onDone: (Rect) -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var side by remember { mutableFloatStateOf(1f) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                Text(stringResource(R.string.photo_crop_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.photo_crop_hint), style = MaterialTheme.typography.bodyMedium)
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    side = constraints.maxWidth.toFloat()
                    // At zoom 1 the photo just covers the square.
                    val base = side / min(w, h)
                    fun clamp(o: Offset, z: Float): Offset {
                        val k = base * z
                        val maxX = (w * k - side) / 2f
                        val maxY = (h * k - side) / 2f
                        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                    }
                    Canvas(
                        Modifier.fillMaxSize().clipToBounds().pointerInput(bitmap) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val z = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                                zoom = z
                                offset = clamp(offset + pan, z)
                            }
                        },
                    ) {
                        val k = base * zoom
                        withTransform({
                            translate(size.width / 2f + offset.x, size.height / 2f + offset.y)
                            scale(k, k, pivot = Offset.Zero)
                        }) {
                            drawImage(image, topLeft = Offset(-w / 2f, -h / 2f))
                        }
                        // Dim what falls outside the round picture.
                        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
                        clipPath(circle, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
                        drawCircle(Color.White, radius = size.minDimension / 2f, style = Stroke(2.dp.toPx()))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                    Button(onClick = { onDone(cropRect(w, h, side, zoom, offset)) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.photo_crop_use))
                    }
                }
            }
        }
    }
}

private const val MAX_ZOOM = 5f

/** The square of the bitmap shown in the frame, for a frame of [side] px at [zoom] and [offset]. */
private fun cropRect(w: Float, h: Float, side: Float, zoom: Float, offset: Offset): Rect {
    val k = side / min(w, h) * zoom
    val cropSide = side / k
    val cx = w / 2f - offset.x / k
    val cy = h / 2f - offset.y / k
    val left = (cx - cropSide / 2f).coerceIn(0f, w - cropSide)
    val top = (cy - cropSide / 2f).coerceIn(0f, h - cropSide)
    return Rect(left.roundToInt(), top.roundToInt(), (left + cropSide).roundToInt(), (top + cropSide).roundToInt())
}
