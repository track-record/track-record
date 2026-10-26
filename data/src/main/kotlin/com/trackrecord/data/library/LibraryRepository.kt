package com.trackrecord.data.library

import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.FolderEntity
import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.db.PlaylistSongEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongOverrideEntity
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import com.trackrecord.data.playlists.BuiltInPlaylists
import com.trackrecord.data.playlists.storedPlaylistId
import com.trackrecord.engine.year.TagYears
import com.trackrecord.engine.year.YearPreference
import com.trackrecord.engine.year.YearResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val folderName: String, val done: Int, val total: Int) : ScanState
}

data class ScanReport(val added: Int, val updated: Int, val removed: Int, val unchanged: Int, val unavailableFolders: Int)

class LibraryRepository(
    private val db: AppDatabase,
    private val scanner: FolderScanner,
    private val tagReader: TagReader,
    private val currentYear: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Which year counts when tags and MusicBrainz disagree (a setting, feedback round 28). */
    private val yearPreference: Flow<YearPreference> = flowOf(YearPreference.MUSICBRAINZ),
    /** Keeps the shared song list while anyone watches it. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val scanMutex = Mutex()
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    val folders: Flow<List<FolderEntity>> = db.folders().observeAll()

    val songs: Flow<List<LibrarySong>> =
        combine(
            db.songs().observeAll(),
            db.overrides().observeAll(),
            folders,
            db.mbCache().observeAll(),
            db.playlists().observeMemberships(),
        ) { songs, overrides, folders, mb, memberships -> Raw(songs, overrides, folders, mb, memberships) }
            .combine(yearPreference.distinctUntilChanged()) { raw, preference -> raw to preference }
            // One list for every screen (the library, a song's page), built off the main thread, and only
            // from the latest data: during a lookup every result changes the cache (feedback round 29).
            .conflate()
            .map { (raw, preference) ->
                LibrarySong.assemble(raw.songs, raw.overrides, raw.folders, currentYear(), raw.mb.associateBy { it.songKey }, membershipMap(raw.memberships), preference)
            }
            .flowOn(Dispatchers.Default)
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private data class Raw(
        val songs: List<SongEntity>,
        val overrides: List<SongOverrideEntity>,
        val folders: List<FolderEntity>,
        val mb: List<MbCacheEntity>,
        val memberships: List<PlaylistSongEntity>,
    )

    private fun membershipMap(rows: List<PlaylistSongEntity>): Map<Long, Set<Long>> =
        rows.groupBy({ it.songId }, { it.playlistId }).mapValues { it.value.toSet() }

    fun song(id: Long): Flow<LibrarySong?> = songs.map { list -> list.firstOrNull { it.id == id } }

    /**
     * Songs that may enter a game: playable, on any included playlist (null = "All"), on no excluded
     * playlist, and within the year range.
     */
    suspend fun playableSongs(include: Collection<Long>?, exclude: Collection<Long>, yearFrom: Int?, yearTo: Int?): List<LibrarySong> =
        assembleNow().filter { s ->
            val y = s.resolved.year ?: return@filter false
            s.playable && inPool(s, include, exclude) &&
                (yearFrom == null || y >= yearFrom) &&
                (yearTo == null || y <= yearTo)
        }

    /**
     * Songs on the game's playlists that only lack a year and were never looked up: during a game
     * they are checked on MusicBrainz and join the pool on a match (DECISIONS.md D-064).
     */
    suspend fun missingYearCandidates(include: Collection<Long>?, exclude: Collection<Long>): List<SongEntity> =
        assembleNow().filter { s ->
            s.missingYear && !s.excluded && s.folderAvailable && s.lookup == null &&
                !s.song.artist.isNullOrBlank() && inPool(s, include, exclude)
        }.map { it.song }

    private fun inPool(s: LibrarySong, include: Collection<Long>?, exclude: Collection<Long>): Boolean =
        (include == null || s.playlistIds.any { it in include }) && s.playlistIds.none { it in exclude }

    /** Matched songs ("Load cover art" skips the ones that already have a cover file). */
    /** Songs with a MusicBrainz year, with their cached lookup (for "Load cover art"). */
    suspend fun matchedSongs(): List<LibrarySong> = assembleNow().filter { it.lookup?.year != null }

    /** Every song's year as it resolves now (for a lookup's summary, feedback round 29). */
    suspend fun resolvedYears(): Map<Long, Int?> = assembleNow().associate { it.id to it.resolved.year }

    // Assembling a large library takes a while: never on the main thread (it held up New game, feedback round 27).
    private suspend fun assembleNow(): List<LibrarySong> = withContext(Dispatchers.Default) {
        LibrarySong.assemble(
            db.songs().getAll(),
            db.overrides().getAll(),
            db.folders().getAll(),
            currentYear(),
            db.mbCache().getAll().associateBy { it.songKey },
            membershipMap(db.playlists().memberships()),
            yearPreference.first(),
        )
    }

    /** One song as it resolves right now (used when a lookup finishes during a game). */
    suspend fun songNow(id: Long): LibrarySong? = assembleNow().firstOrNull { it.id == id }

    /** Playable songs that have no MusicBrainz result yet ("Verify library"). */
    suspend fun unverifiedSongs(currentVersion: Int = MusicBrainzLookup.LOOKUP_VERSION): List<SongEntity> =
        assembleNow().filter { it.playable && (it.lookup == null || it.lookup.lookupVersion < currentVersion) }.map { it.song }

    /**
     * Songs "Verify by fingerprint" hasn't checked yet (D-194): local songs by their audio and names,
     * streamed songs by their ISRC and names (D-276).
     */
    suspend fun fingerprintUncheckedSongs(): List<SongEntity> =
        assembleNow().filter { it.lookup?.fingerprintChecked != true }.map { it.song }

    /** Songs without a usable year ("Check missing years"). */
    suspend fun missingYearSongs(): List<SongEntity> = assembleNow().filter { it.missingYear }.map { it.song }

    suspend fun songEntity(id: Long): SongEntity? = db.songs().get(id)

    /** Adds a folder (or revives one whose access was lost) and scans it. The caller already took the persistable permission. */
    suspend fun addFolder(treeUri: String): ScanReport {
        val name = withContext(Dispatchers.IO) { scanner.displayName(treeUri) } ?: treeUri.substringAfterLast("%3A").ifBlank { treeUri }
        val existing = db.folders().findByUri(treeUri)
        val folder = if (existing != null) {
            existing.copy(displayName = name, available = true).also { db.folders().update(it) }
        } else {
            val id = db.folders().insert(FolderEntity(treeUri = treeUri, displayName = name))
            FolderEntity(id = id, treeUri = treeUri, displayName = name)
        }
        return scanMutex.withLock { scanFolders(listOf(folder)) }
    }

    suspend fun removeFolder(folderId: Long) = db.folders().delete(folderId)

    /** Rescans every folder; unchanged files (same modification time and size) are skipped. */
    suspend fun rescanAll(): ScanReport = scanMutex.withLock { scanFolders(db.folders().getAll()) }

    private suspend fun scanFolders(folders: List<FolderEntity>): ScanReport {
        var report = ScanReport(0, 0, 0, 0, 0)
        try {
            for (folder in folders) {
                val r = scanFolder(folder)
                report = ScanReport(
                    report.added + r.added,
                    report.updated + r.updated,
                    report.removed + r.removed,
                    report.unchanged + r.unchanged,
                    report.unavailableFolders + r.unavailableFolders,
                )
            }
        } finally {
            _scanState.value = ScanState.Idle
        }
        return report
    }

    private suspend fun scanFolder(folder: FolderEntity): ScanReport {
        _scanState.value = ScanState.Scanning(folder.displayName, 0, 0)
        val found = try {
            withContext(Dispatchers.IO) { scanner.scan(folder.treeUri) }
        } catch (e: FolderUnavailableException) {
            // Keep the songs (and their edits); the folder is shown as unavailable until picked again.
            if (folder.available) db.folders().update(folder.copy(available = false))
            return ScanReport(0, 0, 0, 0, 1)
        }
        val plan = ScanPlanner.plan(db.songs().scanStates(folder.id), found)
        _scanState.value = ScanState.Scanning(folder.displayName, 0, plan.toRead.size)

        var done = 0
        val year = currentYear()
        val semaphore = Semaphore(READ_PARALLELISM)
        val read = coroutineScope {
            plan.toRead.map { (file, existingId) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val tags = try {
                            withTimeoutOrNull(READ_TIMEOUT_MS) { tagReader.read(file.uri) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        synchronized(this@LibraryRepository) {
                            done++
                            _scanState.value = ScanState.Scanning(folder.displayName, done, plan.toRead.size)
                        }
                        toEntity(folder.id, file, existingId, tags, year)
                    }
                }
            }.awaitAll()
        }
        db.songs().applyScan(read, plan.deletions)
        rebindMovedOverrides(read)
        db.folders().update(folder.copy(available = true, lastScanAt = clock()))
        val added = plan.toRead.count { it.second == 0L }
        return ScanReport(added, plan.toRead.size - added, plan.deletions.size, plan.unchanged, 0)
    }

    /** A manual change whose file is gone follows the song to its new URI (artist + title + duration match). */
    private suspend fun rebindMovedOverrides(newOrChanged: List<SongEntity>) {
        if (newOrChanged.isEmpty()) return
        val overrides = db.overrides().getAll()
        if (overrides.isEmpty()) return
        val knownUris = db.songs().getAll().mapTo(HashSet()) { it.uri }
        val orphansByKey = overrides.filter { it.songUri !in knownUris }.groupBy { it.matchKey }
        for (song in newOrChanged) {
            val orphan = orphansByKey[LibrarySong.matchKey(song.artist, song.title, song.durationMs)]?.firstOrNull() ?: continue
            if (db.overrides().findByUri(song.uri) == null) db.overrides().upsert(orphan.copy(songUri = song.uri))
        }
    }

    private fun toEntity(folderId: Long, file: FoundFile, existingId: Long, tags: RawTags?, currentYear: Int): SongEntity {
        val fileTitle = file.relativePath.substringAfterLast('/').substringBeforeLast('.')
        val years = tags?.let { TagYears.extract(it.fields, currentYear) }
        return SongEntity(
            id = existingId,
            folderId = folderId,
            uri = file.uri,
            relativePath = file.relativePath,
            lastModified = file.lastModified,
            sizeBytes = file.sizeBytes,
            title = tags?.title ?: fileTitle,
            artist = tags?.artist,
            album = tags?.album,
            durationMs = tags?.durationMs ?: 0,
            tagOriginalYear = years?.originalYear,
            tagYear = years?.year,
            mbRecordingId = tags?.let { TagYears.recordingId(it.fields) },
            isrc = tags?.let { TagYears.isrc(it.fields) },
            hasCover = tags?.hasCover ?: false,
            readFailed = tags == null,
        )
    }

    // ---- Manual changes ----

    /** Sets (or with null clears) the manual year. Throws [IllegalArgumentException] outside 1900..current year. */
    suspend fun setManualYear(songId: Long, year: Int?) {
        require(year == null || YearResolver.isValidYear(year, currentYear())) { "year out of range" }
        updateOverride(songId) { it.copy(manualYear = year) }
    }

    /** Puts the song on "Excluded songs" or takes it off (feedback round 28). */
    suspend fun setExcluded(songId: Long, excluded: Boolean) {
        val playlist = db.storedPlaylistId(BuiltInPlaylists.EXCLUDED)
        if (excluded) db.playlists().addSongs(listOf(PlaylistSongEntity(playlist, songId))) else db.playlists().removeSongs(playlist, listOf(songId))
    }

    private suspend fun updateOverride(songId: Long, change: (SongOverrideEntity) -> SongOverrideEntity) {
        val song = db.songs().get(songId) ?: return
        val key = LibrarySong.matchKey(song.artist, song.title, song.durationMs)
        val current = db.overrides().findByUri(song.uri)
            ?: db.overrides().getAll().firstOrNull { it.matchKey == key }
            ?: SongOverrideEntity(songUri = song.uri, matchKey = key, manualYear = null, updatedAt = 0)
        val updated = change(current).copy(songUri = song.uri, matchKey = key, updatedAt = clock())
        if (updated.manualYear == null) {
            if (updated.id != 0L) db.overrides().delete(updated.id)
        } else {
            db.overrides().upsert(updated)
        }
    }

    companion object {
        private const val READ_PARALLELISM = 4
        private const val READ_TIMEOUT_MS = 15_000L
    }
}
