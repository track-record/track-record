package com.trackrecord.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.trackrecord.data.playlists.BuiltInPlaylists
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY display_name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE tree_uri = :treeUri")
    suspend fun findByUri(treeUri: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Minimal columns for deciding which files changed since the last scan. */
data class SongScanState(val id: Long, val uri: String, val lastModified: Long, val sizeBytes: Long)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY artist COLLATE NOCASE, title COLLATE NOCASE")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun getAll(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observe(id: Long): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun get(id: Long): SongEntity?

    @Query("SELECT id, uri, last_modified AS lastModified, size_bytes AS sizeBytes FROM songs WHERE folder_id = :folderId")
    suspend fun scanStates(folderId: Long): List<SongScanState>

    @Upsert
    suspend fun upsert(songs: List<SongEntity>)

    @Insert
    suspend fun insert(song: SongEntity): Long

    @Query("SELECT id FROM songs WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    @Query("SELECT id FROM songs WHERE deezer_id = :deezerId LIMIT 1")
    suspend fun idByDeezerId(deezerId: Long): Long?

    @Query("SELECT COUNT(*) FROM songs WHERE source = 'LOCAL'")
    fun observeLocalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs WHERE source = 'DEEZER'")
    fun observeStreamedCount(): Flow<Int>

    /** Streamed songs that are in no user playlist ("Excluded songs" alone doesn't keep them, feedback round 28). */
    @Query(
        "SELECT id FROM songs WHERE source = 'DEEZER' AND id NOT IN " +
            "(SELECT ps.song_id FROM playlist_songs ps JOIN playlists p ON p.id = ps.playlist_id WHERE p.kind = 'USER')",
    )
    suspend fun unlistedStreamed(): List<Long>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Transaction
    suspend fun applyScan(upserts: List<SongEntity>, deletions: List<Long>) {
        if (deletions.isNotEmpty()) deletions.chunked(500).forEach { delete(it) }
        if (upserts.isNotEmpty()) upsert(upserts)
    }
}

@Dao
interface SongOverrideDao {
    @Query("SELECT * FROM song_overrides")
    fun observeAll(): Flow<List<SongOverrideEntity>>

    @Query("SELECT * FROM song_overrides")
    suspend fun getAll(): List<SongOverrideEntity>

    @Query("SELECT * FROM song_overrides WHERE song_uri = :uri")
    suspend fun findByUri(uri: String): SongOverrideEntity?

    @Upsert
    suspend fun upsert(override: SongOverrideEntity)

    @Query("DELETE FROM song_overrides WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity): Long

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SavedGameDao {
    @Query("SELECT * FROM saved_game WHERE id = 1")
    suspend fun get(): SavedGameEntity?

    @Upsert
    suspend fun save(game: SavedGameEntity)

    /** The game without touching its song list; 0 when there is no saved game to update (D-269). */
    @Query("UPDATE saved_game SET json = :json, updated_at = :updatedAt WHERE id = 1")
    suspend fun saveGame(json: String, updatedAt: Long): Int

    @Query("DELETE FROM saved_game")
    suspend fun clear()
}

@Dao
interface MbCacheDao {
    @Query("SELECT * FROM mb_cache")
    fun observeAll(): Flow<List<MbCacheEntity>>

    @Query("SELECT * FROM mb_cache")
    suspend fun getAll(): List<MbCacheEntity>

    @Query("SELECT * FROM mb_cache WHERE song_key = :key")
    suspend fun get(key: String): MbCacheEntity?

    @Upsert
    suspend fun put(entry: MbCacheEntity)
}

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val songs: Int,
    /** Set for playlists imported from Deezer. */
    val deezerPlaylistId: Long? = null,
    /** Songs on it that stream from Deezer (they leave the library with the playlist unless used elsewhere). */
    val streamed: Int = 0,
)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.id AS id, p.name AS name, COUNT(ps.song_id) AS songs, p.deezer_playlist_id AS deezerPlaylistId, " +
            "COALESCE(SUM(CASE WHEN s.source = 'DEEZER' THEN 1 ELSE 0 END), 0) AS streamed FROM playlists p " +
            "LEFT JOIN playlist_songs ps ON ps.playlist_id = p.id LEFT JOIN songs s ON s.id = ps.song_id " +
            "WHERE p.kind = 'USER' GROUP BY p.id ORDER BY p.name COLLATE NOCASE",
    )
    fun observeAll(): Flow<List<PlaylistWithCount>>

    /** Every membership; "Excluded songs" appears as [BuiltInPlaylists.EXCLUDED], like the other built-ins. */
    @Query(MEMBERSHIPS)
    fun observeMemberships(): Flow<List<PlaylistSongEntity>>

    @Query(MEMBERSHIPS)
    suspend fun memberships(): List<PlaylistSongEntity>

    @Query("SELECT id FROM playlists WHERE kind = 'EXCLUDED' LIMIT 1")
    suspend fun excludedId(): Long?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE deezer_playlist_id = :deezerId LIMIT 1")
    suspend fun byDeezerId(deezerId: Long): PlaylistEntity?

    @Query("SELECT song_id FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun songIds(playlistId: Long): List<Long>

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongs(rows: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId AND song_id IN (:songIds)")
    suspend fun removeSongs(playlistId: Long, songIds: List<Long>)
}

private const val MEMBERSHIPS =
    "SELECT CASE p.kind WHEN 'EXCLUDED' THEN ${BuiltInPlaylists.EXCLUDED} ELSE ps.playlist_id END AS playlist_id, ps.song_id AS song_id " +
        "FROM playlist_songs ps JOIN playlists p ON p.id = ps.playlist_id"

@Dao
interface PlayerStatsDao {
    /** A round recorded again (a restored game) replaces the earlier record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRounds(rounds: List<PlayerRoundEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGames(games: List<PlayerGameEntity>)

    @Query("SELECT * FROM player_rounds WHERE profile_id = :profileId")
    fun observeRounds(profileId: Long): Flow<List<PlayerRoundEntity>>

    @Query("SELECT * FROM player_games WHERE profile_id = :profileId")
    fun observeGames(profileId: Long): Flow<List<PlayerGameEntity>>
}
