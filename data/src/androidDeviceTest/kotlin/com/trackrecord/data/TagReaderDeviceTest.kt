package com.trackrecord.data

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackrecord.data.library.Media3TagReader
import com.trackrecord.data.library.RawTags
import com.trackrecord.engine.year.TagYears
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reads the generated test library (tools/make_test_library.py) with Media3 on a real device or
 * emulator, covering ID3v2.3/2.4, MP4, FLAC, Ogg Vorbis and Opus.
 */
@RunWith(AndroidJUnit4::class)
class TagReaderDeviceTest {
    private val year = 2026
    private val mbid = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
    private val context: Context = InstrumentationRegistry.getInstrumentation().context
    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun read(asset: String): RawTags = runBlocking {
        val file = File(target.cacheDir, asset.substringAfterLast('/'))
        context.assets.open("testlib/$asset").use { input -> file.outputStream().use { input.copyTo(it) } }
        Media3TagReader(target).read(Uri.fromFile(file).toString())
    }

    private fun years(tags: RawTags) = TagYears.extract(tags.fields, year)

    @Test
    fun id3v24_with_original_date_ufid_isrc_and_cover() {
        val t = read("01 Queen - Bohemian Rhapsody.mp3")
        assertEquals("Bohemian Rhapsody (Remastered 2011)", t.title)
        assertEquals("Queen", t.artist)
        assertEquals("A Night at the Opera", t.album)
        assertEquals(TagYears.Result(1975, 2011), years(t))
        assertEquals(mbid, TagYears.recordingId(t.fields))
        assertEquals("GBUM71029604", TagYears.isrc(t.fields))
        assertTrue(t.hasCover)
        assertTrue(t.durationMs in 11_000..13_000, "duration ${t.durationMs}")
    }

    @Test
    fun id3v23_tory_and_tyer() {
        val t = read("02 David Bowie - Space Oddity.mp3")
        assertEquals("Space Oddity", t.title)
        assertEquals(TagYears.Result(1969, 2009), years(t))
    }

    @Test
    fun mp3_without_year_and_with_bogus_year() {
        assertEquals(TagYears.Result(null, null), years(read("03 Unknown - No Year.mp3")))
        assertEquals(TagYears.Result(null, null), years(read("05 Bad Year.mp3")))
    }

    @Test
    fun id3_txxx_originalyear() {
        assertEquals(TagYears.Result(1977, 2017), years(read("06 TXXX Original.mp3")))
    }

    @Test
    fun mp4_day_and_freeform_atoms() {
        val t = read("07 John Lennon - Imagine.m4a")
        assertEquals("Imagine", t.title)
        assertEquals("John Lennon", t.artist)
        assertEquals(TagYears.Result(1971, 2015), years(t))
        assertEquals(mbid, TagYears.recordingId(t.fields))
        assertEquals("GBAYE0601477", TagYears.isrc(t.fields))
        assertEquals(TagYears.Result(null, null), years(read("08 No Year.m4a")))
    }

    @Test
    fun flac_vorbis_comments_and_picture() {
        val t = read("09 Toto - Africa.flac")
        assertEquals("Africa", t.title)
        assertEquals(TagYears.Result(1982, 1999), years(t))
        assertEquals(mbid, TagYears.recordingId(t.fields))
        assertEquals("USSM18200521", TagYears.isrc(t.fields))
        assertTrue(t.hasCover)
    }

    @Test
    fun ogg_vorbis_comments() {
        val t = read("10 ABBA - Dancing Queen.ogg")
        assertEquals("Dancing Queen", t.title)
        assertEquals(TagYears.Result(null, 1976), years(t))
    }

    @Test
    fun opus_comments() {
        val t = read("11 Beatles - Yesterday.opus")
        assertEquals("Yesterday", t.title)
        assertEquals("The Beatles", t.artist)
        assertEquals(TagYears.Result(1965, 2001), years(t))
        assertNull(TagYears.recordingId(t.fields))
    }
}
