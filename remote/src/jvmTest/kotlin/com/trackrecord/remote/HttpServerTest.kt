package com.trackrecord.remote

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpServerTest {
    private var stream: SseStream? = null
    private val opened = CountDownLatch(1)
    private val closed = CountDownLatch(1)

    private val server = HttpServer { r ->
        when (r.path) {
            "/echo" -> Response.text(200, "${r.method} ${r.query["a"]} ${r.bodyText}")
            "/events" -> Response.EventStream { s ->
                stream = s
                s.onClose { closed.countDown() }
                opened.countDown()
            }
            "/boom" -> error("handler failed")
            else -> Response.text(404, "nope")
        }
    }
    private val port = server.start(0)

    @AfterTest
    fun stop() = server.stop()

    private fun connect(path: String) = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection

    @Test
    fun `get with query and post with body`() {
        val get = connect("/echo?a=hello%20world")
        assertEquals(200, get.responseCode)
        assertEquals("GET hello world ", get.inputStream.readBytes().decodeToString())

        val post = connect("/echo").apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("""{"x":1}""".toByteArray()) }
        }
        assertEquals("POST null {\"x\":1}", post.inputStream.readBytes().decodeToString())
    }

    @Test
    fun `unknown paths and failing handlers`() {
        assertEquals(404, connect("/missing").responseCode)
        assertEquals(500, connect("/boom").responseCode)
    }

    @Test
    fun `oversized bodies are refused`() {
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("POST /echo HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".toByteArray())
            val status = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
            assertEquals("HTTP/1.1 413 Payload Too Large", status)
        }
    }

    @Test
    fun `event stream delivers events and notices when the browser leaves`() {
        val socket = Socket("127.0.0.1", port)
        socket.getOutputStream().write("GET /events HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        assertEquals("HTTP/1.1 200 OK", reader.readLine())
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        while (reader.readLine().isNotEmpty()) Unit // headers

        assertTrue(stream!!.send("""{"a":1}""", event = "view"))
        assertEquals("event: view", reader.readLine())
        assertEquals("""data: {"a":1}""", reader.readLine())
        assertEquals("", reader.readLine())

        socket.close()
        assertTrue(closed.await(2, TimeUnit.SECONDS), "closing the socket closes the stream")
        assertTrue(!stream!!.send("late"))
    }

    /** A raw connection; [closedWithin] tells whether the server hung up within [ms]. */
    private fun raw(target: Int = port) = Socket("127.0.0.1", target).apply { soTimeout = 3_000 }

    private fun Socket.closedWithin(ms: Int): Boolean {
        soTimeout = ms
        return try {
            getInputStream().read() == -1
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: java.io.IOException) {
            true
        }
    }

    private fun limited(limits: HttpServer.Limits, handler: (Request) -> Response = { Response.text(200, "ok") }) =
        HttpServer(limits, handler)

    @Test
    fun `a request that trickles in is cut off at its deadline`() {
        val slow = limited(HttpServer.Limits(requestDeadlineMs = 300))
        val p = slow.start(0)
        try {
            raw(p).use { s ->
                s.getOutputStream().write("GET /echo HTTP/1.1\r\nHost: x".toByteArray())
                assertTrue(s.closedWithin(2_000), "closed although bytes keep the connection busy")
            }
        } finally {
            slow.stop()
        }
    }

    @Test
    fun `one device can't take every connection`() {
        val capped = limited(HttpServer.Limits(maxPerClient = 2))
        val p = capped.start(0)
        try {
            raw(p).use { a ->
                raw(p).use { b ->
                    raw(p).use { c -> assertTrue(c.closedWithin(1_000), "the third connection is refused") }
                    assertTrue(!a.closedWithin(200) && !b.closedWithin(200), "the first two stay open")
                }
            }
        } finally {
            capped.stop()
        }
    }

    private fun openStream(target: Int): Pair<Socket, BufferedReader> {
        val socket = raw(target)
        socket.getOutputStream().write("GET /events HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        assertEquals("HTTP/1.1 200 OK", reader.readLine())
        while (reader.readLine().isNotEmpty()) Unit
        return socket to reader
    }

    @Test
    fun `a phone that stops reading doesn't hold up the others`() {
        val opened = java.util.concurrent.LinkedBlockingQueue<SseStream>()
        val server = limited(HttpServer.Limits(writeTimeoutMs = 300)) { Response.EventStream { opened += it } }
        val p = server.start(0)
        try {
            // The stuck phone: a tiny receive window and it never reads, so the server's write blocks.
            val stuck = Socket().apply { receiveBufferSize = 1024; connect(java.net.InetSocketAddress("127.0.0.1", p)) }
            stuck.getOutputStream().write("GET /events HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val stuckStream = opened.poll(2, TimeUnit.SECONDS)!!
            val stuckClosed = CountDownLatch(1)
            stuckStream.onClose { stuckClosed.countDown() }
            val (healthy, reader) = openStream(p)
            val healthyStream = opened.poll(2, TimeUnit.SECONDS)!!

            assertTrue(stuckStream.send("x".repeat(16 * 1024 * 1024), event = "view"), "sending never blocks")
            assertTrue(healthyStream.send("hello", event = "view"))
            assertEquals("event: view", reader.readLine())
            assertEquals("data: hello", reader.readLine())
            assertTrue(stuckClosed.await(3, TimeUnit.SECONDS), "the stuck write is cut off")
            healthy.close()
            stuck.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `only the newest view waits for a busy stream`() {
        val opened = java.util.concurrent.LinkedBlockingQueue<SseStream>()
        val server = limited(HttpServer.Limits()) { Response.EventStream { opened += it } }
        val p = server.start(0)
        try {
            val (socket, reader) = openStream(p)
            val s = opened.poll(2, TimeUnit.SECONDS)!!
            repeat(200) { s.send("v$it", event = "view") }
            // Whatever was written first, the stream ends on the newest view and skips most of the rest.
            val seen = mutableListOf<String>()
            while (seen.lastOrNull() != "data: v199") reader.readLine().takeIf { it.startsWith("data:") }?.let(seen::add)
            assertTrue(seen.size < 200, "views waiting behind a write are replaced by newer ones")
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a reloaded page replaces the same device's oldest stream`() {
        val server = limited(HttpServer.Limits(maxStreamsPerClient = 1)) { Response.EventStream { } }
        val p = server.start(0)
        try {
            val (first, _) = openStream(p)
            val (second, _) = openStream(p)
            assertTrue(first.closedWithin(1_000), "the older stream is closed")
            assertTrue(!second.closedWithin(200))
            second.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `listens only on the addresses it is given`() {
        val other = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address }
        val server = limited(HttpServer.Limits())
        val p = server.start(0, listOf(java.net.InetAddress.getLoopbackAddress()))
        try {
            raw(p).close()
            if (other != null) {
                val refused = runCatching { Socket().use { it.connect(java.net.InetSocketAddress(other, p), 1_000) } }.isFailure
                assertTrue(refused, "not reachable on $other")
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `only requests for our own address and page are served`() {
        val server = limited(HttpServer.Limits())
        val p = server.start(0, listOf(java.net.InetAddress.getByName("127.0.0.1")))
        fun status(vararg headers: String): String = raw(p).use { s ->
            s.getOutputStream().write(("GET /x HTTP/1.1\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n").toByteArray())
            BufferedReader(InputStreamReader(s.getInputStream())).readLine()
        }
        try {
            assertEquals("HTTP/1.1 200 OK", status("Host: 127.0.0.1:$p"))
            assertEquals("HTTP/1.1 200 OK", status("Host: 127.0.0.1:$p", "Origin: http://127.0.0.1:$p"))
            assertEquals("HTTP/1.1 403 Forbidden", status("Host: rebound.example:$p"), "DNS rebinding")
            assertEquals("HTTP/1.1 403 Forbidden", status("Host: 127.0.0.1:$p", "Origin: http://evil.example"), "another site")
            assertEquals("HTTP/1.1 403 Forbidden", status(), "no Host")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `responses forbid inline scripts and framing`() {
        val c = connect("/echo")
        assertTrue(c.getHeaderField("Content-Security-Policy").contains("script-src 'self'"))
        assertEquals("DENY", c.getHeaderField("X-Frame-Options"))
    }

    @Test
    fun `falls back to a free port when the preferred one is taken`() {
        val second = HttpServer { Response.text(200, "") }
        try {
            val other = second.start(port)
            assertTrue(other != port && other > 0)
        } finally {
            second.stop()
        }
    }
}
