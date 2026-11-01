package com.trackrecord.data.backup

import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.db.PendingPlaylistSongEntity
import com.trackrecord.data.db.PlayerGameEntity
import com.trackrecord.data.db.PlayerRoundEntity
import com.trackrecord.data.db.PlaylistEntity
import com.trackrecord.data.db.PlaylistKind
import com.trackrecord.data.db.PlaylistSongEntity
import com.trackrecord.data.db.ProfileEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongOverrideEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.db.inTransaction
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.library.LibrarySong
import com.trackrecord.data.settings.AppSettings
import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.data.sources.AudioRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Who made a library export. */
data class BackupSource(val appVersion: String, val device: String)

/** What an import added or changed (PLATFORMS.md section 5). */
data class ImportSummary(
    /** Playlists created or given songs ("Excluded songs" not counted). */
    val playlists: Int,
    val streamedSongs: Int,
    val players: Int,
    val yearCorrections: Int,
    /** Local songs on the imported playlists or with a year correction that this device doesn't have yet. */
    val localMissing: Int,
    val settings: Boolean,
)

/** The file isn't a library export, or one from a newer version of the app. */
class BackupFormatException(message: String) : Exception(message)

/**
 * Library export and import (PLATFORMS.md section 5): a `.trackrecord` file, a ZIP of `manifest.json`,
 * `library.json` and the players' photos, never music. An import only adds or updates, in one transaction; local
 * songs that aren't on this device keep their playlist places and year corrections until a scan finds them.
 */
