package com.trackrecord.desktop

import com.trackrecord.app.AppConfig
import com.trackrecord.app.platform.RoomHost
import com.trackrecord.app.remote.RoomIdentity
import com.trackrecord.remote.ListenInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.browser.CefBrowser
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/**
 * The internet room on the desktop (SPEC v4; PLATFORMS.md, `JcefRoomHost`): the same room.js as on Android, in a
 * hidden Chromium page behind the bridge. Guests' messages come out through [onMessage] and [onLeave], off Chromium's
 * thread. Local files reach internet guests as the room's stream of the file the host plays.
 */
class JcefRoom(
    private val chromium: Chromium,
    identity: RoomIdentity,
    private val onMessage: (peer: String, text: String) -> Unit,
    private val onLeave: (peer: String) -> Unit,
) : RoomHost {
    private val roomId = identity.roomId
    private val secret = identity.secret
    private val key = identity.key
    override val link: String = "${AppConfig.current.guestPage}#n.$roomId.$secret.${keyId(key.public.encoded)}"

    private var browser: CefBrowser? = null
    private val port = PagePort("room") { text -> received(text) }
    private val peers = HashSet<String>()
    private var song: String? = null
    private var songUrl: String? = null
    private var lastFollow: String? = null

    @Synchronized
    override fun open() {
        if (browser != null) return
        browser = chromium.open("desktop/room.html", port)
        val b64 = Base64.getEncoder()
        port.send(
            buildJsonObject {
                put("t", "join")
                put("room", roomId)
                put("secret", secret)
                put("key", b64.encodeToString(key.private.encoded))
                put("pub", b64.encodeToString(key.public.encoded))
            }.toString(),
        )
    }

    @Synchronized
    override fun follow(listen: ListenInfo?, uri: String?) {
        val stream = listen?.takeIf { it.kind == "stream" && uri != null && uri.startsWith("file:") }
        if (stream != null && uri != song) {
            song = uri
            songUrl = chromium.publish(File(URI(uri!!)))
        }
        val text = buildJsonObject {
            put("t", "audio")
            if (stream == null) {
                put("src", JsonNull)
            } else {
                put("src", songUrl)
                put("playing", stream.playing)
                put("positionMs", stream.positionMs)
                put("atMs", stream.atMs)
            }
        }.toString()
        if (text == lastFollow) return
        lastFollow = text
        port.send(text)
    }

    override fun send(peer: String, text: String) = port.send(
        buildJsonObject {
            put("t", "send")
            put("peer", peer)
            put("data", text)
        }.toString(),
    )

    @Synchronized
    override fun close() {
        port.send(buildJsonObject { put("t", "leave") }.toString())
        browser?.let(chromium::close)
        browser = null
        val gone = synchronized(peers) { peers.toList().also { peers.clear() } }
        gone.forEach(onLeave)
    }

    private fun received(text: String) {
        val m = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        val peer = m["peer"]?.jsonPrimitive?.content.orEmpty()
        when (m["t"]?.jsonPrimitive?.content) {
            "msg" -> onMessage(peer, m["data"]?.jsonPrimitive?.content ?: return)
            "left" -> {
                synchronized(peers) { peers -= peer }
                onLeave(peer)
            }
            "joined" -> synchronized(peers) { peers += peer }
            "log" -> if (AppConfig.current.debug) Log.i("room: ${m["text"]?.jsonPrimitive?.content}")
        }
    }

    private companion object {
        /** The link's host key id: the first 128 bits of the public key's SHA-256, as p2p-common.js computes it. */
        fun keyId(spki: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki).copyOf(16))
    }
}
