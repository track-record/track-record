package com.trackrecord.data

import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.trackrecord.data.db.FolderEntity
import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongOverrideEntity
import com.trackrecord.data.db.SongScanState
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.library.AudioFiles
import com.trackrecord.data.library.FoundFile
import com.trackrecord.data.library.LibraryQuery
import com.trackrecord.data.library.LibrarySong
import com.trackrecord.data.library.LibraryStats
import com.trackrecord.data.library.Media3TagReader
import com.trackrecord.data.library.ScanPlanner
import com.trackrecord.data.library.SongStatus
import com.trackrecord.data.library.Verification
import com.trackrecord.data.library.filtered
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import com.trackrecord.data.playlists.BuiltInPlaylists
import com.trackrecord.data.sources.AudioRef
import com.trackrecord.data.sources.AudioResolver
import com.trackrecord.engine.year.TagYears
import com.trackrecord.engine.year.YearSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryLogicTest {
    private val year = 2026

    @Test
    fun `audio file extensions`() {
        for (name in listOf("a.mp3", "b.M4A", "c.aac", "d.flac", "e.ogg", "f.opus")) assertTrue(AudioFiles.isAudio(name), name)
        for (name in listOf("cover.jpg", "notes.txt", "x.wav", "._a.mp3", "noext")) assertFalse(AudioFiles.isAudio(name), name)
    }

    @Test
    fun `scan plan skips unchanged files by modification time and size`() {
        val existing = listOf(
            SongScanState(1, "u1", lastModified = 100, sizeBytes = 10),
            SongScanState(2, "u2", lastModified = 100, sizeBytes = 10),
            SongScanState(3, "gone", lastModified = 100, sizeBytes = 10),
        )
        val found = listOf(
            FoundFile("u1", "a.mp3", 100, 10), // unchanged
            FoundFile("u2", "b.mp3", 200, 10), // modified
            FoundFile("u4", "c.mp3", 100, 10), // new
        )
        val plan = ScanPlanner.plan(existing, found)
        assertEquals(1, plan.unchanged)
        assertEquals(listOf("u2" to 2L, "u4" to 0L), plan.toRead.map { it.first.uri to it.second })
        assertEquals(listOf(3L), plan.deletions)
    }

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

    private fun song(id: Long, uri: String, original: Int? = null, tag: Int? = null, folderId: Long = 1, title: String = "T$id") = SongEntity(
        id = id, folderId = folderId, uri = uri, relativePath = "$title.mp3", lastModified = 0, sizeBytes = 0,
        title = title, artist = "Artist", album = null, durationMs = 200_500, tagOriginalYear = original, tagYear = tag,
        mbRecordingId = null, isrc = null, hasCover = false,
    )

    private fun override(uri: String, key: String = "x", year: Int? = null) =
        SongOverrideEntity(songUri = uri, matchKey = key, manualYear = year, updatedAt = 0)

    @Test
    fun `verify by fingerprint leaves out streamed songs a name check already covered`() {
        val local = song(1, "u1", tag = 1990)
        val streamed = song(2, "deezer:2", tag = 1990).copy(folderId = null, source = SongSource.DEEZER)
        fun needs(s: SongEntity, lookup: MbCacheEntity?) =
            LibrarySong.assemble(listOf(s), emptyList(), listOf(FolderEntity(1, "tree", "Music")), year, lookups = listOfNotNull(lookup).associateBy { MusicBrainzLookup.keyOf(s) }).single().needsFingerprintCheck
        val byName = MbCacheEntity(songKey = "k", year = 1990, checkedAt = 0, lookupVersion = MusicBrainzLookup.LOOKUP_VERSION, nameSearched = true)
        assertTrue(needs(local, byName), "a local file still gets its fingerprint")
        assertFalse(needs(local, byName.copy(fingerprintChecked = true)))
        assertFalse(needs(streamed, byName), "nothing more to check without a file")
        assertTrue(needs(streamed, null), "never checked")
        assertTrue(needs(streamed, byName.copy(nameSearched = false)), "settled by its ISRC without a name search, before feedback round 47")
        assertTrue(needs(streamed, byName.copy(lookupVersion = 0)), "only looked up before verification existed")
        assertFalse(needs(streamed, byName.copy(nameSearched = false, fingerprintChecked = true)), "a fingerprint run always searched by name")
    }

    @Test
    fun `a result without the name search shows as not verified until checked again`() {
        val s = song(1, "u1", tag = 1990)
        fun status(lookup: MbCacheEntity) =
            LibrarySong.assemble(listOf(s), emptyList(), listOf(FolderEntity(1, "tree", "Music")), year, lookups = mapOf(MusicBrainzLookup.keyOf(s) to lookup)).single().verification
        val old = MbCacheEntity(songKey = "k", year = 1990, checkedAt = 0, lookupVersion = MusicBrainzLookup.LOOKUP_VERSION)
        assertEquals(Verification.NONE, status(old), "settled by its ids before feedback round 47")
        assertEquals(Verification.NAME, status(old.copy(nameSearched = true)))
        assertEquals(Verification.FINGERPRINT, status(old.copy(fingerprintChecked = true)))
    }

    @Test
    fun `manual edits are found by uri, then by artist title and duration`() {
        val moved = song(2, "new-uri", tag = 1990, title = "Moved")
        val songs = listOf(song(1, "u1", tag = 2011), moved, song(3, "u3"))
        val overrides = listOf(
            override("u1", year = 1975),
            override("old-uri", key = LibrarySong.matchKey("Artist", "Moved", 200_999), year = 1980),
        )
        val lib = LibrarySong.assemble(songs, overrides, listOf(FolderEntity(1, "tree", "Music")), year)
        assertEquals(1975, lib[0].resolved.year)
        assertEquals(YearSource.MANUAL, lib[0].resolved.source)
        assertEquals(1980, lib[1].resolved.year)
        assertTrue(lib[2].missingYear)
        assertFalse(lib[2].playable)
    }

    @Test
    fun `filters and search`() {
        val songs = listOf(song(1, "u1", tag = 2011), song(2, "u2"), song(3, "u3", original = 1970, tag = 1999, title = "Heroes"))
        val lib = LibrarySong.assemble(
            songs, listOf(override("u1", year = 1975)), listOf(FolderEntity(1, "tree", "Music")), year,
            playlists = mapOf(3L to setOf(7L)),
        )
        fun ids(q: LibraryQuery) = lib.filtered(q).map { it.id }
        assertEquals(listOf(1L, 2L, 3L), ids(LibraryQuery()))
        assertEquals(listOf(2L), ids(LibraryQuery(status = SongStatus.MISSING_YEAR)))
        assertEquals(listOf(1L), ids(LibraryQuery(status = SongStatus.EDITED)))
        assertEquals(listOf(3L), ids(LibraryQuery(text = "hero")))
        assertEquals(listOf(3L), ids(LibraryQuery(playlistId = 7L)))
        assertEquals(listOf(1L, 3L), ids(LibraryQuery(status = SongStatus.PLAYABLE)))
        assertEquals(listOf(1L, 2L, 3L), ids(LibraryQuery(status = SongStatus.NOT_CHECKED)))
        // Year range uses the effective year (1975 for the edited song, 1970 for Heroes) and drops songs without one.
        assertEquals(listOf(3L), ids(LibraryQuery(yearFrom = 1960, yearTo = 1972)))
        assertEquals(2, LibraryQuery(status = SongStatus.EXCLUDED, playlistId = 1).activeFilters)
        // Sources disagree needs MusicBrainz; tag original vs tag year alone is not a disagreement
        assertEquals(emptyList(), ids(LibraryQuery(status = SongStatus.SOURCES_DISAGREE)))
    }

    @Test
    fun `songs on Excluded songs and unavailable folders are not playable`() {
        val songs = listOf(song(1, "u1", tag = 2000), song(2, "u2", tag = 2000, folderId = 2))
        val folders = listOf(FolderEntity(1, "a", "A"), FolderEntity(2, "b", "B", available = false))
        val lib = LibrarySong.assemble(songs, emptyList(), folders, year, playlists = mapOf(1L to setOf(BuiltInPlaylists.EXCLUDED)))
        assertTrue(lib[0].excluded)
        assertFalse(lib[0].playable)
        assertFalse(lib[1].playable)
    }

    @Test
    fun `stats count distinct artists and songs per year`() {
        val songs = listOf(
            song(1, "u1", tag = 1990).copy(artist = "Queen"),
            song(2, "u2", tag = 1990).copy(artist = "QUEEN feat. David Bowie"),
            song(3, "u3", tag = 1993).copy(artist = "Oasis"),
            song(4, "u4", tag = null).copy(artist = null),
        )
        val stats = LibraryStats.of(LibrarySong.assemble(songs, emptyList(), listOf(FolderEntity(1, "a", "A")), year))
        assertEquals(4, stats.songs)
        assertEquals(2, stats.artists)
        assertEquals(3, stats.withYear)
        assertEquals(1990, stats.minYear)
        assertEquals(1993, stats.maxYear)
        assertEquals(listOf(1990 to 2, 1991 to 0, 1992 to 0, 1993 to 1), stats.perYear)
        assertEquals(1990 to 2, stats.busiestYear)
        assertEquals(null, LibraryStats.of(emptyList()).busiestYear)
    }

    @Test
    fun `local files are on the built-in playlist`() {
        val local = song(1, "u1", tag = 2000)
        val deezer = song(2, AudioRef.deezerUri(5), tag = 2000).copy(folderId = null, source = SongSource.DEEZER, deezerId = 5)
        val lib = LibrarySong.assemble(listOf(local, deezer), emptyList(), listOf(FolderEntity(1, "a", "A")), year)
        assertTrue(BuiltInPlaylists.LOCAL_FILES in lib[0].playlistIds)
        assertFalse(BuiltInPlaylists.LOCAL_FILES in lib[1].playlistIds)
        assertEquals(listOf(1L), lib.filtered(LibraryQuery(playlistId = BuiltInPlaylists.LOCAL_FILES)).map { it.id })
    }

    @Test
    fun `streamed songs are on their own built-in playlist`() {
        val local = song(1, "u1", tag = 2000)
        val deezer = song(2, AudioRef.deezerUri(5), tag = 2000).copy(folderId = null, source = SongSource.DEEZER, deezerId = 5)
        val lib = LibrarySong.assemble(listOf(local, deezer), emptyList(), listOf(FolderEntity(1, "a", "A")), year)
        assertFalse(BuiltInPlaylists.STREAMED in lib[0].playlistIds)
        assertTrue(BuiltInPlaylists.STREAMED in lib[1].playlistIds)
        assertEquals(listOf(2L), lib.filtered(LibraryQuery(playlistId = BuiltInPlaylists.STREAMED)).map { it.id })
    }

    @Test
    fun `streamed songs have no folder and count as reachable`() {
        val deezer = song(3, AudioRef.deezerUri(985745702), tag = 1995).copy(folderId = null, source = SongSource.DEEZER, deezerId = 985745702)
        val lib = LibrarySong.assemble(listOf(deezer), emptyList(), emptyList(), year)
        assertTrue(lib.single().playable)
    }

    @Test
    fun `audio references are local URIs or Deezer tracks`() = runBlocking {
        assertEquals(AudioRef.Deezer(985745702), AudioRef.parse("deezer:track:985745702"))
        val local = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/x.mp3"
        assertEquals(AudioRef.Local(local), AudioRef.parse(local))
        assertEquals(AudioRef.Local("deezer:track:abc"), AudioRef.parse("deezer:track:abc"))
        assertEquals(local, AudioResolver.LocalOnly.playableUri(local))
        assertNull(AudioResolver.LocalOnly.playableUri("deezer:track:1"))
    }
}
