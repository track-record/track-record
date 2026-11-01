package com.trackrecord.data.net

import okhttp3.OkHttpClient

/**
 * One OkHttp base for every web service the app talks to (MusicBrainz, AcoustID, Deezer, Cover Art
 * Archive, share links): each use takes [client]'s `newBuilder()` for its own timeouts, and all share
 * one connection pool and one set of threads (REVIEW.md P-05).
 */
object Http {
    val client: OkHttpClient by lazy { OkHttpClient() }
}
