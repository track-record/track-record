package com.trackrecord.data.musicbrainz

import com.trackrecord.data.db.SongEntity
import com.trackrecord.engine.musicbrainz.MatchAcceptance
import com.trackrecord.engine.musicbrainz.MbCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Computes a song's Chromaprint fingerprint (compressed, base64), or null if the file can't be decoded. */
fun interface Fingerprinter {
    suspend fun fingerprint(song: SongEntity): String?
}

@Serializable
data class AcoustIdArtist(val name: String = "")

@Serializable
data class AcoustIdRecording(val id: String = "", val title: String? = null, val artists: List<AcoustIdArtist> = emptyList())

@Serializable
data class AcoustIdResult(val id: String = "", val score: Double = 0.0, val recordings: List<AcoustIdRecording> = emptyList())

@Serializable
data class AcoustIdError(val code: Int = 0, val message: String = "")

@Serializable
data class AcoustIdFingerprintResults(val index: Int = -1, val results: List<AcoustIdResult> = emptyList())

@Serializable
data class AcoustIdResponse(
    val status: String = "",
    val results: List<AcoustIdResult> = emptyList(),
    /** Batched lookups (`batch=1`) answer per fingerprint. */
    val fingerprints: List<AcoustIdFingerprintResults> = emptyList(),
    val error: AcoustIdError? = null,
)

/** The AcoustID API key was rejected; fingerprint matching stays off until it is fixed in Settings. */
class AcoustIdKeyException(message: String) : Exception(message)

enum class AcoustIdKeyState {
    /** No key entered. */
    NONE,
    CHECKING,
    VALID,
    REJECTED,

    /** AcoustID couldn't be asked (offline or unavailable). */
    UNREACHABLE,
}

/** What is known about [key]; shown in Settings, the library and song details (D-076). */
data class AcoustIdKeyStatus(val key: String, val state: AcoustIdKeyState)

/**
 * AcoustID lookups: a fingerprint plus the file's duration give the MusicBrainz recordings with that
 * audio. Requests go through their own queue (AcoustID allows 3 per second). Free for non-commercial
 * use; the application key is entered in Settings.
 */
