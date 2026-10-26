package com.trackrecord.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** An HTTP request as the handler sees it; header names are lower-case. */
class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)
}

sealed interface Response {
    class Bytes(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val cacheable: Boolean = false,
    ) : Response

    /** A Server-Sent Events stream: [onOpen] gets the stream once the headers are written. */
    class EventStream(val onOpen: (SseStream) -> Unit) : Response

    companion object {
        fun text(status: Int, text: String, contentType: String = "text/plain; charset=utf-8") =
            Bytes(status, contentType, text.toByteArray(Charsets.UTF_8))

        fun json(json: String, status: Int = 200) = text(status, json, "application/json; charset=utf-8")
    }
}

/**
 * One open event stream to a browser. Thread-safe and never blocks: [send] queues the event and
 * returns false once the stream is closed. Only the newest data per event name waits to be written,
 * so a slow phone gets the latest view instead of a backlog.
 */
interface SseStream {
    val isOpen: Boolean

    /** A guest over the internet (SPEC v4): it plays the music itself and gets [ClientView.listen]. */
    val internet: Boolean get() = false

    fun send(data: String, event: String? = null): Boolean

    /** A comment line that keeps idle connections (and NAT entries) alive. */
    fun ping(): Boolean

    fun close()

    /** Called once when the browser goes away or [close] is called. */
    fun onClose(listener: () -> Unit)
}

/**
 * A small HTTP/1.1 server for the guest page (SPEC v3; DECISIONS.md D-100, D-165): one request per
 * connection ("Connection: close") plus long-lived Server-Sent Events streams. Plain sockets and
 * threads, no keep-alive, chunking or TLS.
 *
 * Every phase of a connection has a deadline that a watchdog enforces by closing the socket: reading
 * the request, writing the response, and each write to an event stream. Connections and event streams
 * are capped in total and per device, so one device can't lock the others out.
 */
