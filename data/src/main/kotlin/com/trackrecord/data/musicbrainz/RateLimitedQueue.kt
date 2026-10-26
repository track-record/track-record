package com.trackrecord.data.musicbrainz

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InterruptedIOException

data class HttpResponse(val code: Int, val body: String)

/** Performs one HTTP request. Throws (e.g. IOException) when offline. */
fun interface HttpFetcher {
    suspend fun get(url: String): HttpResponse

    /** A form POST (AcoustID prefers POST for its long fingerprints). */
    suspend fun post(url: String, form: Map<String, String>): HttpResponse = throw UnsupportedOperationException("POST")
}

/** A request failed in a way worth retrying later: rate limited, server trouble or a timeout. */
class ServiceUnavailableException(message: String) : Exception(message)

/**
 * A global, rate-limited request queue: request starts are at least [minIntervalMs] apart (MusicBrainz
 * asks clients to "never make more than ONE call per second"; AcoustID allows three). Each call makes
 * one attempt. A rate limit (HTTP 503/429), server error or timeout throws [ServiceUnavailableException], so
 * the caller can put the work at the end of its queue (DECISIONS.md D-074).
 *
 * The spacing adapts (feedback rounds 28, 29): it starts at [minIntervalMs], the fastest the service
 * allows, and only grows once something fails, doubling per failure (2 s, 4 s, 8 s … at most
 * [maxBackoffMs]). It stays there, since other phones on the same network may share the limit; every
 * [PROBE_AFTER] successes in a row it shrinks by a fifth, testing whether a faster pace works again.
 * After [IDLE_RESET_MS] without requests the next run starts at the minimum again. Clock and sleep are
 * injected for tests.
 */
class RateLimitedQueue(
    private val fetcher: HttpFetcher,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    private val minIntervalMs: Long = 1_000,
    private val maxBackoffMs: Long = 30_000,
) {
    private val mutex = Mutex()
    private var lastStart: Long? = null
    private var intervalMs = minIntervalMs
    private var successesInRow = 0

    /** The current spacing between request starts. */
    val currentIntervalMs: Long get() = intervalMs

    suspend fun get(url: String): HttpResponse = request { fetcher.get(url) }

    suspend fun post(url: String, form: Map<String, String>): HttpResponse = request { fetcher.post(url, form) }

    private suspend fun request(send: suspend () -> HttpResponse): HttpResponse = mutex.withLock {
        waitForSlot()
        lastStart = clock()
        val response = try {
            send()
        } catch (e: InterruptedIOException) {
            // OkHttp's call and read timeouts; a coroutine cancellation is not one of these.
            slowDown()
            throw ServiceUnavailableException("timeout: ${e.message}")
        }
        if (response.code == 503 || response.code == 429 || response.code >= 500) {
            slowDown()
            throw ServiceUnavailableException("HTTP ${response.code}")
        }
        if (++successesInRow >= PROBE_AFTER) {
            successesInRow = 0
            intervalMs = maxOf(minIntervalMs, intervalMs * 4 / 5)
        }
        response
    }

    private fun slowDown() {
        successesInRow = 0
        intervalMs = minOf(maxOf(intervalMs, minIntervalMs) * 2, maxOf(maxBackoffMs, minIntervalMs))
    }

    private suspend fun waitForSlot() {
        val last = lastStart ?: return
        if (clock() - last > IDLE_RESET_MS) {
            intervalMs = minIntervalMs
            successesInRow = 0
        }
        val wait = last + intervalMs - clock()
        if (wait > 0) sleep(wait)
    }

    private companion object {
        const val PROBE_AFTER = 5
        const val IDLE_RESET_MS = 60_000L
    }
}
