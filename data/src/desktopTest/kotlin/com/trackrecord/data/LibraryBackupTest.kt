package com.trackrecord.data

import com.trackrecord.data.backup.BackupFormatException
import com.trackrecord.data.backup.BackupSource
import com.trackrecord.data.backup.LibraryBackup
import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.FolderEntity
import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.db.PlayerGameEntity
import com.trackrecord.data.db.PlayerRoundEntity
import com.trackrecord.data.db.PlaylistEntity
import com.trackrecord.data.db.PlaylistKind
import com.trackrecord.data.db.PlaylistSongEntity
import com.trackrecord.data.db.ProfileEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongOverrideEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.db.create
import com.trackrecord.data.library.FolderScanner
import com.trackrecord.data.library.FoundFile
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.library.LibrarySong
import com.trackrecord.data.library.RawTags
import com.trackrecord.data.library.TagReader
import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.data.settings.ThemeChoice
import com.trackrecord.data.sources.AudioRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Library export and import (PLATFORMS.md section 5): a round trip into an empty and into a different library. */
class LibraryBackupTest {
    private val folders = ArrayList<File>()

    /** A library in its own folder: database, settings and photos, as on a device. */
    private inner class Device {
        val folder: File = Files.createTempDirectory("trackrecord-device").toFile().also { folders += it }
        val db = AppDatabase.create(folder)
        val settings = SettingsRepository(folder)
        val photos = File(folder, "profile_photos")
        val backup = LibraryBackup(db, settings, photos, clock = { 1_000 })

        fun export(withSettings: Boolean = true): ByteArray = runBlocking {
            ByteArrayOutputStream().also { backup.export(it, BackupSource("0.4.1", "Phone"), withSettings) }.toByteArray()
        }

        fun import(bytes: ByteArray, withSettings: Boolean = false) = runBlocking { backup.import(bytes.inputStream(), withSettings) }

        fun close() = db.close()
    }

    private val devices = ArrayList<Device>()

    private fun device() = Device().also { devices += it }

    @AfterTest
    fun cleanUp() {
        devices.forEach { it.close() }
        folders.forEach { it.deleteRecursively() }
    }

    private fun local(folderId: Long, name: String, artist: String, title: String, durationMs: Long) = SongEntity(
        folderId = folderId, uri = "content://phone/$name", relativePath = "Music/$name", lastModified = 1, sizeBytes = 1, title = title,
        artist = artist, album = null, durationMs = durationMs, tagOriginalYear = 1980, tagYear = null, mbRecordingId = null, isrc = null, hasCover = false,
    )

    private fun streamed(deezerId: Long, title: String) = SongEntity(
        folderId = null, uri = AudioRef.deezerUri(deezerId), relativePath = "", lastModified = 1, sizeBytes = 0, title = title, artist = "Band",
        album = "LP", durationMs = 30_000, tagOriginalYear = null, tagYear = 1999, mbRecordingId = null, isrc = "GBAAA9900001", hasCover = false,
        source = SongSource.DEEZER, deezerId = deezerId,
    )

