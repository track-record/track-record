package com.trackrecord.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Internet guests (SPEC v4; DECISIONS.md D-180): each guest's browser has a direct message channel to
 * the host. Over it the guest page sends the same requests as on Wi-Fi, and this tunnel hands them
 * to the same [RemoteHub], so claims, views, checks and limits are shared. The host adds the hub's
 * key itself: a peer that reached the room already proved it knows the room's secret.
 *
 * Messages are JSON text. Guest to host: a [TunnelRequest]. Host to guest: a [TunnelReply] per
 * request, and for the live view (`/api/events`) [TunnelEvent]s until the peer leaves.
 */
class Tunnel(
    private val hub: RemoteHub,
    /** Sends [message] to [peer]; must not block. */
    private val output: (peer: String, message: String) -> Unit,
    private val limits: Limits = Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Limits(
        val maxMessageChars: Int = 64 * 1024,
        /** Requests per peer: a bucket of [burst] refilled at [perSecond]. */
        val perSecond: Double = 15.0,
        val burst: Double = 40.0,
        val maxPeers: Int = 16,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val workers: ExecutorService = Executors.newFixedThreadPool(4) { r -> Thread(r, "remote-tunnel").apply { isDaemon = true } }
    private val peers = HashMap<String, Peer>()

    private inner class Peer(val id: String) {
        var tokens = limits.burst
        var refilledAt = clock()
        var stream: PeerStream? = null

        fun take(): Boolean {
            val now = clock()
            tokens = minOf(limits.burst, tokens + (now - refilledAt) / 1000.0 * limits.perSecond)
            refilledAt = now
            if (tokens < 1) return false
            tokens -= 1
            return true
        }
    }

    /**
     * A message from [peer]; may be called from any thread but the main one. The rate limit comes before
     * anything is parsed, so a flood costs little; a refused request is answered by its leading id only
     * (DECISIONS.md D-261).
     */
    fun onMessage(peer: String, text: String) {
        if (text.length > limits.maxMessageChars) return
        val allowed = synchronized(peers) {
            val p = peers[peer] ?: (if (peers.size >= limits.maxPeers) null else Peer(peer).also { peers[peer] = it })
            p?.take()
        }
        if (allowed == null) return
        if (!allowed) {
            LEADING_ID.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { reply(peer, TunnelReply(it, 429, "text/plain", text = "Too many requests")) }
            return
        }
        val request = runCatching { json.decodeFromString(TunnelRequest.serializer(), text) }.getOrNull() ?: return
        try {
            workers.execute { handle(peer, request) }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    /** [peer] went away: its live view ends. */
    fun onLeave(peer: String) {
        val stream = synchronized(peers) { peers.remove(peer)?.stream }
        stream?.close()
    }

    fun close() {
        val open = synchronized(peers) { peers.values.mapNotNull { it.stream }.also { peers.clear() } }
        open.forEach { it.close() }
        workers.shutdownNow()
    }

    private fun handle(peer: String, request: TunnelRequest) {
        val path = request.p
        if (!path.startsWith("/api/")) {
            reply(peer, TunnelReply(request.id, 404, "text/plain", text = "Not found"))
            return
        }
        val query = request.q + ("k" to hub.key)
        val response = try {
            hub.handle(Request(request.m.uppercase(), path, query, emptyMap(), request.b.orEmpty().toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            Response.text(500, "Internal error")
        }
        when (response) {
            is Response.Bytes -> reply(peer, bytesReply(request.id, response))
            is Response.EventStream -> {
                val stream = PeerStream(peer)
                val previous = synchronized(peers) {
                    val p = peers[peer]
                    if (p == null) null else p.stream.also { p.stream = stream }
                }
                // One live view per peer: a new one (after a reload) replaces the old.
                previous?.close()
                reply(peer, TunnelReply(request.id, 200, "text/event-stream", stream = true))
                response.onOpen(stream)
            }
        }
    }

    private fun bytesReply(id: Long, r: Response.Bytes): TunnelReply {
        val textual = r.contentType.startsWith("text/") || r.contentType.startsWith("application/json")
        return if (textual) {
            TunnelReply(id, r.status, r.contentType, text = r.body.toString(Charsets.UTF_8))
        } else {
            TunnelReply(id, r.status, r.contentType, b64 = Base64.getEncoder().encodeToString(r.body))
        }
    }

    private fun reply(peer: String, reply: TunnelReply) {
        runCatching { output(peer, json.encodeToString(TunnelReply.serializer(), reply)) }
    }

    /** The live view of one peer, as messages; sending never blocks ([output] must not). */
    private inner class PeerStream(private val peer: String) : SseStream {
        private val listeners = mutableListOf<() -> Unit>()
        @Volatile private var open = true
        override val isOpen: Boolean get() = open
        override val internet: Boolean get() = true

        override fun send(data: String, event: String?): Boolean {
            if (!open) return false
            return runCatching { output(peer, json.encodeToString(TunnelEvent.serializer(), TunnelEvent(event ?: "message", data))) }.isSuccess
        }

        override fun ping(): Boolean = open

        override fun close() {
            val notify = synchronized(this) {
                if (!open) return
                open = false
                listeners.toList()
            }
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
}

/** The page writes the id first (p2p.js), so a refused request can be answered without parsing it. */
private val LEADING_ID = Regex("""^\{"id":(\d{1,15})[,}]""")

/** A guest's request: id (echoed in the reply), method, path ("/api/…"), query, body. */
@Serializable
data class TunnelRequest(val id: Long, val m: String = "GET", val p: String, val q: Map<String, String> = emptyMap(), val b: String? = null)

/** The reply to request [id]: status, content type, and a text or base64 body; [stream] for the live view. */
@Serializable
data class TunnelReply(
    val id: Long,
    val s: Int,
    val ct: String,
    val text: String? = null,
    val b64: String? = null,
    val stream: Boolean = false,
)

/** One event of the live view (`ev` is "view"), sent without a request. */
@Serializable
data class TunnelEvent(val ev: String, val d: String)
