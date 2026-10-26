package com.trackrecord.data.deezer

import com.trackrecord.data.musicbrainz.RateLimitedQueue
import com.trackrecord.data.musicbrainz.ServiceUnavailableException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URLEncoder

@Serializable
data class DzError(val type: String = "", val message: String = "", val code: Int = 0)

@Serializable
data class DzArtist(val id: Long = 0, val name: String = "")

@Serializable
data class DzAlbum(
    val id: Long = 0,
    val title: String = "",
    @SerialName("cover_medium") val coverMedium: String? = null,
)

@Serializable
data class DzTrack(
    val id: Long = 0,
    val title: String = "",
    /** The title without version notes ("Wonderwall" for "Wonderwall (Remastered)"). */
    @SerialName("title_short") val titleShort: String? = null,
    val isrc: String? = null,
    /** Seconds (the whole track, not the preview). */
    val duration: Int = 0,
    val readable: Boolean = true,
    /** 30-second MP3; a signed URL that expires after about 15 minutes. */
    val preview: String? = null,
    /** Only in track details (/track/<id>), not in playlist pages. */
    @SerialName("release_date") val releaseDate: String? = null,
    val artist: DzArtist = DzArtist(),
    val album: DzAlbum = DzAlbum(),
    val error: DzError? = null,
) {
    val playable: Boolean get() = readable && !preview.isNullOrBlank()
}

@Serializable
data class DzTrackPage(val data: List<DzTrack> = emptyList(), val total: Int = 0, val next: String? = null, val error: DzError? = null)

@Serializable
data class DzPlaylist(
    val id: Long = 0,
    val title: String = "",
    @SerialName("nb_tracks") val nbTracks: Int = 0,
    val public: Boolean = true,
    val error: DzError? = null,
)

/**
 * Deezer's public API (no account): playlists, tracks with ISRC, release date and a 30-second preview
 * (SPEC v2). Requests go through their own rate-limited queue. Deezer answers errors with HTTP 200 and
 * an `error` object; "Quota limit exceeded" (code 4) counts as a passing failure (D-074), "no data"
 * (code 800) as not found.
 */
class DeezerClient(private val queue: RateLimitedQueue, private val root: String = "https://api.deezer.com/") {
    private val json = Json { ignoreUnknownKeys = true }

    /** The playlist's name and size, or null if it doesn't exist or isn't public. */
    suspend fun playlist(id: Long): DzPlaylist? = get("playlist/$id", DzPlaylist.serializer()) { it.error }?.takeIf { it.id != 0L }

    /** Every track of a playlist, in order (pages of 100). */
    suspend fun playlistTracks(id: Long): List<DzTrack> {
        val tracks = mutableListOf<DzTrack>()
        var index = 0
        while (true) {
            val page = get("playlist/$id/tracks?index=$index&limit=$PAGE", DzTrackPage.serializer()) { it.error } ?: break
            tracks += page.data
            index += page.data.size
            if (page.next == null || page.data.isEmpty() || index >= page.total) break
        }
        return tracks
    }

    /** Track details, including the release date and a fresh preview URL; null if the track is gone. */
    suspend fun track(id: Long): DzTrack? = get("track/$id", DzTrack.serializer()) { it.error }?.takeIf { it.id != 0L }

    suspend fun trackByIsrc(isrc: String): DzTrack? = get("track/isrc:${enc(isrc)}", DzTrack.serializer()) { it.error }?.takeIf { it.id != 0L }

    /** A plain search ("artist title"); Deezer's advanced syntax returned nothing in tests (D-079). */
    suspend fun search(query: String, limit: Int = 10): List<DzTrack> =
        get("search?q=${enc(query)}&limit=$limit", DzTrackPage.serializer()) { it.error }?.data.orEmpty()

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, error: (T) -> DzError?): T? {
        val response = queue.get(root + path)
        if (response.code != 200) throw IOException("Deezer HTTP ${response.code}")
        val parsed = json.decodeFromString(serializer, response.body)
        val e = error(parsed) ?: return parsed
        return when (e.code) {
            QUOTA -> throw ServiceUnavailableException("Deezer quota: ${e.message}")
            NO_DATA -> null
            else -> throw IOException("Deezer error ${e.code}: ${e.message}")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val PAGE = 100
        private const val QUOTA = 4
        private const val NO_DATA = 800

        /** About 9 requests per second, just under Deezer's documented 50 per 5 seconds (feedback round 19). */
        const val SPACING_MS = 110L
    }
}

/** Finds a Deezer playlist id in pasted text: a playlist URL (any language path) or a bare id. */
object DeezerLinks {
    private val playlistUrl = Regex("""deezer\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?playlist/(\d+)""", RegexOption.IGNORE_CASE)
    private val url = Regex("""https?://\S+""")
    private val shareHosts = listOf("link.deezer.com", "deezer.page.link", "dzr.page.link")

    fun playlistId(text: String): Long? =
        playlistUrl.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: text.trim().takeIf { it.matches(Regex("""\d{3,}""")) }?.toLongOrNull()

    /**
     * A share link in the text (the app's "Share" adds a sentence around it), to be followed to the
     * playlist URL: https, and exactly one of Deezer's share hosts (DECISIONS.md D-168).
     */
    fun shareLink(text: String): String? =
        url.findAll(text).map { it.value.trimEnd('.', ',', ')') }.firstOrNull { u ->
            val parsed = u.toHttpUrlOrNull()
            parsed != null && parsed.isHttps && parsed.host.lowercase() in shareHosts
        }
}

/** Follows a link's redirects and returns the final URL (for Deezer share links). */
fun interface LinkResolver {
    suspend fun finalUrl(url: String): String?
}
