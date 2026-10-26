package com.trackrecord.app.players

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Turns a camera or gallery picture into a circular avatar stored in app storage. */
object ProfilePhotos {
    private const val SIZE = 256

    /** Shortest side kept for cropping, so zooming in still looks sharp at 256 px. */
    private const val CROP_SOURCE = 1024

    private fun dir(context: Context) = File(context.filesDir, "profile_photos").apply { mkdirs() }

    /**
     * A gallery picture, upright and downsampled to keep enough detail for zooming in the crop dialog
     * (feedback round 16).
     */
    suspend fun decode(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            // Gallery photos can be very large.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CROP_SOURCE) sample *= 2
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val rotation = context.contentResolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0
            rotate(bitmap, rotation)
        }.getOrNull()
    }

    /** Saves the [crop] square of [source] (chosen in the crop dialog) as the round avatar. */
    suspend fun save(context: Context, source: Bitmap, crop: Rect): String? = withContext(Dispatchers.IO) {
        runCatching { save(context, source, crop as Rect?) }.getOrNull()
    }

    private fun exifRotation(exif: ExifInterface): Int = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /** Crops to [crop] (the centre square if null), scales to 256 px and masks to a circle (transparent corners). */
    private fun save(context: Context, source: Bitmap, crop: Rect?): String {
        val side = minOf(source.width, source.height)
        val src = crop ?: Rect((source.width - side) / 2, (source.height - side) / 2, (source.width + side) / 2, (source.height + side) / 2)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawOval(RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, src, Rect(0, 0, SIZE, SIZE), paint)
        val file = File(dir(context), "${UUID.randomUUID()}.png")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    /** Deletes a photo that was picked in the editor but never saved. */
    fun discard(path: String?) {
        if (path != null) File(path).delete()
    }
}
