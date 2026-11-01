package com.trackrecord.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntRect
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.util.UUID

/**
 * Players' photos on the desktop (feedback round 55), as ProfilePhotos does on Android: a picture upright and scaled
 * down for the crop dialog, and the chosen square saved as a round 256 px PNG in [folder].
 */
class DesktopPhotos(private val folder: File) {
    /** [bytes] (JPEG, PNG, WebP, …) upright, its shortest side at most [CROP_SOURCE]; null if they aren't a picture. */
    fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val origin = Codec.makeFromData(Data.makeFromBytes(bytes)).encodedOrigin
        val image = Image.makeFromEncoded(bytes)
        val scale = minOf(1f, CROP_SOURCE.toFloat() / minOf(image.width, image.height))
        val turned = origin == EncodedOrigin.RIGHT_TOP || origin == EncodedOrigin.LEFT_BOTTOM ||
            origin == EncodedOrigin.LEFT_TOP || origin == EncodedOrigin.RIGHT_BOTTOM
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(if (turned) h else w, if (turned) w else h)
        surface.canvas.apply {
            // The camera's orientation (EXIF), applied as Android's decoder does with ExifInterface.
            concat(origin.toMatrix(w, h))
            drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SMOOTH, null, true)
        }
        surface.makeImageSnapshot().toComposeImageBitmap()
    }.getOrNull()

    /** Saves the [crop] square of [image] as the round avatar; its path, or null if it couldn't be written. */
    fun save(image: ImageBitmap, crop: IntRect): String? = runCatching {
        val source = Image.makeFromBitmap(image.asSkiaBitmap())
        val surface = Surface.makeRasterN32Premul(SIZE, SIZE)
        surface.canvas.apply {
            clipRRect(RRect.makeXYWH(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), SIZE / 2f), true)
            drawImageRect(
                source,
                Rect.makeLTRB(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat()),
                Rect.makeWH(SIZE.toFloat(), SIZE.toFloat()),
                SMOOTH,
                null,
                true,
            )
        }
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes ?: return null
        folder.mkdirs()
        File(folder, "${UUID.randomUUID()}.png").apply { writeBytes(png) }.absolutePath
    }.getOrNull()

    private companion object {
        const val SIZE = 256

        /** Shortest side kept for cropping, so zooming in still looks sharp at 256 px. */
        const val CROP_SOURCE = 1024

        val SMOOTH = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
    }
}
