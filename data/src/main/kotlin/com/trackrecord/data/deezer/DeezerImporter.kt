package com.trackrecord.data.deezer

import androidx.room.withTransaction
import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.PlaylistEntity
import com.trackrecord.data.db.PlaylistSongEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.lists.ListRow
import com.trackrecord.data.musicbrainz.ServiceUnavailableException
import com.trackrecord.data.sources.AudioRef
import com.trackrecord.engine.musicbrainz.MatchAcceptance
import com.trackrecord.engine.musicbrainz.MbCandidate
import com.trackrecord.engine.year.TagYears

/** How one playlist track becomes a library song (SPEC v2, Library). */
sealed interface ImportPick {
    /** A local file is the same song: it plays in full, nothing streamed is added. */
    data class Local(val songId: Long) : ImportPick

    /** The Deezer track is already in the library. */
    data class Existing(val songId: Long) : ImportPick

    data class New(val track: DzTrack) : ImportPick

    /** No preview (region, licensing) and no local file. */
    data object Unavailable : ImportPick
}

object DeezerImportPlanner {
    /** Picks per track, in playlist order: local file first, then an existing streamed song, else new. */
    fun plan(tracks: List<DzTrack>, songs: List<SongEntity>): List<ImportPick> {
        val locals = songs.filter { it.source == SongSource.LOCAL }
        val localByIsrc = locals.filter { !it.isrc.isNullOrBlank() }.associateBy { it.isrc!!.uppercase() }
        val localByName = locals.groupBy { nameKey(it.artist.orEmpty(), it.title) }
        val streamed = songs.filter { it.deezerId != null }.associateBy { it.deezerId!! }
        val streamedByIsrc = songs.filter { it.deezerId != null && !it.isrc.isNullOrBlank() }.associateBy { it.isrc!!.uppercase() }
        return tracks.map { t ->
            val local = t.isrc?.let { localByIsrc[it.uppercase()] } ?: localByName[nameKey(t.artist.name, t.title)]?.firstOrNull()
            // A streamed song with the same ISRC is already in the library (no request needed, feedback round 19).
            val known = streamed[t.id] ?: t.isrc?.let { streamedByIsrc[it.uppercase()] }
            when {
                local != null -> ImportPick.Local(local.id)
                known != null -> ImportPick.Existing(known.id)
                t.playable -> ImportPick.New(t)
                else -> ImportPick.Unavailable
            }
        }
    }

    fun nameKey(artist: String, title: String) = MatchAcceptance.normalize(artist) + "|" + MatchAcceptance.normalize(title)
}

data class DeezerImportResult(
    val playlistId: Long,
    val name: String,
    val total: Int,
    /** New streamed songs in the library. */
    val added: Int,
    /** Tracks that are local files. */
    val local: Int,
    /** Tracks without a preview (left out). */
    val unavailable: Int,
    /** List import: the rows nothing was found for ("Artist – Title"). */
    val notFound: List<String> = emptyList(),
)

class DeezerImportException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { INVALID_LINK, NOT_FOUND, UNAVAILABLE, EMPTY_LIST }
}

/**
 * Imports public Deezer playlists into the library (SPEC v2, Library): each becomes an app playlist that
 * remembers its Deezer id; "Update from Deezer" syncs it again. Streamed songs are stored once; songs
 * that no playlist and no saved game ([inUse]) needs any more are removed.
 */