    /** The phone: two local songs, two streamed ones, playlists, a year correction each, a lookup and a player. */
    private fun phone(): Device = device().apply {
        runBlocking {
            val folder = db.folders().insert(FolderEntity(treeUri = "content://phone/tree", displayName = "Music"))
            val queen = db.songs().insert(local(folder, "queen.mp3", "Queen", "Bohemian Rhapsody", 354_300))
            val nena = db.songs().insert(local(folder, "nena.mp3", "Nena", "99 Luftballons", 232_900))
            val one = db.songs().insert(streamed(11, "One"))
            val two = db.songs().insert(streamed(22, "Two"))
            val party = db.playlists().insert(PlaylistEntity(name = "Party", createdAt = 5, deezerPlaylistId = 99))
            db.playlists().addSongs(listOf(PlaylistSongEntity(party, queen), PlaylistSongEntity(party, one), PlaylistSongEntity(party, two)))
            val favorites = db.playlists().insert(PlaylistEntity(name = "Favorites", createdAt = 6, kind = PlaylistKind.FAVORITES))
            db.playlists().addSongs(listOf(PlaylistSongEntity(favorites, nena)))
            db.playlists().addSongs(listOf(PlaylistSongEntity(db.playlists().excludedId()!!, two)))
            db.overrides().upsert(SongOverrideEntity(songUri = "content://phone/queen.mp3", matchKey = LibrarySong.matchKey("Queen", "Bohemian Rhapsody", 354_300), manualYear = 1975, updatedAt = 50))
            db.overrides().upsert(SongOverrideEntity(songUri = AudioRef.deezerUri(11), matchKey = LibrarySong.matchKey("Band", "One", 30_000), manualYear = 1991, updatedAt = 50))
            db.mbCache().put(MbCacheEntity("queen|bohemian rhapsody||", 1975, checkedAt = 70, lookupVersion = 3, nameSearched = true))
            photos.mkdirs()
            val photo = File(photos, "anna.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2)) }
            val anna = db.profiles().upsert(ProfileEntity(name = "Anna", initials = "AN", colorIndex = 3, photoPath = photo.path, createdAt = 8))
            db.playerStats().insertRounds(listOf(PlayerRoundEntity(0, anna, game = 7, round = 1, mode = "TIMELINE", year = 1980, played = true, yearsOff = 0, namingChance = true, named = true, won = true)))
            db.playerStats().insertGames(listOf(PlayerGameEntity(0, anna, game = 7, mode = "TIMELINE", won = true, finishedAt = 9)))
            settings.update { it.copy(theme = ThemeChoice.DARK, clipLimitSeconds = 30) }
        }
    }

    @Test
    fun `into an empty library, then the same file again changes nothing`() = runBlocking {
        val file = phone().export()
        val pc = device()
        val summary = pc.import(file)
        assertEquals(2, summary.playlists, "Party and Favorites; Excluded songs isn't counted")
        assertEquals(2, summary.streamedSongs)
        assertEquals(1, summary.players)
        assertEquals(2, summary.yearCorrections)
        assertEquals(2, summary.localMissing, "Queen and Nena aren't on the PC")

        val playlists = pc.db.playlists().getAll()
        val party = playlists.single { it.name == "Party" }
        assertEquals(99, party.deezerPlaylistId)
        assertEquals(setOf(11L, 22L), pc.db.playlists().songIds(party.id).map { pc.db.songs().get(it)!!.deezerId }.toSet())
        assertEquals(listOf(22L), pc.db.playlists().songIds(pc.db.playlists().excludedId()!!).map { pc.db.songs().get(it)!!.deezerId })
        assertNotNull(playlists.singleOrNull { it.kind == PlaylistKind.FAVORITES })
        assertEquals(2, pc.db.playlists().pending().size, "Queen's place on Party, Nena's on Favorites")
        val overrides = pc.db.overrides().getAll().associateBy { it.songUri }
        assertEquals(1991, overrides[AudioRef.deezerUri(11)]!!.manualYear)
        assertEquals(1975, overrides.values.single { it.songUri.startsWith(LibraryRepository.IMPORTED_URI) }.manualYear)
        assertEquals(1975, pc.db.mbCache().get("queen|bohemian rhapsody||")!!.year)
        val anna = pc.db.profiles().getAll().single()
        assertTrue(File(anna.photoPath!!).isFile && File(anna.photoPath!!).parentFile == pc.photos)
        assertEquals(1, pc.db.playerStats().allRounds().size)
        assertEquals(1, pc.db.playerStats().allGames().size)
        assertEquals(ThemeChoice.LOW_POLY, pc.settings.current().theme, "settings only when asked for")

        suspend fun counts() = listOf(pc.db.songs().getAll().size, pc.db.playlists().getAll().size, pc.db.playlists().storedMemberships().size, pc.db.playlists().pending().size, pc.db.overrides().getAll().size, pc.db.profiles().getAll().size, pc.db.playerStats().allRounds().size, pc.photos.list()!!.size)
        val before = counts()
        val again = pc.import(file)
        assertEquals(before, counts())
        assertEquals(listOf(0, 0, 0, 0), listOf(again.playlists, again.streamedSongs, again.players, again.yearCorrections))
    }

    @Test
    fun `local songs join their playlists and keep their years once a scan finds them`() = runBlocking {
        val pc = device()
        pc.import(phone().export())
        // The PC's tag reader measures the files a little differently than the phone's.
        val files = mapOf("queen.mp3" to Triple("Queen", "Bohemian Rhapsody", 355_100L), "nena.mp3" to Triple("Nena", "99 Luftballons", 232_100L))
        val scanner = object : FolderScanner {
            override fun scan(treeUri: String) = files.keys.map { FoundFile("file:///C:/Music/$it", it, 1, 1) }
            override fun displayName(treeUri: String) = "Music"
        }
        val tags = object : TagReader {
            override suspend fun read(uri: String): RawTags {
                val (artist, title, duration) = files.getValue(uri.substringAfterLast('/'))
                return RawTags(mapOf("TPE1" to artist, "TIT2" to title), duration, false)
            }
        }
        val library = LibraryRepository(pc.db, scanner, tags, currentYear = { 2026 })
        assertEquals(2, library.addFolder("file:///C:/Music").added)
        assertTrue(pc.db.playlists().pending().isEmpty())
        val party = pc.db.playlists().getAll().single { it.name == "Party" }
        val titles = pc.db.playlists().songIds(party.id).map { pc.db.songs().get(it)!!.title }.toSet()
        assertEquals(setOf("Bohemian Rhapsody", "One", "Two"), titles)
        val favorites = pc.db.playlists().getAll().single { it.kind == PlaylistKind.FAVORITES }
        assertEquals(listOf("99 Luftballons"), pc.db.playlists().songIds(favorites.id).map { pc.db.songs().get(it)!!.title })
        val queen = library.songs.first().first { it.song.title == "Bohemian Rhapsody" }
        assertEquals(1975, queen.manualYear, "the year correction follows the song")
    }

    @Test
    fun `into a different library newer year corrections win and players and playlists are matched by name`() = runBlocking {
        val file = phone().export()
        val other = device()
        runBlocking {
            val party = other.db.playlists().insert(PlaylistEntity(name = "Party", createdAt = 1))
            val three = other.db.songs().insert(streamed(33, "Three"))
            other.db.playlists().addSongs(listOf(PlaylistSongEntity(party, three)))
            other.db.overrides().upsert(SongOverrideEntity(songUri = AudioRef.deezerUri(11), matchKey = LibrarySong.matchKey("Band", "One", 30_000), manualYear = 1992, updatedAt = 99))
            other.db.mbCache().put(MbCacheEntity("queen|bohemian rhapsody||", 1974, checkedAt = 10))
            other.db.profiles().upsert(ProfileEntity(name = "anna", initials = "A", colorIndex = 1, photoPath = null, createdAt = 2))
        }
        val summary = other.import(file, withSettings = true)
        assertEquals(1, summary.yearCorrections, "only Queen's: the PC's own correction of One is newer")
        assertEquals(0, summary.players)
        assertTrue(summary.settings)
        val party = other.db.playlists().getAll().filter { it.name == "Party" }
        assertEquals(1, party.size)
        assertEquals(setOf(11L, 22L, 33L), other.db.playlists().songIds(party.single().id).map { other.db.songs().get(it)!!.deezerId }.toSet())
        assertEquals(1992, other.db.overrides().findByUri(AudioRef.deezerUri(11))!!.manualYear)
        assertEquals(1975, other.db.mbCache().get("queen|bohemian rhapsody||")!!.year, "the newer lookup")
        val anna = other.db.profiles().getAll().single()
        assertEquals("anna", anna.name)
        assertTrue(anna.photoPath != null, "her photo was missing here")
        assertEquals(1, other.db.playerStats().allRounds().size)
        assertEquals(ThemeChoice.DARK, other.settings.current().theme)
        assertEquals(30, other.settings.current().clipLimitSeconds)
    }

    @Test
    fun `a file that isn't an export, or is from a newer version, changes nothing`() {
        val pc = device()
        assertFailsWith<BackupFormatException> { pc.import("not a zip".toByteArray()) }
        val newer = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"format":2,"appVersion":"9","exportedAt":1,"device":"x"}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assertFailsWith<BackupFormatException> { pc.import(newer) }
        runBlocking { assertTrue(pc.db.songs().getAll().isEmpty()) }
    }
}