class AcoustIdClient(
    private val queue: RateLimitedQueue,
    private val apiKey: suspend () -> String,
    private val url: String = "https://api.acoustid.org/v2/lookup",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun key(): String = apiKey()

    /**
     * Looks up several fingerprints (with each file's duration in seconds) in one request: `batch=1`
     * with numbered parameters, found in the AcoustID server source (at most 20 per request; it may
     * drop to 10, so [BATCH] is used; D-077). Results come back in the order given; a fingerprint
     * AcoustID can't read gets none.
     */
    suspend fun lookup(items: List<Pair<String, Int>>): List<List<AcoustIdResult>> {
        require(items.size <= BATCH)
        val form = linkedMapOf("client" to apiKey(), "format" to "json", "meta" to "recordings", "batch" to "1")
        items.forEachIndexed { i, (fingerprint, duration) ->
            form["duration.$i"] = duration.coerceAtLeast(1).toString()
            form["fingerprint.$i"] = fingerprint
        }
        val response = queue.post(url, form)
        val parsed = runCatching { json.decodeFromString(AcoustIdResponse.serializer(), response.body) }.getOrNull()
        if (parsed?.error?.code == INVALID_API_KEY) throw AcoustIdKeyException(parsed.error.message)
        // Other errors (e.g. unreadable fingerprints) mean: nothing found for these files.
        if (response.code != 200 || parsed?.status != "ok") return items.map { emptyList() }
        val byIndex = parsed.fingerprints.associate { it.index to it.results }
        return items.indices.map { byIndex[it].orEmpty() }
    }

    /**
     * Whether AcoustID accepts [key], asked with a dummy fingerprint: the key is checked first, so a
     * good key gets "invalid fingerprint" and a bad one "invalid API key" (both checked live). Null if
     * AcoustID can't be reached.
     */
    suspend fun checkKey(key: String): Boolean? = try {
        val (_, parsed) = request(key, DUMMY_FINGERPRINT, 10)
        when (parsed?.error?.code) {
            INVALID_API_KEY -> false
            null -> if (parsed?.status == "ok") true else null
            else -> true
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** A single, unbatched lookup (used to check a key). */
    private suspend fun request(key: String, fingerprint: String, durationSeconds: Int): Pair<Int, AcoustIdResponse?> {
        val response = queue.post(
            url,
            mapOf(
                "client" to key,
                "format" to "json",
                "meta" to "recordings",
                "duration" to durationSeconds.toString(),
                "fingerprint" to fingerprint,
            ),
        )
        return response.code to runCatching { json.decodeFromString(AcoustIdResponse.serializer(), response.body) }.getOrNull()
    }

    companion object {
        /** Fingerprints per batched lookup. */
        const val BATCH = 10

        /** AcoustID error code for an unknown application key. */
        private const val INVALID_API_KEY = 4
        private const val DUMMY_FINGERPRINT = "AQAAAA"
    }
}

/**
 * Fingerprint matching (DECISIONS.md D-075, D-076): the recording ids AcoustID links to a song's
 * audio. Only results scoring at least [MIN_SCORE] count, and only recordings whose names fit the song
 * by the audio-match rule, so the batched MusicBrainz request that follows stays small; acceptance is
 * checked again on the MusicBrainz data. Tracks whether the key works ([keyStatus]).
 */
class FingerprintMatcher(
    private val client: AcoustIdClient,
    private val fingerprinter: Fingerprinter,
    private val enabled: suspend () -> Boolean,
) {
    private val status = MutableStateFlow(AcoustIdKeyStatus("", AcoustIdKeyState.NONE))
    val keyStatus: StateFlow<AcoustIdKeyStatus> = status.asStateFlow()

    suspend fun enabled(): Boolean = enabled.invoke()

    suspend fun fingerprint(song: SongEntity): String? = fingerprinter.fingerprint(song)

    /** Asks AcoustID whether [key] works and publishes the answer in [keyStatus]. */
    suspend fun checkKey(key: String) {
        if (key.isBlank()) {
            status.value = AcoustIdKeyStatus(key, AcoustIdKeyState.NONE)
            return
        }
        status.value = AcoustIdKeyStatus(key, AcoustIdKeyState.CHECKING)
        val ok = client.checkKey(key)
        // A lookup may have answered meanwhile; only the latest key's answer counts.
        if (status.value.key == key) {
            status.value = AcoustIdKeyStatus(key, when (ok) { true -> AcoustIdKeyState.VALID; false -> AcoustIdKeyState.REJECTED; null -> AcoustIdKeyState.UNREACHABLE })
        }
    }

    /** For each (song, fingerprint), in order, the recording ids that fit; one request for up to [AcoustIdClient.BATCH]. */
    suspend fun recordingIds(batch: List<Pair<SongEntity, String>>): List<List<String>> {
        val key = client.key()
        val results = try {
            client.lookup(batch.map { (song, fp) -> fp to ((song.durationMs + 500) / 1000).toInt() })
        } catch (e: AcoustIdKeyException) {
            status.value = AcoustIdKeyStatus(key, AcoustIdKeyState.REJECTED)
            throw e
        }
        status.value = AcoustIdKeyStatus(key, AcoustIdKeyState.VALID)
        return batch.zip(results) { (song, _), found -> idsFor(song, found) }
    }

    private fun idsFor(song: SongEntity, results: List<AcoustIdResult>): List<String> {
        val artist = song.artist.orEmpty()
        return results
            .filter { it.score >= MIN_SCORE }
            .flatMap { it.recordings }
            .filter { r ->
                // Without artists on AcoustID's side, the title decides here; MusicBrainz's data has them.
                r.id.isNotBlank() && r.title != null && if (r.artists.isEmpty()) MatchAcceptance.titlesCompatible(song.title, r.title)
                else MatchAcceptance.acceptsAudioMatch(artist, song.title, MbCandidate(r.artists.joinToString(" & ") { it.name }, r.title, year = null))
            }
            .map { it.id.lowercase() }
            .distinct()
    }

    companion object {
        const val MIN_SCORE = 0.5
    }
}
