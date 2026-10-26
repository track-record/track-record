package com.trackrecord.data.playlists

import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.PlaylistEntity
import com.trackrecord.data.db.PlaylistKind
import com.trackrecord.data.db.PlaylistSongEntity
import com.trackrecord.data.db.PlaylistWithCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Playlists the app provides itself; they are never stored and can't be edited. */
object BuiltInPlaylists {
    /** Every local file (feedback round 5). Songs carry it in their playlist ids, so pools and filters just work. */
    const val LOCAL_FILES = -2L

    /** Every streamed (Deezer) song, from playlists and imported lists alike (feedback round 10). */
    const val STREAMED = -3L

    /**
     * "Excluded songs" (feedback round 28): stored like a playlist (kind EXCLUDED) and reached through this
     * id, so filters and pools treat it like the other built-ins. Never listed with the user's playlists.
     */
    const val EXCLUDED = -4L

    fun isBuiltIn(id: Long) = id < 0
}

/** User playlists. "All" is built in (every song, not editable) and is represented by a null id. */
class PlaylistRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    val playlists: Flow<List<PlaylistWithCount>> = db.playlists().observeAll()

    /** The built-in playlists first (name left empty: the UI names them), then the user's. */
    val withBuiltIns: Flow<List<PlaylistWithCount>> =
        combine(db.songs().observeLocalCount(), db.songs().observeStreamedCount(), playlists) { local, streamed, user ->
            listOf(
                PlaylistWithCount(id = BuiltInPlaylists.LOCAL_FILES, name = "", songs = local),
                PlaylistWithCount(id = BuiltInPlaylists.STREAMED, name = "", songs = streamed),
            ) + user
        }

    suspend fun create(name: String): Long = db.playlists().insert(PlaylistEntity(name = name.trim(), createdAt = clock()))

    suspend fun rename(id: Long, name: String) = db.playlists().rename(id, name.trim())

    suspend fun delete(id: Long) = db.playlists().delete(id)

    suspend fun addSongs(playlistId: Long, songIds: Collection<Long>) {
        val id = db.storedPlaylistId(playlistId)
        if (songIds.isNotEmpty()) db.playlists().addSongs(songIds.map { PlaylistSongEntity(id, it) })
    }

    suspend fun removeSongs(playlistId: Long, songIds: Collection<Long>) {
        val id = db.storedPlaylistId(playlistId)
        songIds.chunked(500).forEach { db.playlists().removeSongs(id, it) }
    }
}

/** The stored id for [playlistId]: "Excluded songs" has its own row (created if it went missing). */
internal suspend fun AppDatabase.storedPlaylistId(playlistId: Long): Long {
    if (playlistId != BuiltInPlaylists.EXCLUDED) return playlistId
    return playlists().excludedId() ?: playlists().insert(PlaylistEntity(name = "", createdAt = 0, kind = PlaylistKind.EXCLUDED))
}