class LibraryBackup(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    /** Where profile photos are kept; imported ones are written there. */
    private val photoFolder: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** Writes the library to [out]; [onProgress] gets done and total steps. */
    suspend fun export(out: OutputStream, source: BackupSource, withSettings: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val total = 4
        onProgress(0, total)
        val songs = db.songs().getAll()
        val byId = songs.associateBy { it.id }
        val streamed = songs.filter { it.source == SongSource.DEEZER && it.deezerId != null }
        val local = songs.filter { it.source == SongSource.LOCAL }
        val members = db.playlists().storedMemberships().groupBy { it.playlistId }
        val pending = db.playlists().pending().groupBy { it.playlistId }
        val playlists = db.playlists().getAll().map { p ->
            val onIt = members[p.id].orEmpty().mapNotNull { byId[it.songId] }
            BackupPlaylist(
                name = p.name,
                kind = p.kind.name,
                createdAt = p.createdAt,
                deezerPlaylistId = p.deezerPlaylistId,
                streamed = onIt.filter { it.source == SongSource.DEEZER }.mapNotNull { it.deezerId },
                local = onIt.filter { it.source == SongSource.LOCAL }.map(::keyOf) + pending[p.id].orEmpty().map { it.matchKey },
            )
        }
        currentCoroutineContext().ensureActive()
        onProgress(1, total)
        val years = db.overrides().getAll().mapNotNull { o ->
            val year = o.manualYear ?: return@mapNotNull null
            BackupYear(o.matchKey, (AudioRef.parse(o.songUri) as? AudioRef.Deezer)?.trackId, year, o.updatedAt)
        }
        val rounds = db.playerStats().allRounds().groupBy { it.profileId }
        val games = db.playerStats().allGames().groupBy { it.profileId }
        val photos = LinkedHashMap<String, ByteArray>()
        val players = db.profiles().getAll().map { p ->
            val photo = p.photoPath?.let(::File)?.takeIf { it.isFile }?.let { file ->
                "photos/${photos.size + 1}.${file.extension.ifBlank { "png" }}".also { photos[it] = file.readBytes() }
            }
            BackupPlayer(
                name = p.name, initials = p.initials, colorIndex = p.colorIndex, createdAt = p.createdAt, photo = photo,
                rounds = rounds[p.id].orEmpty().map { BackupRound(it.game, it.round, it.mode, it.year, it.played, it.yearsOff, it.namingChance, it.named, it.won) },
                games = games[p.id].orEmpty().map { BackupGame(it.game, it.mode, it.won, it.finishedAt) },
            )
        }
        currentCoroutineContext().ensureActive()
        onProgress(2, total)
        val library = BackupLibrary(
            streamed = streamed.map { BackupStreamed(it.deezerId!!, it.title, it.artist, it.album, it.durationMs, it.tagOriginalYear, it.tagYear, it.isrc, it.mbRecordingId) },
            local = local.map { BackupLocal(keyOf(it), it.artist, it.title, it.durationMs, it.relativePath.substringAfterLast('/')) },
            playlists = playlists,
            years = years,
            lookups = db.mbCache().getAll().map(BackupLookup::from),
            players = players,
            settings = if (withSettings) json.encodeToString(AppSettings.serializer(), settings.current()) else null,
        )
        val manifest = BackupManifest(FORMAT, source.appVersion, clock(), source.device)
        ZipOutputStream(out).use { zip ->
            zip.put(MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.put(LIBRARY, json.encodeToString(BackupLibrary.serializer(), library).toByteArray())
            onProgress(3, total)
            for ((name, bytes) in photos) zip.put(name, bytes)
        }
        onProgress(total, total)
    }

    /** Reads an export from [input]; the library's own settings stay unless [withSettings]. */
    suspend fun import(input: InputStream, withSettings: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }): ImportSummary {
        val (library, photos) = read(input)
        val total = library.streamed.size + library.playlists.size + library.years.size + library.lookups.size + library.players.size
        var done = 0
        suspend fun step() {
            currentCoroutineContext().ensureActive()
            if (++done % PROGRESS_STEP == 0 || done == total) onProgress(done, total)
        }
        onProgress(0, total)
        val written = ArrayList<File>()
        val summary = try {
            db.inTransaction {
                // Streamed songs, by Deezer id.
                var newStreamed = 0
                val deezerIds = HashMap<Long, Long>()
                for (s in library.streamed) {
                    deezerIds[s.deezerId] = db.songs().idByDeezerId(s.deezerId) ?: db.songs().insert(s.toEntity(clock())).also { newStreamed++ }
                    step()
                }
                // Local songs: this device's, matched by artist, title and duration.
                val localHere = db.songs().getAll().filter { it.source == SongSource.LOCAL }.map { it to keyOf(it) }
                fun localSong(key: String) = localHere.firstOrNull { it.second == key }?.first ?: localHere.firstOrNull { LibrarySong.sameSong(key, it.second) }?.first
                val missing = HashSet<String>()

                // Playlists: user playlists by name, Favorites and Excluded songs by kind.
                var playlists = 0
                val existing = db.playlists().getAll()
                val waiting = db.playlists().pending().toSet()
                for (p in library.playlists) {
                    val kind = runCatching { PlaylistKind.valueOf(p.kind) }.getOrDefault(PlaylistKind.USER)
                    val here = existing.firstOrNull { if (kind == PlaylistKind.USER) it.kind == kind && it.name == p.name else it.kind == kind }
                    val id = here?.id ?: db.playlists().insert(PlaylistEntity(name = p.name, createdAt = p.createdAt, deezerPlaylistId = p.deezerPlaylistId, kind = kind))
                    val before = db.playlists().songIds(id).toSet()
                    val songs = p.streamed.mapNotNull { deezerIds[it] ?: db.songs().idByDeezerId(it) } + p.local.mapNotNull { key -> localSong(key)?.id.also { if (it == null) missing += key } }
                    val added = songs.distinct().filter { it !in before }
                    db.playlists().addSongs(added.map { PlaylistSongEntity(id, it) })
                    val pending = p.local.filter { it in missing }.map { PendingPlaylistSongEntity(id, it) }.filter { it !in waiting }
                    db.playlists().addPending(pending)
                    // "Excluded songs" isn't listed as a playlist, so the summary doesn't count it.
                    if (kind != PlaylistKind.EXCLUDED && (here == null || added.isNotEmpty() || pending.isNotEmpty())) playlists++
                    step()
                }

                // Year corrections, by match key; the newer one wins.
                var years = 0
                val overrides = db.overrides().getAll().toMutableList()
                for (y in library.years) {
                    val uri = y.deezerId?.let(AudioRef::deezerUri) ?: localSong(y.matchKey)?.uri ?: (LibraryRepository.IMPORTED_URI + y.matchKey).also { missing += y.matchKey }
                    val here = overrides.firstOrNull { it.songUri == uri } ?: overrides.firstOrNull { it.matchKey == y.matchKey }
                    if (here == null || here.updatedAt < y.updatedAt) {
                        val row = (here ?: SongOverrideEntity(songUri = uri, matchKey = y.matchKey, manualYear = null, updatedAt = 0))
                            .copy(manualYear = y.year, updatedAt = y.updatedAt)
                        db.overrides().upsert(row)
                        years++
                    }
                    step()
                }

                // MusicBrainz results, by song key; the newer one wins.
                for (l in library.lookups) {
                    val here = db.mbCache().get(l.songKey)
                    if (here == null || here.checkedAt < l.checkedAt) db.mbCache().put(l.toEntity())
                    step()
                }

                // Players by name, with their photo and statistics; rounds and games already here stay.
                var players = 0
                val profiles = db.profiles().getAll()
                for (p in library.players) {
                    val here = profiles.firstOrNull { it.name.trim().equals(p.name.trim(), ignoreCase = true) }
                    val photo = if (here?.photoPath == null) p.photo?.let { photos[it] }?.let { savePhoto(it, written) } else null
                    val id = when {
                        here == null -> db.profiles().upsert(ProfileEntity(name = p.name, initials = p.initials, colorIndex = p.colorIndex, photoPath = photo, createdAt = p.createdAt)).also { players++ }
                        photo != null -> here.id.also { db.profiles().upsert(here.copy(photoPath = photo)) }
                        else -> here.id
                    }
                    db.playerStats().addRounds(p.rounds.map { PlayerRoundEntity(0, id, it.game, it.round, it.mode, it.year, it.played, it.yearsOff, it.namingChance, it.named, it.won) })
                    db.playerStats().addGames(p.games.map { PlayerGameEntity(0, id, it.game, it.mode, it.won, it.finishedAt) })
                    step()
                }
                ImportSummary(playlists, newStreamed, players, years, missing.size, settings = false)
            }
        } catch (e: Throwable) {
            written.forEach { it.delete() }
            throw e
        }
        val imported = library.settings?.takeIf { withSettings }?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
        if (imported != null) settings.update { imported }
        onProgress(total, total)
        return summary.copy(settings = imported != null)
    }

    /** Whether [input] holds settings (an import may then offer them). */
    fun hasSettings(input: InputStream): Boolean = read(input).first.settings != null

    private fun read(input: InputStream): Pair<BackupLibrary, Map<String, ByteArray>> {
        var manifest: BackupManifest? = null
        var library: BackupLibrary? = null
        val photos = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == MANIFEST -> manifest = runCatching { json.decodeFromString(BackupManifest.serializer(), zip.readLimited(MAX_JSON).decodeToString()) }.getOrNull()
                    entry.name == LIBRARY -> library = json.decodeFromString(BackupLibrary.serializer(), zip.readLimited(MAX_JSON).decodeToString())
                    PHOTO.matches(entry.name) && photos.size < MAX_PHOTOS -> photos[entry.name] = zip.readLimited(MAX_PHOTO)
                }
            }
        }
        val m = manifest ?: throw BackupFormatException("no manifest")
        if (m.format > FORMAT) throw BackupFormatException("format ${m.format} is newer than $FORMAT")
        return (library ?: throw BackupFormatException("no library")) to photos
    }

    /** Writes a photo into the photo folder if it's a PNG or JPEG; returns its path. */
    private fun savePhoto(bytes: ByteArray, written: MutableList<File>): String? {
        val extension = when {
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            else -> return null
        }
        photoFolder.mkdirs()
        val file = File(photoFolder, "${UUID.randomUUID()}.$extension")
        file.writeBytes(bytes)
        written += file
        return file.absolutePath
    }

    companion object {
        /** The file name an export suggests, e.g. "TrackRecord-2026-11-01.trackrecord". */
        fun fileName(date: String) = "TrackRecord-$date.$EXTENSION"

        const val EXTENSION = "trackrecord"
        const val FORMAT = 1
        private const val MANIFEST = "manifest.json"
        private const val LIBRARY = "library.json"
        private val PHOTO = Regex("""photos/\d{1,6}\.(png|jpg)""")
        private const val MAX_JSON = 256 * 1024 * 1024
        private const val MAX_PHOTO = 8 * 1024 * 1024
        private const val MAX_PHOTOS = 1000
        private const val PROGRESS_STEP = 50

        private fun keyOf(song: SongEntity) = LibrarySong.matchKey(song.artist, song.title, song.durationMs)

        private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
            putNextEntry(ZipEntry(name))
            write(bytes)
            closeEntry()
        }

        /** The current entry, refusing more than [max] bytes (a file from elsewhere could claim anything). */
        private fun ZipInputStream.readLimited(max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > max) throw BackupFormatException("an entry is too large")
            }
            return out.toByteArray()
        }
    }
}

