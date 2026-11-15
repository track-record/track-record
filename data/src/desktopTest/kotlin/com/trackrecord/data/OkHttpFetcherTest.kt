package com.trackrecord.data

import com.trackrecord.data.musicbrainz.OkHttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real fetcher behind MusicBrainz, AcoustID and Deezer, against a local socket (REVIEW-3 T-08). */
class OkHttpFetcherTest {
    /** Accepts connections and reads, but never answers: a service that hangs. */
    private val silent = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val accepted = Collections.synchronizedList(mutableListOf<Socket>())
    private val acceptor = thread(isDaemon = true) {
        runCatching { while (true) accepted += silent.accept() }
    }

    @AfterTest
    fun cleanUp() {
        silent.close()
        accepted.forEach { runCatching { it.close() } }
    }

    @Test
    fun `cancel ends a request under way at once`() = runBlocking {
        // REVIEW-3 B-14: the call ran on until the 15 s read timeout.
        val fetcher = OkHttpFetcher { "TrackRecordTest/1" }
        val call = async(Dispatchers.Default) { fetcher.get("http://127.0.0.1:${silent.localPort}/ws/2/recording") }
        while (accepted.isEmpty()) delay(10)
        delay(200) // the request is sent and waits for the answer
        val cancelled = System.currentTimeMillis()
        call.cancelAndJoin()
        val took = System.currentTimeMillis() - cancelled
        assertTrue(took < 1_000, "took $took ms")
        assertTrue(call.isCancelled)
    }

    @Test
    fun `an answer comes back with its code and body`() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.accept().use { s ->
                val reader = s.getInputStream().bufferedReader()
                while (reader.readLine().orEmpty().isNotEmpty()) Unit
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
            }
        }
        val response = OkHttpFetcher { "TrackRecordTest/1" }.get("http://127.0.0.1:${server.localPort}/ws/2/x")
        server.close()
        assertEquals(200, response.code)
        assertEquals("{}", response.body)
    }
}
