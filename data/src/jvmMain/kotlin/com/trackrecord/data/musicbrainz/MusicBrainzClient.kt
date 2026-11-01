package com.trackrecord.data.musicbrainz

import com.trackrecord.engine.musicbrainz.MatchAcceptance
import com.trackrecord.engine.musicbrainz.MbCandidate
import com.trackrecord.engine.year.TagYears
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@Serializable
data class MbArtistCredit(val name: String = "", val joinphrase: String = "")

@Serializable
data class MbReleaseGroup(val id: String = "")

@Serializable
data class MbRelease(
    val id: String = "",
    val date: String? = null,
    @SerialName("release-group") val releaseGroup: MbReleaseGroup? = null,
)

@Serializable
data class MbRecording(
    val id: String = "",
    val title: String = "",
    val disambiguation: String? = null,
    /** Date of the earliest release that includes the recording (confirmed against live responses). */
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val releases: List<MbRelease> = emptyList(),
) {
    fun toCandidate(currentYear: Int) = MbCandidate(
        artist = artistCredit.joinToString("") { it.name + it.joinphrase },
        title = title,
        disambiguation = disambiguation,
        year = TagYears.parseYear(firstReleaseDate, currentYear),
    )
}

@Serializable
data class MbRecordingList(
    val recordings: List<MbRecording> = emptyList(),
    /** Total matches for a search (a page holds at most 100). */
    val count: Int? = null,
)

/** One artist + title search; with [releasedBefore] only recordings first released up to that year. */
data class NameSearch(val artist: String, val title: String, val releasedBefore: Int? = null)

/** The three candidate sources: tagged recording id, tagged ISRC, and a search on artist + title. */
class MusicBrainzClient(private val queue: RateLimitedQueue, private val root: String = "https://musicbrainz.org/ws/2/") {
    private val json = Json { ignoreUnknownKeys = true }

    /** Recordings for many recording ids in one search (`rid:(a OR b …)`), [BATCH] ids per request. */
    suspend fun recordingsByIds(ids: List<String>): List<MbRecording> =
        ids.chunked(BATCH).flatMap { searchField("rid", it) }

    /** Recordings for many ISRCs in one search (`isrc:(a OR b …)`), [BATCH] ISRCs per request. */
    suspend fun recordingsByIsrcs(isrcs: List<String>): List<MbRecording> =
        isrcs.chunked(BATCH).flatMap { searchField("isrc", it) }

    /** One search for several values of a field; a page that overflows is split and asked again. */
    private suspend fun searchField(field: String, values: List<String>): List<MbRecording> {
        if (values.isEmpty()) return emptyList()
        val page = searchQuery("$field:(${values.joinToString(" OR ") { luceneEscape(it) }})")
        if ((page.count ?: 0) <= page.recordings.size || values.size == 1) return page.recordings
        val half = values.size / 2
        return searchField(field, values.take(half)) + searchField(field, values.drop(half))
    }

    private suspend fun searchQuery(query: String): MbRecordingList {
        val response = queue.get(root + "recording?query=${enc(query)}&fmt=json&limit=100")
        return when (response.code) {
            200 -> json.decodeFromString(MbRecordingList.serializer(), response.body)
            400, 404 -> MbRecordingList()
            else -> throw ServiceUnavailableException("HTTP ${response.code}")
        }
    }

    /**
     * Recording search on artist + title. With [releasedBefore] only recordings first released up to
     * the end of that year are returned (search field firstreleasedate).
     */
    suspend fun search(artist: String, title: String, releasedBefore: Int? = null): MbRecordingList =
        searchQuery(clause(NameSearch(artist, title, releasedBefore)))

    /**
     * Several artist + title searches in one request, joined with OR (D-078): the answer is exactly the
     * union of the single searches (checked live: 6 songs, 81 = 0+46+4+14+6+11 recordings, all
     * present). Returns null when the union doesn't fit on one page, so the caller splits the batch.
     */
    suspend fun searchMany(searches: List<NameSearch>): List<MbRecording>? {
        val page = searchQuery(searches.joinToString(" OR ") { clause(it) })
        return if ((page.count ?: 0) <= page.recordings.size) page.recordings else null
    }

    private fun clause(s: NameSearch): String {
        var query = "artist:\"${luceneEscape(MatchAcceptance.searchText(s.artist))}\" AND recording:\"${luceneEscape(MatchAcceptance.searchText(s.title))}\""
        if (s.releasedBefore != null) query += " AND firstreleasedate:[1000 TO ${s.releasedBefore}-12-31]"
        return "($query)"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun luceneEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        /** Ids per batched search; keeps the URL short and the page (max 100) from overflowing. */
        const val BATCH = 40
    }
}