@Serializable
private data class BackupManifest(val format: Int, val appVersion: String, val exportedAt: Long, val device: String)

@Serializable
private data class BackupLibrary(
    val streamed: List<BackupStreamed> = emptyList(),
    val local: List<BackupLocal> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val years: List<BackupYear> = emptyList(),
    val lookups: List<BackupLookup> = emptyList(),
    val players: List<BackupPlayer> = emptyList(),
    /** AppSettings as JSON, when they were exported. */
    val settings: String? = null,
)

@Serializable
private data class BackupStreamed(
    val deezerId: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val tagOriginalYear: Int?,
    val tagYear: Int?,
    val isrc: String?,
    val mbRecordingId: String?,
) {
    fun toEntity(now: Long) = SongEntity(
        folderId = null, uri = AudioRef.deezerUri(deezerId), relativePath = "", lastModified = now, sizeBytes = 0,
        title = title, artist = artist, album = album, durationMs = durationMs, tagOriginalYear = tagOriginalYear, tagYear = tagYear,
        mbRecordingId = mbRecordingId, isrc = isrc, hasCover = false, source = SongSource.DEEZER, deezerId = deezerId,
    )
}

/** A local song as a reference only: the file stays on its device. */
@Serializable
private data class BackupLocal(val matchKey: String, val artist: String?, val title: String, val durationMs: Long, val fileName: String)

