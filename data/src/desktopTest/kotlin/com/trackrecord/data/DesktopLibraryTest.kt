package com.trackrecord.data

import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.create
import com.trackrecord.data.library.JaudiotaggerTagReader
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.library.NioFolderScanner
import com.trackrecord.data.library.RawTags
import com.trackrecord.engine.year.TagYears
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's tag reader and folder scanner (PLATFORMS.md phase 3) on the generated test library
 * (tools/make_test_library.py): the same expectations as TagReaderDeviceTest has for Media3 on Android.
 */
class DesktopLibraryTest {
    private val year = 2026
    private val mbid = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
    private val testlib = File(System.getProperty("trackrecord.testlib"))

    private fun read(name: String): RawTags = runBlocking { JaudiotaggerTagReader().read(File(testlib, name).toURI().toString()) }

    private fun years(tags: RawTags) = TagYears.extract(tags.fields, year)

    @Test
    fun `id3v24 with original date ufid isrc and cover`() {
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
    fun `id3v23 tory and tyer`() {
        val t = read("02 David Bowie - Space Oddity.mp3")
        assertEquals("Space Oddity", t.title)
        assertEquals(TagYears.Result(1969, 2009), years(t))
    }

    @Test
    fun `mp3 without year and with bogus year`() {
        assertEquals(TagYears.Result(null, null), years(read("03 Unknown - No Year.mp3")))
        assertEquals(TagYears.Result(null, null), years(read("05 Bad Year.mp3")))
    }

    @Test
    fun `id3 txxx originalyear`() {
        assertEquals(TagYears.Result(1977, 2017), years(read("06 TXXX Original.mp3")))
    }

    @Test
    fun `mp4 day and freeform atoms`() {
        val t = read("07 John Lennon - Imagine.m4a")
        assertEquals("Imagine", t.title)
        assertEquals("John Lennon", t.artist)
        assertEquals(TagYears.Result(1971, 2015), years(t))
        assertEquals(mbid, TagYears.recordingId(t.fields))
        assertEquals("GBAYE0601477", TagYears.isrc(t.fields))
        assertEquals(TagYears.Result(null, null), years(read("08 No Year.m4a")))
    }

    @Test
    fun `flac vorbis comments and picture`() {
        val t = read("09 Toto - Africa.flac")
        assertEquals("Africa", t.title)
        assertEquals(TagYears.Result(1982, 1999), years(t))
        assertEquals(mbid, TagYears.recordingId(t.fields))
        assertEquals("USSM18200521", TagYears.isrc(t.fields))
        assertTrue(t.hasCover)
    }

    @Test
    fun `ogg vorbis comments`() {
        val t = read("10 ABBA - Dancing Queen.ogg")
        assertEquals("Dancing Queen", t.title)
        assertEquals(TagYears.Result(null, 1976), years(t))
    }

    @Test
    fun `opus comments`() {
        val t = read("11 Beatles - Yesterday.opus")
        assertEquals("Yesterday", t.title)
        assertEquals("The Beatles", t.artist)
        assertEquals(TagYears.Result(1965, 2001), years(t))
        assertNull(TagYears.recordingId(t.fields))
        // Read from the last page by OpusTags, not by jaudiotagger.
        assertTrue(t.durationMs in 11_000..13_000, "duration ${t.durationMs}")
    }

    @Test
    fun `a folder is scanned into a desktop database`() = runBlocking {
        val folder = Files.createTempDirectory("trackrecord-db").toFile()
        val db = AppDatabase.create(folder)
        try {
            val library = LibraryRepository(db, NioFolderScanner(), JaudiotaggerTagReader(), currentYear = { year })
            val report = library.addFolder(NioFolderScanner.uriOf(testlib))
            assertEquals(12, report.added, "every song, also in the subfolder")
            val songs = library.songs.first()
            val queen = songs.single { it.song.title == "Bohemian Rhapsody (Remastered 2011)" }
            assertEquals(1975, queen.resolved.baseYear)
            assertTrue(queen.song.uri.startsWith("file:/"))
            assertEquals("Sub/12 Nirvana - Smells Like Teen Spirit.mp3", songs.single { it.song.title.contains("Teen Spirit") }.song.relativePath)
            assertEquals(12, library.rescanAll().unchanged, "a second scan finds nothing changed")
            assertTrue(db.playlists().excludedId() != null, "the new database has its Excluded songs playlist")
        } finally {
            db.close()
            folder.deleteRecursively()
        }
    }
}