class HttpServer(
    private val limits: Limits = Limits(),
    private val handler: (Request) -> Response,
) {
    data class Limits(
        val maxConnections: Int = 64,
        /** Per remote address: a browser opens up to 6 connections at once, plus its event stream. */
        val maxPerClient: Int = 12,
        val maxStreams: Int = 32,
        /** Per remote address; a further stream closes that device's oldest (a reloaded page). */
        val maxStreamsPerClient: Int = 4,
        /** From accept to the end of the request body. */
        val requestDeadlineMs: Long = 5_000,
        /** For writing one response or one event. */
        val writeTimeoutMs: Long = 10_000,
        val watchdogIntervalMs: Long = 250,
    )

    private val sockets = mutableListOf<ServerSocket>()

    /** "address:port" values a request's Host header may name; empty when listening everywhere (tests). */
    @Volatile private var hosts: Set<String> = emptySet()

    /** Loopback (debug builds, adb forwarding): any local port, since adb may forward from another one. */
    @Volatile private var loopback = false
    private var pool: ExecutorService? = null
    private var writers: ExecutorService? = null
    private var watchdog: ScheduledExecutorService? = null
    private val slots = Semaphore(limits.maxConnections)
    private val live: MutableSet<Connection> = ConcurrentHashMap.newKeySet()
    private val perClient = HashMap<InetAddress, Int>()
    private val streams = ArrayList<SocketSseStream>()

    val isRunning: Boolean @Synchronized get() = sockets.any { !it.isClosed }

    /**
     * Starts listening on [addresses] (every interface if empty), on [preferredPort] or a free port if
     * that one is taken. Returns the port. An address after the first that can't take the port is
     * left out.
     */
    @Synchronized
    fun start(preferredPort: Int, addresses: List<InetAddress> = emptyList()): Int {
        sockets.firstOrNull { !it.isClosed }?.let { return it.localPort }
        val targets: List<InetAddress?> = addresses.ifEmpty { listOf(null) }
        val first = ServerSocket().apply { reuseAddress = true }
        try {
            first.bind(InetSocketAddress(targets[0], preferredPort), BACKLOG)
        } catch (_: BindException) {
            first.bind(InetSocketAddress(targets[0], 0), BACKLOG)
        }
        val bound = mutableListOf(first)
        for (address in targets.drop(1)) {
            val socket = ServerSocket().apply { reuseAddress = true }
            try {
                socket.bind(InetSocketAddress(address, first.localPort), BACKLOG)
                bound += socket
            } catch (_: IOException) {
                runCatching { socket.close() }
            }
        }
        val executor = Executors.newCachedThreadPool { r -> Thread(r, "remote-http").apply { isDaemon = true } }
        pool = executor
        writers = Executors.newCachedThreadPool { r -> Thread(r, "remote-write").apply { isDaemon = true } }
        watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "remote-watchdog").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(::expire, limits.watchdogIntervalMs, limits.watchdogIntervalMs, TimeUnit.MILLISECONDS)
        }
        sockets += bound
        hosts = bound.mapNotNull { it.inetAddress }.filter { !it.isAnyLocalAddress }
            .flatMap { a -> listOfNotNull(a.hostAddress, if (a is java.net.Inet6Address) "[${a.hostAddress}]" else null) }
            .map { "$it:${first.localPort}" }.toSet()
        loopback = bound.any { it.inetAddress?.isLoopbackAddress == true }
        for (socket in bound) Thread({ acceptLoop(socket, executor) }, "remote-accept").apply { isDaemon = true }.start()
        return first.localPort
    }

    @Synchronized
    fun stop() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        live.forEach { it.close() }
        pool?.shutdownNow()
        pool = null
        writers?.shutdownNow()
        writers = null
        watchdog?.shutdownNow()
        watchdog = null
    }

    private fun acceptLoop(server: ServerSocket, executor: ExecutorService) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            val connection = admit(socket)
            if (connection == null) {
                runCatching { socket.close() }
                continue
            }
            try {
                executor.execute { serve(connection) }
            } catch (_: RejectedExecutionException) {
                release(connection)
            }
        }
    }

    /** Takes a slot for [socket], or null when the server or its device is at the limit. */
    private fun admit(socket: Socket): Connection? {
        val client = socket.inetAddress
        synchronized(perClient) {
            if ((perClient[client] ?: 0) >= limits.maxPerClient) return null
            if (!slots.tryAcquire()) return null
            perClient[client] = (perClient[client] ?: 0) + 1
        }
        return Connection(socket, client).also {
            it.expireIn(limits.requestDeadlineMs)
            live += it
        }
    }

    private fun release(connection: Connection) {
        if (!live.remove(connection)) return
        connection.close()
        synchronized(perClient) {
            val left = (perClient[connection.client] ?: 1) - 1
            if (left <= 0) perClient.remove(connection.client) else perClient[connection.client] = left
            slots.release()
        }
    }

    /** Closes every connection whose current phase ran past its deadline. Runs on the watchdog. */
    private fun expire() {
        val now = System.nanoTime()
        for (c in live) if (now - c.deadline > 0) c.close()
    }

    private fun serve(connection: Connection) {
        val s = connection.socket
        try {
            s.soTimeout = limits.requestDeadlineMs.toInt()
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()
            val request = try {
                readRequest(input)
            } catch (e: BadRequest) {
                connection.expireIn(limits.writeTimeoutMs)
                write(output, Response.text(e.status, e.message ?: ""))
                return
            } ?: return
            if (!fromOurPage(request)) {
                connection.expireIn(limits.writeTimeoutMs)
                write(output, Response.text(403, "Forbidden"))
                return
            }
            // The handler may wait for the host app; that time isn't the guest's to spend.
            connection.noDeadline()
            val response = try {
                handler(request)
            } catch (_: Exception) {
                Response.text(500, "Internal error")
            }
            connection.expireIn(limits.writeTimeoutMs)
            when (response) {
                is Response.Bytes -> write(output, if (request.method == "HEAD") Response.Bytes(response.status, response.contentType, ByteArray(0)) else response)
                is Response.EventStream -> stream(connection, input, output, response)
            }
        } catch (_: IOException) {
            // The browser went away mid-request, or the watchdog closed the connection.
        } finally {
            release(connection)
        }
    }

    private fun stream(connection: Connection, input: InputStream, output: OutputStream, response: Response.EventStream) {
        val writer = writers ?: return
        val stream = SocketSseStream(connection, output, writer)
        val replaced = synchronized(streams) {
            if (streams.size >= limits.maxStreams) null else {
                val mine = streams.filter { it.connection.client == connection.client }
                streams += stream
                mine.take((mine.size + 1 - limits.maxStreamsPerClient).coerceAtLeast(0))
            }
        }
        if (replaced == null) {
            write(output, Response.text(503, "Too many connections"))
            return
        }
        replaced.forEach { it.close() }
        try {
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\n" +
                    "Connection: keep-alive\r\nX-Accel-Buffering: no\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            connection.noDeadline()
            connection.socket.soTimeout = 0
            response.onOpen(stream)
            // Browsers send nothing on an event stream: reading returns -1 (or fails) when they go away.
            while (input.read() != -1) Unit
        } catch (_: IOException) {
        } finally {
            stream.close()
        }
    }

    /**
     * DNS rebinding and cross-site requests (DECISIONS.md D-168): the Host header must name an address
     * this server listens on, as an IP (a rebound host name never matches), and a request carrying an
     * Origin must come from that same address.
     */
    private fun fromOurPage(request: Request): Boolean {
        val host = request.headers["host"] ?: return false
        val known = hosts
        val local = loopback && (host.startsWith("127.0.0.1:") || host.startsWith("localhost:") || host.startsWith("[::1]:"))
        if (known.isNotEmpty() && host !in known && !local) return false
        val origin = request.headers["origin"] ?: return true
        return origin == "http://$host"
    }

    /** A live connection and the deadline of what it is doing now ([Long.MAX_VALUE]-like when none). */
    private class Connection(val socket: Socket, val client: InetAddress) {
        @Volatile var deadline: Long = System.nanoTime() + NO_DEADLINE_NS

        fun expireIn(ms: Long) {
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        }

        fun noDeadline() {
            deadline = System.nanoTime() + NO_DEADLINE_NS
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private inner class SocketSseStream(
        val connection: Connection,
        private val output: OutputStream,
        private val writer: ExecutorService,
    ) : SseStream {
        private val listeners = mutableListOf<() -> Unit>()

        /** Event name → its newest frame, oldest first; written by one drain at a time. */
        private val pending = LinkedHashMap<String, String>()
        private var draining = false
        @Volatile private var open = true
        override val isOpen: Boolean get() = open

        override fun send(data: String, event: String?): Boolean {
            val text = buildString {
                if (event != null) append("event: ").append(event).append('\n')
                for (line in data.split('\n')) append("data: ").append(line).append('\n')
                append('\n')
            }
            return enqueue("e:${event.orEmpty()}", text)
        }

        override fun ping(): Boolean = enqueue(PING, ": ping\n\n")

        private fun enqueue(key: String, text: String): Boolean {
            synchronized(this) {
                if (!open) return false
                pending.remove(key)
                pending[key] = text
                if (draining) return true
                draining = true
            }
            try {
                writer.execute(::drain)
            } catch (_: RejectedExecutionException) {
                close()
                return false
            }
            return true
        }

        /** Writes what is pending; a write that takes longer than allowed is cut off by the watchdog. */
        private fun drain() {
            while (true) {
                val text = synchronized(this) {
                    val next = pending.entries.firstOrNull()
                    if (next == null || !open) {
                        draining = false
                        return
                    }
                    pending.remove(next.key)
                    next.value
                }
                try {
                    connection.expireIn(limits.writeTimeoutMs)
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.flush()
                    connection.noDeadline()
                } catch (_: IOException) {
                    synchronized(this) { draining = false }
                    close()
                    return
                }
            }
        }

        override fun close() {
            val notify = synchronized(this) {
                if (!open) return
                open = false
                pending.clear()
                listeners.toList()
            }
            synchronized(streams) { streams.remove(this) }
            connection.close()
            notify.forEach { runCatching { it() } }
        }

        override fun onClose(listener: () -> Unit) {
            val closed = synchronized(this) {
                if (open) listeners += listener
                !open
            }
            if (closed) listener()
        }
    }

    private class BadRequest(val status: Int, message: String) : Exception(message)

    companion object {
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
        private const val BACKLOG = 50
        private const val PING = "ping"

        /**
         * Only the page's own files run or load (D-168); inline styles stay allowed because the page
         * sets colours and positions per element.
         */
        private const val CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
            "font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

        /** "No deadline": far enough ahead to never pass, small enough not to overflow nanoTime arithmetic. */
        private val NO_DEADLINE_NS = TimeUnit.DAYS.toNanos(365)

        /** Reads one request; null when the connection closed before sending anything. */
        internal fun readRequest(input: InputStream): Request? {
            val head = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b == -1) {
                    if (head.size() == 0) return null
                    throw BadRequest(400, "Incomplete request")
                }
                head.write(b)
                if (head.size() > MAX_HEADER_BYTES) throw BadRequest(431, "Headers too large")
                matched = when {
                    (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                    (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
            val lines = head.toString(Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
            val parts = lines.firstOrNull()?.split(' ') ?: throw BadRequest(400, "No request line")
            if (parts.size != 3) throw BadRequest(400, "Bad request line")
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY_BYTES) throw BadRequest(413, "Body too large")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n == -1) throw BadRequest(400, "Incomplete body")
                read += n
            }
            val target = parts[1]
            val q = target.indexOf('?')
            val path = decode(if (q < 0) target else target.substring(0, q))
            val query = if (q < 0) emptyMap() else parseQuery(target.substring(q + 1))
            return Request(parts[0].uppercase(), path, query, headers, body)
        }

        internal fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) decode(pair) to "" else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
            }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }

        private fun write(output: OutputStream, response: Response.Bytes) {
            val head = buildString {
                append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
                append("Content-Type: ").append(response.contentType).append("\r\n")
                append("Content-Length: ").append(response.body.size).append("\r\n")
                append(if (response.cacheable) "Cache-Control: max-age=3600\r\n" else "Cache-Control: no-store\r\n")
                append("X-Content-Type-Options: nosniff\r\n")
                append("Content-Security-Policy: ").append(CSP).append("\r\n")
                append("X-Frame-Options: DENY\r\n")
                append("Referrer-Policy: no-referrer\r\n")
                append("Connection: close\r\n\r\n")
            }
            try {
                output.write(head.toByteArray(Charsets.US_ASCII))
                output.write(response.body)
                output.flush()
            } catch (_: SocketException) {
            }
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> if (status >= 500) "Internal Server Error" else "Status"
        }
    }
}