/** [streamed]: Deezer ids; [local]: match keys, also of places still waiting for their song. */
@Serializable
private data class BackupPlaylist(
    val name: String,
    val kind: String,
    val createdAt: Long,
    val deezerPlaylistId: Long?,
    val streamed: List<Long>,
    val local: List<String>,
)

/** A year correction; [deezerId] for a streamed song. */
@Serializable
private data class BackupYear(val matchKey: String, val deezerId: Long?, val year: Int, val updatedAt: Long)

@Serializable
private data class BackupLookup(
    val songKey: String,
    val year: Int?,
    val checkedAt: Long,
    val releaseId: String?,
    val releaseGroupId: String?,
    val coverChecked: Boolean,
    val lookupVersion: Int,
    val viaFingerprint: Boolean,
    val fingerprintChecked: Boolean,
    val nameSearched: Boolean,
) {
    fun toEntity() = MbCacheEntity(songKey, year, checkedAt, releaseId, releaseGroupId, coverChecked, lookupVersion, viaFingerprint, fingerprintChecked, nameSearched)

    companion object {
        fun from(e: MbCacheEntity) =
            BackupLookup(e.songKey, e.year, e.checkedAt, e.releaseId, e.releaseGroupId, e.coverChecked, e.lookupVersion, e.viaFingerprint, e.fingerprintChecked, e.nameSearched)
    }
}

/** [photo]: its entry in the file. */
@Serializable
private data class BackupPlayer(
    val name: String,
    val initials: String,
    val colorIndex: Int,
    val createdAt: Long,
    val photo: String?,
    val rounds: List<BackupRound>,
    val games: List<BackupGame>,
)

@Serializable
private data class BackupRound(
    val game: Long,
    val round: Int,
    val mode: String,
    val year: Int,
    val played: Boolean,
    val yearsOff: Int?,
    val namingChance: Boolean,
    val named: Boolean,
    val won: Boolean,
)

@Serializable
private data class BackupGame(val game: Long, val mode: String, val won: Boolean, val finishedAt: Long)
