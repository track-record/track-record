package com.trackrecord.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A folder the user picked with the system folder picker. */
@Entity(tableName = "folders", indices = [Index(value = ["tree_uri"], unique = true)])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** False once access was lost (folder moved or deleted, permission gone). */
    val available: Boolean = true,
    @ColumnInfo(name = "last_scan_at") val lastScanAt: Long = 0,
)

/** Where a song comes from (v2). */
enum class SongSource {
    /** An audio file in a picked folder. */
    LOCAL,

    /** A Deezer track, played as its 30-second preview. */
    DEEZER,
}

/**
 * One song with the tag values the game needs: an audio file found in a folder, or (v2) a Deezer track.
 * [uri] is the song's audio reference: the file's content URI, or `deezer:track:<id>`, resolved to a
 * preview URL right before playback (see AudioRef).
 */
@Entity(
    tableName = "songs",
    indices = [Index(value = ["uri"], unique = true), Index("folder_id"), Index(value = ["deezer_id"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The folder of a local file; null for streamed songs. */
    @ColumnInfo(name = "folder_id") val folderId: Long?,
    val uri: String,
    /** Path inside the picked folder, e.g. "Rock/Queen - Bohemian Rhapsody.mp3"; empty for streamed songs. Host-only display. */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "tag_original_year") val tagOriginalYear: Int?,
    @ColumnInfo(name = "tag_year") val tagYear: Int?,
    @ColumnInfo(name = "mb_recording_id") val mbRecordingId: String?,
    val isrc: String?,
    @ColumnInfo(name = "has_cover") val hasCover: Boolean,
    /** Tags couldn't be read; the song is listed with its file name and no year. */
    @ColumnInfo(name = "read_failed") val readFailed: Boolean = false,
    @ColumnInfo(name = "source", defaultValue = "LOCAL") val source: SongSource = SongSource.LOCAL,
    /** The Deezer track id of a streamed song. */
    @ColumnInfo(name = "deezer_id") val deezerId: Long? = null,
)

/**
 * The host's year edit of a song. Keyed by file URI, falling back to artist + title + duration
 * (seconds) so edits survive a moved file (DECISIONS.md D-023). Exclusion is the "Excluded songs"
 * playlist since feedback round 28.
 */
@Entity(tableName = "song_overrides", indices = [Index(value = ["song_uri"], unique = true), Index(value = ["match_key"])])
data class SongOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "song_uri") val songUri: String,
    /** Normalized "artist|title|durationSeconds". */
    @ColumnInfo(name = "match_key") val matchKey: String,
    @ColumnInfo(name = "manual_year") val manualYear: Int?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** A saved player profile. The photo (already cropped to a circle) lives in app storage. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val initials: String,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    @ColumnInfo(name = "photo_path") val photoPath: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * The running game: a single row holding the serialized game (state, settings, seed, pool order,
 * players) and, apart from it, the game's song list, which is written only when it changes (D-269).
 * Rows saved before that have the songs inside [json] and no [songsJson].
 */
@Entity(tableName = "saved_game")
data class SavedGameEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val json: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "songs_json") val songsJson: String? = null,
) {
    companion object {
        const val SINGLE_ROW = 1
    }
}

/** A MusicBrainz lookup result for a song, including "no match" (year null). */
@Entity(tableName = "mb_cache")
data class MbCacheEntity(
    /** Normalized artist|title|recording id|ISRC; survives rescans. */
    @PrimaryKey @ColumnInfo(name = "song_key") val songKey: String,
    /** Earliest accepted year, or null for "no match". */
    val year: Int?,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
    /** Earliest release of the accepted recordings (for its cover art). */
    @ColumnInfo(name = "release_id") val releaseId: String? = null,
    @ColumnInfo(name = "release_group_id") val releaseGroupId: String? = null,
    /** The Cover Art Archive was asked for this release (whether or not it had a cover). */
    @ColumnInfo(name = "cover_checked", defaultValue = "0") val coverChecked: Boolean = false,
    /** Lookup method version; older results are re-checked by "Verify library" (DECISIONS.md D-069). */
    @ColumnInfo(name = "lookup_version", defaultValue = "0") val lookupVersion: Int = 0,
    /** The year comes from a recording AcoustID linked to the audio (DECISIONS.md D-076). */
    @ColumnInfo(name = "via_fingerprint", defaultValue = "0") val viaFingerprint: Boolean = false,
    /**
     * "Verify by fingerprint" checked this song (D-194): AcoustID was asked about its audio, or the file
     * couldn't be decoded; for a streamed song, its ISRC and name were checked (D-276).
     */
    @ColumnInfo(name = "fingerprint_checked", defaultValue = "0") val fingerprintChecked: Boolean = false,
)

/** A user-made playlist. The built-in "All" playlist is virtual and never stored. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** The Deezer playlist this one was imported from ("Update from Deezer"), if any. */
    @ColumnInfo(name = "deezer_playlist_id") val deezerPlaylistId: Long? = null,
    /** A user playlist, or the app's own "Excluded songs" (feedback round 28). */
    @ColumnInfo(defaultValue = "USER") val kind: PlaylistKind = PlaylistKind.USER,
)

/**
 * [EXCLUDED]: the one "Excluded songs" playlist (feedback round 28). Its songs never enter a game; it
 * isn't listed with the user's playlists and doesn't keep a streamed song in the library.
 */
enum class PlaylistKind { USER, EXCLUDED }

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlist_id", "song_id"],
    indices = [Index("song_id")],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SongEntity::class, parentColumns = ["id"], childColumns = ["song_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaylistSongEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "song_id") val songId: Long,
)

/**
 * One round as it went for a saved player (feedback round 47), recorded when the round is committed. [game]
 * is the game's seed; [mode] a GameMode name. [played]: their turn (Song Order) or their guess (Timeline);
 * [yearsOff]: how far their guess was; [namingChance] and [named]: see RoundStats.
 */
@Entity(
    tableName = "player_rounds",
    indices = [Index(value = ["game", "round", "profile_id"], unique = true), Index(value = ["profile_id"])],
    foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profile_id"], onDelete = ForeignKey.CASCADE)],
)
data class PlayerRoundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: Long,
    val game: Long,
    val round: Int,
    val mode: String,
    val year: Int,
    val played: Boolean,
    @ColumnInfo(name = "years_off") val yearsOff: Int?,
    @ColumnInfo(name = "naming_chance") val namingChance: Boolean,
    val named: Boolean,
)

/** A finished game of a saved player who was still playing at the end (feedback round 47). */
@Entity(
    tableName = "player_games",
    indices = [Index(value = ["game", "profile_id"], unique = true), Index(value = ["profile_id"])],
    foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profile_id"], onDelete = ForeignKey.CASCADE)],
)
data class PlayerGameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: Long,
    val game: Long,
    val mode: String,
    val won: Boolean,
    @ColumnInfo(name = "finished_at") val finishedAt: Long,
)
