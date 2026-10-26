package com.trackrecord.data.library

import com.trackrecord.data.db.FolderEntity
import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.db.SongOverrideEntity
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import com.trackrecord.data.playlists.BuiltInPlaylists
import com.trackrecord.engine.year.ResolvedYear
import com.trackrecord.engine.year.YearInputs
import com.trackrecord.engine.year.YearPreference
import com.trackrecord.engine.year.YearResolver

/** A song as the library screen and pool builder see it: tags, manual changes and the resolved year. */
data class LibrarySong(
    val song: SongEntity,
    val override: SongOverrideEntity?,
    val resolved: ResolvedYear,
    val folderAvailable: Boolean,
    /** MusicBrainz year used for resolution: any accepted match (feedback round 30: no confirmation step). */
    val musicBrainzYear: Int? = null,
    /** Cached lookup, if any (also an unconfirmed match for a missing-year song). */
    val lookup: MbCacheEntity? = null,
    /** Playlists this song is in, built-in ones included (every song is also in the built-in "All"). */
    val playlistIds: Set<Long> = emptySet(),
) {
    val id: Long get() = song.id
    val manualYear: Int? get() = override?.manualYear
    /** On "Excluded songs" (feedback round 28). */
    val excluded: Boolean get() = BuiltInPlaylists.EXCLUDED in playlistIds
    val edited: Boolean get() = manualYear != null
    val missingYear: Boolean get() = resolved.year == null

    /**
     * How MusicBrainz checked this song (feedback round 30); a fingerprint check includes the name search.
     * A streamed song is never fingerprinted, so it counts as checked by name (D-276).
     */
    val verification: Verification
        get() = when {
            lookup == null -> Verification.NONE
            lookup.fingerprintChecked && song.source == SongSource.LOCAL -> Verification.FINGERPRINT
            else -> Verification.NAME
        }

    /** Enters game pools: has a usable year, isn't excluded and its folder is reachable. */
    val playable: Boolean get() = resolved.year != null && !excluded && folderAvailable

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return song.title.contains(q, ignoreCase = true) ||
            song.artist?.contains(q, ignoreCase = true) == true ||
            song.album?.contains(q, ignoreCase = true) == true ||
            song.relativePath.contains(q, ignoreCase = true)
    }

    companion object {
        /** Key used to find a manual change again after a file moved: artist + title + duration (s). */
        fun matchKey(artist: String?, title: String, durationMs: Long): String =
            "${artist.orEmpty().trim().lowercase()}|${title.trim().lowercase()}|${durationMs / 1000}"

        fun assemble(
            songs: List<SongEntity>,
            overrides: List<SongOverrideEntity>,
            folders: List<FolderEntity>,
            currentYear: Int,
            lookups: Map<String, MbCacheEntity> = emptyMap(),
            playlists: Map<Long, Set<Long>> = emptyMap(),
            preference: YearPreference = YearPreference.MUSICBRAINZ,
        ): List<LibrarySong> {
            val byUri = overrides.associateBy { it.songUri }
            val byKey = overrides.groupBy { it.matchKey }
            val available = folders.associate { it.id to it.available }
            return songs.map { s ->
                val override = byUri[s.uri] ?: byKey[matchKey(s.artist, s.title, s.durationMs)]?.firstOrNull()
                // Streamed songs have no folder; whether they can play is checked when they are drawn.
                val reachable = if (s.folderId == null) s.source == SongSource.DEEZER else available[s.folderId] ?: false
                val builtIn = setOf(if (s.source == SongSource.LOCAL) BuiltInPlaylists.LOCAL_FILES else BuiltInPlaylists.STREAMED)
                build(s, override, reachable, currentYear, lookups[MusicBrainzLookup.keyOf(s)], preference)
                    .copy(playlistIds = playlists[s.id].orEmpty() + builtIn)
            }
        }

        fun build(
            song: SongEntity,
            override: SongOverrideEntity?,
            folderAvailable: Boolean,
            currentYear: Int,
            lookup: MbCacheEntity? = null,
            preference: YearPreference = YearPreference.MUSICBRAINZ,
        ): LibrarySong {
            // Every accepted match counts, also for songs without a tag year (feedback round 30).
            val mbYear = lookup?.year
            val resolved = YearResolver.resolve(
                YearInputs(
                    manualYear = override?.manualYear,
                    tagOriginalYear = song.tagOriginalYear,
                    tagYear = song.tagYear,
                    musicBrainzYear = mbYear,
                ),
                currentYear,
                preference,
            )
            return LibrarySong(song, override, resolved, folderAvailable, mbYear, lookup)
        }
    }
}

/** How MusicBrainz checked a song (feedback round 30): not at all, by name, or by audio fingerprint (and name). */
enum class Verification { NONE, NAME, FINGERPRINT }

/** Groups of song states in the filter pane (feedback round 5). */
enum class StatusCategory { PLAYABILITY, DATA_QUALITY, VERIFICATION }

/** Every state a song can be in, for the library's filter pane. */
enum class SongStatus(val category: StatusCategory?) {
    ALL(null),
    PLAYABLE(StatusCategory.PLAYABILITY),
    EXCLUDED(StatusCategory.PLAYABILITY),
    FOLDER_UNAVAILABLE(StatusCategory.PLAYABILITY),
    UNREADABLE(StatusCategory.PLAYABILITY),
    MISSING_YEAR(StatusCategory.DATA_QUALITY),
    SOURCES_DISAGREE(StatusCategory.DATA_QUALITY),
    EDITED(StatusCategory.DATA_QUALITY),
    NOT_CHECKED(StatusCategory.VERIFICATION),
    VERIFIED_BY_NAME(StatusCategory.VERIFICATION),
    VERIFIED_BY_FINGERPRINT(StatusCategory.VERIFICATION),
    NO_MATCH(StatusCategory.VERIFICATION),
    ;

    fun matches(s: LibrarySong): Boolean = when (this) {
        ALL -> true
        PLAYABLE -> s.playable
        MISSING_YEAR -> s.missingYear
        EDITED -> s.edited
        EXCLUDED -> s.excluded
        SOURCES_DISAGREE -> s.resolved.sourcesDisagree
        NOT_CHECKED -> s.verification == Verification.NONE
        // A fingerprint check searches by name too, so those songs count here as well (feedback round 30).
        VERIFIED_BY_NAME -> s.verification != Verification.NONE
        VERIFIED_BY_FINGERPRINT -> s.verification == Verification.FINGERPRINT
        NO_MATCH -> s.lookup != null && s.lookup.year == null
        FOLDER_UNAVAILABLE -> !s.folderAvailable
        UNREADABLE -> s.song.readFailed
    }
}

/** The library's search and filter pane. A null [playlistId] means the built-in "All". */
data class LibraryQuery(
    val text: String = "",
    val status: SongStatus = SongStatus.ALL,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val playlistId: Long? = null,
) {
    /** Number of active filters besides the search text. */
    val activeFilters: Int
        get() = listOf(status != SongStatus.ALL, yearFrom != null, yearTo != null, playlistId != null).count { it }

    fun matches(s: LibrarySong): Boolean {
        if (!s.matches(text) || !status.matches(s)) return false
        if (playlistId != null && playlistId !in s.playlistIds) return false
        if (yearFrom != null || yearTo != null) {
            val y = s.resolved.year ?: return false
            if (yearFrom != null && y < yearFrom) return false
            if (yearTo != null && y > yearTo) return false
        }
        return true
    }
}

fun List<LibrarySong>.filtered(query: LibraryQuery): List<LibrarySong> = filter(query::matches)
