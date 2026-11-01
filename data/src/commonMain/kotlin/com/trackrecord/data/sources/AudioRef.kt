package com.trackrecord.data.sources

/**
 * What a song's audio reference (SongEntity.uri) points at: a local file, played as is, or a Deezer
 * track, whose preview URL is fetched right before playback because it expires (SPEC v2, Playback).
 */
sealed interface AudioRef {
    data class Local(val uri: String) : AudioRef
    data class Deezer(val trackId: Long) : AudioRef

    companion object {
        private const val DEEZER_PREFIX = "deezer:track:"

        fun deezerUri(trackId: Long): String = DEEZER_PREFIX + trackId

        fun parse(uri: String): AudioRef =
            uri.removePrefix(DEEZER_PREFIX).takeIf { uri.startsWith(DEEZER_PREFIX) }?.toLongOrNull()?.let { Deezer(it) } ?: Local(uri)
    }
}

/** Turns an audio reference into something the player can open, or null if it can't be played now. */
fun interface AudioResolver {
    suspend fun playableUri(uri: String): String?

    /** Prepares a song that plays soon (e.g. fetches its preview URL); never blocks playback. */
    suspend fun prefetch(uri: String) {}

    companion object {
        /** Local files only (tests and previews of local songs); the app uses DeezerAudioResolver. */
        val LocalOnly = AudioResolver { uri -> (AudioRef.parse(uri) as? AudioRef.Local)?.uri }
    }
}
