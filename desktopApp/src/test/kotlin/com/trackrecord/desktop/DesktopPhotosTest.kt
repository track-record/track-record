package com.trackrecord.desktop

import androidx.compose.ui.unit.IntRect
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Players' photos on the desktop (feedback round 55): a picture for the crop dialog, and the round avatar it gives. */
class DesktopPhotosTest {
    private val folder: File = Files.createTempDirectory("trackrecord-photos").toFile()
    private val photos = DesktopPhotos(folder)

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    /** A 3000 × 2000 picture: red on the left half, blue on the right. */
    private fun picture(): ByteArray {
        val surface = Surface.makeRasterN32Premul(3000, 2000)
        surface.canvas.drawRect(Rect.makeLTRB(0f, 0f, 1500f, 2000f), Paint().apply { color = Color.RED })
        surface.canvas.drawRect(Rect.makeLTRB(1500f, 0f, 3000f, 2000f), Paint().apply { color = Color.BLUE })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes
    }

    @Test
    fun `a large picture is scaled down for cropping, and the square becomes a round PNG`() {
        val image = assertNotNull(photos.decode(picture()))
        assertEquals(1536 to 1024, image.width to image.height, "shortest side 1024")
        // The left part's square: red throughout, transparent outside the circle.
        val path = assertNotNull(photos.save(image, IntRect(0, 0, 700, 700)))
        val saved = Image.makeFromEncoded(File(path).readBytes())
        assertEquals(256 to 256, saved.width to saved.height)
        val bitmap = Bitmap.makeFromImage(saved)
        assertEquals(0, Color.getA(bitmap.getColor(2, 2)), "corner left out")
        val centre = bitmap.getColor(128, 128)
        assertEquals(255, Color.getA(centre))
        assert(Color.getR(centre) > 200 && Color.getB(centre) < 50) { "red in the middle" }
        assertEquals(File(path).parentFile, folder)
    }

    @Test
    fun `anything else isn't a picture`() {
        assertNull(photos.decode("not a picture".toByteArray()))
    }
}
