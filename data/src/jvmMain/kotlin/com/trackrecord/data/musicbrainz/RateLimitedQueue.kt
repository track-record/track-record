package com.trackrecord.data.musicbrainz

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 * the caller can put the work at the end of its queue (DECISIONS.md D-074). Only the starts are spaced: up to
 * [maxInFlight] requests wait for their answers at once, so a slow answer doesn't hold up the next start
 * (feedback round 48; MusicBrainz answers in about a second, at times in seven).
 *
 * The spacing adapts (feedback rounds 28, 29): it starts at [minIntervalMs], the fastest the service
 * allows, and only grows once something fails, doubling per failure (2 s, 4 s, 8 s … at most
 * [maxBackoffMs]). It doesn't jump back, since other phones on the same network may share the limit: every
 * [PROBE_AFTER] successes in a row it halves, testing whether a faster pace works again (feedback round 47:
 * a fifth less per 5 successes took over 20 minutes to get from 30 s back to 1 s; round 48: after every success,
 * as MusicBrainz refuses now and then under load, and waiting to recover cost more time than the refusals).
 * After [IDLE_RESET_MS] without requests the next run starts at the minimum again. Clock and sleep are
 * injected for tests.
 */
class RateLimitedQueue(
    private val fetcher: HttpFetcher,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    private val minIntervalMs: Long = 1_000,
    /** At most 10 s between requests, however often they fail (feedback round 47; was 30 s). */
    private val maxBackoffMs: Long = 10_000,
    /** Requests waiting for their answers at once; 1 makes each wait for the one before. */
    maxInFlight: Int = 3,
) {
    private val mutex = Mutex()

    /** Requests under way: the next starts on time without waiting for the answers before it (feedback round 48). */
    private val inFlight = Semaphore(maxInFlight)
    private var lastStart: Long? = null
    private var intervalMs = minIntervalMs
    private var successesInRow = 0

    /** When the spacing last grew: requests started before then were paced for the old spacing. */
    private var slowedDownAt: Long? = null

    /** The current spacing between request starts. */
    val currentIntervalMs: Long get() = intervalMs

    suspend fun get(url: String): HttpResponse = request { fetcher.get(url) }

    suspend fun post(url: String, form: Map<String, String>): HttpResponse = request { fetcher.post(url, form) }

    private suspend fun request(send: suspend () -> HttpResponse): HttpResponse = inFlight.withPermit {
        // Only the starts are spaced out; the answer is awaited outside the lock.
        val started = mutex.withLock {
            waitForSlot()
            clock().also { lastStart = it }
        }
        val response = try {
            send()
        } catch (e: InterruptedIOException) {
            // OkHttp's call and read timeouts; a coroutine cancellation is not one of these.
            mutex.withLock { slowDown(started) }
            throw ServiceUnavailableException("timeout: ${e.message}")
        }
        mutex.withLock {
            if (response.code == 503 || response.code == 429 || response.code >= 500) {
                slowDown(started)
                throw ServiceUnavailableException("HTTP ${response.code}")
            }
            if (++successesInRow >= PROBE_AFTER) {
                successesInRow = 0
                intervalMs = maxOf(minIntervalMs, intervalMs / 2)
            }
        }
        response
    }

    /**
     * Doubles the spacing after a failure of a request [started] then. Requests already under way when it last grew
     * count once: several refused together are one sign of being too fast, not several.
     */
    private fun slowDown(started: Long) {
        successesInRow = 0
        val last = slowedDownAt
        if (last != null && started < last) return
        slowedDownAt = clock()
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
        const val PROBE_AFTER = 1
        const val IDLE_RESET_MS = 60_000L
    }
}
