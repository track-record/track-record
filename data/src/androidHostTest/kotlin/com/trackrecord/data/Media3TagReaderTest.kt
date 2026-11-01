package com.trackrecord.data

import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.trackrecord.data.library.Media3TagReader
import com.trackrecord.engine.year.TagYears
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Media3's metadata entries as tag fields (split from LibraryLogicTest; Android only since PLATFORMS.md phase 3). */
class Media3TagReaderTest {
    private val year = 2026

    @Test
    fun `media3 metadata entries become tag fields`() {
        val ufid = "http://musicbrainz.org".toByteArray(Charsets.ISO_8859_1) + 0 + "b1a9c0e9-d987-4042-ae91-78d6a3267d69".toByteArray()
        val tags = Media3TagReader.toRawTags(
            listOf(
                TextInformationFrame("TIT2", null, listOf("Bohemian Rhapsody")),
                TextInformationFrame("TPE1", null, listOf("Queen")),
                TextInformationFrame("TDRC", null, listOf("2011")),
                TextInformationFrame("TDOR", null, listOf("1975-10-31")),
                TextInformationFrame("TXXX", "originalyear", listOf("1975")),
                TextInformationFrame("TSRC", null, listOf("GBUM71029604")),
                BinaryFrame("UFID", ufid),
                ApicFrame("image/jpeg", null, 3, byteArrayOf(1, 2, 3)),
            ),
            durationMs = 354_000,
        )
        assertEquals("Bohemian Rhapsody", tags.title)
        assertEquals("Queen", tags.artist)
        assertTrue(tags.hasCover)
        assertEquals(TagYears.Result(1975, 2011), TagYears.extract(tags.fields, year))
        assertEquals("b1a9c0e9-d987-4042-ae91-78d6a3267d69", TagYears.recordingId(tags.fields))
        assertEquals("GBUM71029604", TagYears.isrc(tags.fields))
    }

    @Test
    fun `vorbis and mp4 freeform entries`() {
        val vorbis = Media3TagReader.toRawTags(
            listOf(VorbisComment("TITLE", "Song"), VorbisComment("ARTIST", "Band"), VorbisComment("ORIGINALDATE", "1969"), VorbisComment("DATE", "2009")),
            1000,
        )
        assertEquals("Song", vorbis.title)
        assertEquals(TagYears.Result(1969, 2009), TagYears.extract(vorbis.fields, year))

        val mp4 = Media3TagReader.toRawTags(
            listOf(
                TextInformationFrame("TDRC", null, listOf("2015-01-01T00:00:00Z")),
                InternalFrame("com.apple.iTunes", "originaldate", "1971"),
                InternalFrame("com.apple.iTunes", "MusicBrainz Track Id", "b1a9c0e9-d987-4042-ae91-78d6a3267d69"),
            ),
            1000,
        )
        assertEquals(TagYears.Result(1971, 2015), TagYears.extract(mp4.fields, year))
        assertEquals("b1a9c0e9-d987-4042-ae91-78d6a3267d69", TagYears.recordingId(mp4.fields))
        assertNull(mp4.title)
    }

    @Test
    fun `ufid parsing`() {
        assertEquals("owner" to "id", Media3TagReader.parseUfid("owner".toByteArray() + 0 + "id".toByteArray()))
        assertNull(Media3TagReader.parseUfid("noterminator".toByteArray()))
    }
}