class DeezerImporter(
    private val db: AppDatabase,
    private val client: DeezerClient,
    private val links: LinkResolver,
    private val inUse: suspend () -> Set<Long>,
    private val currentYear: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Imports the playlist behind [link]; importing the same Deezer playlist again updates it. */
    suspend fun import(link: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): DeezerImportResult {
        val id = DeezerLinks.playlistId(link)
            ?: DeezerLinks.shareLink(link)?.let { share -> retrying { links.finalUrl(share) }?.let { DeezerLinks.playlistId(it) } }
            ?: throw DeezerImportException(DeezerImportException.Reason.INVALID_LINK, "no playlist link: $link")
        return sync(id, db.playlists().byDeezerId(id)?.id, onProgress)
    }

    /** Re-reads a playlist that came from Deezer. */
    suspend fun update(playlistId: Long, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): DeezerImportResult {
        val deezerId = db.playlists().get(playlistId)?.deezerPlaylistId
            ?: throw DeezerImportException(DeezerImportException.Reason.NOT_FOUND, "playlist $playlistId has no Deezer link")
        return sync(deezerId, playlistId, onProgress)
    }

    /**
     * Adds the [DefaultPlaylists] the library doesn't have yet ("Get default playlists", feedback round 48);
     * the ones already there stay as they are.
     */
    suspend fun importDefaults(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<DeezerImportResult> =
        DefaultPlaylists.ALL.filter { db.playlists().byDeezerId(it.deezerId) == null }.map { sync(it.deezerId, existing = null, onProgress, name = it.name) }

    private suspend fun sync(deezerId: Long, existing: Long?, onProgress: (Int, Int) -> Unit, name: String? = null): DeezerImportResult {
        val meta = retrying { client.playlist(deezerId) }
            ?: throw DeezerImportException(DeezerImportException.Reason.NOT_FOUND, "Deezer playlist $deezerId not found or not public")
        val listed = retrying { client.playlistTracks(deezerId) }
        val (picks, details) = resolve(listed, onProgress)
        val (playlistId, added) = save(picks, details, existing) {
            PlaylistEntity(name = name ?: meta.title.ifBlank { "Deezer $deezerId" }, createdAt = clock(), deezerPlaylistId = deezerId)
        }
        return result(playlistId, meta.title, picks, added, emptyList())
    }

    /**
     * Imports a song list (CSV or text, e.g. a Spotify playlist exported with Exportify) as a new
     * playlist named [name]. Each row becomes a local file, a Deezer track found by ISRC or by a search
     * (strict names, duration within 10 s when the list has one), or is reported as not found.
     */
    suspend fun importList(name: String, rows: List<ListRow>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): DeezerImportResult {
        if (rows.isEmpty()) throw DeezerImportException(DeezerImportException.Reason.EMPTY_LIST, "no songs in the list")
        // Rows become stand-in tracks without a preview: the playlist logic then finds the local file or
        // a playable Deezer track for each, exactly as for unplayable playlist entries (D-085).
        val standIns = rows.mapIndexed { i, r ->
            DzTrack(
                id = -(i + 1L), title = r.title, isrc = r.isrc, duration = ((r.durationMs ?: 0L) / 1000).toInt(),
                readable = false, artist = DzArtist(name = r.artist),
            )
        }
        val rowOf = standIns.indices.associateBy { standIns[it].id }
        // Saved chunk by chunk (feedback round 19): a long list fills its playlist while it runs, and an
        // interruption keeps what was found so far.
        var playlistId: Long? = null
        var added = 0
        val picks = ArrayList<ImportPick>(rows.size)
        for ((chunkIndex, chunk) in standIns.chunked(LIST_CHUNK).withIndex()) {
            val offset = chunkIndex * LIST_CHUNK
            val (chunkPicks, details) = resolve(
                chunk,
                onProgress = { done, _ -> onProgress(minOf(offset + done, rows.size), rows.size) },
                credits = chunk.associate { it.id to rows[rowOf.getValue(it.id)].credit },
                releaseDates = chunk.associate { it.id to rows[rowOf.getValue(it.id)].releaseDate },
            )
            val (id, new) = save(chunkPicks, details, existing = playlistId, replace = false, cleanUp = false) {
                PlaylistEntity(name = name.ifBlank { "Imported" }, createdAt = clock())
            }
            playlistId = id
            added += new
            picks += chunkPicks
            onProgress(minOf(offset + chunk.size, rows.size), rows.size)
        }
        removeUnused()
        val notFound = picks.withIndex().filter { it.value == ImportPick.Unavailable }.map { rows[it.index] }.map { "${it.artist} – ${it.title}" }
        return result(playlistId!!, name, picks, added, notFound)
    }

    /** Picks for each listed track (finding playable copies for unplayable ones) and the details of new tracks. */
    private suspend fun resolve(
        listed: List<DzTrack>,
        onProgress: (Int, Int) -> Unit,
        credits: Map<Long, String> = emptyMap(),
        /** List imports: each stand-in's album release date from the list, so search hits need no detail request. */
        releaseDates: Map<Long, String?> = emptyMap(),
    ): Pair<List<ImportPick>, Map<Long, DzTrack?>> {
        val songs = db.songs().getAll()
        val details = HashMap<Long, DzTrack?>()
        // Playlists often point at an old copy of a recording that has no preview any more; the same
        // recording under another id usually has one (D-085).
        val unplayable = DeezerImportPlanner.plan(listed, songs).withIndex().filter { it.value == ImportPick.Unavailable }.map { listed[it.index] }
        val copies = fetchAll(unplayable, onProgress) { playableCopy(it, credits[it.id] ?: it.artist.name) }
        copies.values.filterNotNull().filter { it.releaseDate != null }.forEach { details[it.id] = it }
        for ((standIn, copy) in copies) {
            val date = releaseDates[standIn]
            if (copy != null && copy.id !in details && date != null) details[copy.id] = copy.copy(releaseDate = date)
        }
        val tracks = listed.map { copies[it.id] ?: it }
        val picks = DeezerImportPlanner.plan(tracks, songs)
        // Release dates are only in track details.
        val toFetch = picks.filterIsInstance<ImportPick.New>().map { it.track }.distinctBy { it.id }.filter { it.id !in details }
        details += fetchAll(toFetch, onProgress) { client.track(it.id) }
        return picks to details
    }

    /**
     * Stores new songs and the playlist's songs; returns the playlist id and the number of new songs.
     * With [replace] the playlist gets exactly these songs (playlist sync), else they are added (list
     * import chunks).
     */
    private suspend fun save(
        picks: List<ImportPick>,
        details: Map<Long, DzTrack?>,
        existing: Long?,
        replace: Boolean = true,
        cleanUp: Boolean = true,
        create: () -> PlaylistEntity,
    ): Pair<Long, Int> {
        val year = currentYear()
        val newIds = HashMap<Long, Long>()
        var playlistId = 0L
        db.withTransaction {
            // Songs picked from the library may be gone by now (a rescan during a long import): only
            // songs that still exist go into the playlist (feedback round 19, the crash with 3000 rows).
            val picked = picks.mapNotNull { (it as? ImportPick.Local)?.songId ?: (it as? ImportPick.Existing)?.songId }.distinct()
            val stillThere = picked.chunked(500).flatMap { db.songs().existingIds(it) }.toSet()
            val songIds = picks.map { pick ->
                when (pick) {
                    is ImportPick.Local -> pick.songId.takeIf { it in stillThere }
                    is ImportPick.Existing -> pick.songId.takeIf { it in stillThere }
                    is ImportPick.New -> newIds.getOrPut(pick.track.id) {
                        // The same track may have been added meanwhile (another import): reuse it.
                        db.songs().idByDeezerId(pick.track.id) ?: db.songs().insert(entityFor(pick.track, details[pick.track.id], year))
                    }
                    ImportPick.Unavailable -> null
                }
            }
            playlistId = existing?.takeIf { db.playlists().get(it) != null } ?: db.playlists().insert(create())
            val wanted = songIds.filterNotNull().distinct()
            val current = db.playlists().songIds(playlistId)
            if (replace) (current - wanted.toSet()).chunked(500).forEach { db.playlists().removeSongs(playlistId, it) }
            db.playlists().addSongs((wanted - current.toSet()).map { PlaylistSongEntity(playlistId, it) })
        }
        if (cleanUp) removeUnused()
        return playlistId to newIds.size
    }

    private suspend fun result(playlistId: Long, fallbackName: String, picks: List<ImportPick>, added: Int, notFound: List<String>) = DeezerImportResult(
        playlistId = playlistId,
        name = db.playlists().get(playlistId)?.name ?: fallbackName,
        total = picks.size,
        added = added,
        local = picks.count { it is ImportPick.Local },
        unavailable = picks.count { it == ImportPick.Unavailable },
        notFound = notFound,
    )

    /**
     * A playable Deezer track of the same recording: by ISRC first, else a search accepted by the
     * strict name rule with a duration within [MAX_DURATION_DIFF_S] seconds; null if there is none.
     */
    private suspend fun playableCopy(track: DzTrack, credit: String): DzTrack? {
        track.isrc?.takeIf { it.isNotBlank() }?.let { isrc ->
            client.trackByIsrc(isrc)?.takeIf { it.playable && it.id != track.id }?.let { return it }
        }
        return client.search("${track.artist.name} ${track.titleShort ?: track.title}").firstOrNull { r ->
            r.playable && (track.duration <= 0 || kotlin.math.abs(r.duration - track.duration) <= MAX_DURATION_DIFF_S) &&
                MatchAcceptance.acceptsCredited(credit, track.title, MbCandidate(r.artist.name, r.title, year = null))
        }
    }

    /** One request per track; a failed one goes to the end of the list, at most [MAX_RETRIES] times (D-074). */
    private suspend fun <T> fetchAll(tracks: List<DzTrack>, onProgress: (Int, Int) -> Unit, fetch: suspend (DzTrack) -> T?): Map<Long, T?> {
        val out = HashMap<Long, T?>()
        var done = 0
        if (tracks.isNotEmpty()) onProgress(0, tracks.size)
        val queue = ArrayDeque(tracks.map { it to 0 })
        while (queue.isNotEmpty()) {
            val (track, retries) = queue.removeFirst()
            try {
                out[track.id] = fetch(track)
                onProgress(++done, tracks.size)
            } catch (e: ServiceUnavailableException) {
                if (retries < MAX_RETRIES) queue.addLast(track to retries + 1) else { out[track.id] = null; onProgress(++done, tracks.size) }
            }
        }
        return out
    }

    /** Streamed songs in no playlist and not in the saved game leave the library. */
    suspend fun removeUnused() {
        val keep = inUse()
        val unused = db.songs().unlistedStreamed().filter { it !in keep }
        unused.chunked(500).forEach { db.songs().delete(it) }
    }

    private fun entityFor(track: DzTrack, details: DzTrack?, currentYear: Int) = SongEntity(
        folderId = null,
        uri = AudioRef.deezerUri(track.id),
        relativePath = "",
        lastModified = clock(),
        sizeBytes = 0,
        title = track.title,
        artist = track.artist.name.ifBlank { null },
        album = track.album.title.ifBlank { null },
        durationMs = track.duration * 1000L,
        tagOriginalYear = null,
        // Deezer's release date stands in for the tag year (SPEC v2, Years).
        tagYear = TagYears.parseYear(details?.releaseDate, currentYear),
        mbRecordingId = null,
        isrc = (details?.isrc ?: track.isrc)?.ifBlank { null },
        hasCover = false,
        source = SongSource.DEEZER,
        deezerId = track.id,
    )

    /** Runs a request, repeating it after a passing failure (the queue spaces the attempts out). */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ServiceUnavailableException) {
                if (++attempt > MAX_RETRIES) throw DeezerImportException(DeezerImportException.Reason.UNAVAILABLE, e.message ?: "Deezer unavailable")
            } catch (e: java.io.IOException) {
                throw DeezerImportException(DeezerImportException.Reason.UNAVAILABLE, e.message ?: "offline")
            }
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val MAX_DURATION_DIFF_S = 10

        /** List rows resolved and saved together. */
        const val LIST_CHUNK = 100
    }
}
