package com.trackrecord.remote

import com.trackrecord.engine.Card
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.GameState
import com.trackrecord.engine.ReduceResult
import com.trackrecord.engine.RejectReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Internet guests' requests and views through the tunnel into the hub (SPEC v4; D-180). */
class TunnelTest {
    private val json = Json { ignoreUnknownKeys = true }

    private inner class FakeHost : RemoteHost {
        var state: GameState? = null
        lateinit var hub: RemoteHub

        fun publish() = hub.update(HostSnapshot(state?.let { HostGame(it, players, emptyMap()) }, ThemeInfo("LOW_POLY", 4, 4)))

        override fun dispatch(action: GameAction): RejectReason? = when (val r = GameEngine.reduce(state, action)) {
            is ReduceResult.Accepted -> {
                state = r.state
                publish()
                null
            }
            is ReduceResult.Rejected -> r.reason
        }

        override fun declineVeto(playerId: String) = Unit
        override fun vetoPicking(playerId: String, picking: Boolean) = Unit
        override fun togglePlay() = Unit
        override fun replay() = Unit
        override fun rejectText(reason: RejectReason) = "reject:$reason"
        override fun errorText(error: HubError) = "error:$error"
        override fun strings() = mapOf("web_waiting" to "Waiting")
        override fun asset(path: String): Asset? = null
        override fun photo(playerId: String): Asset? = if (playerId == "anna") Asset("image/png", byteArrayOf(1, 2, 3)) else null
        override fun cover(songId: Long): Asset? = null
    }

    private val players = listOf("anna", "ben").map { PlayerInfo(it, it, it.take(2).uppercase(), "#ff0000", "#000000", photo = it == "anna") }
    private val host = FakeHost()
    private val clients = CopyOnWriteArrayList<RemoteClients>()
    private val hub = RemoteHub(host, key = "secret-key", onClients = { clients += it }).also { host.hub = it }
    private val sent = LinkedBlockingQueue<Pair<String, String>>()
    private var tunnel = Tunnel(hub, { peer, message -> sent += peer to message })

    @AfterTest
    fun close() {
        tunnel.close()
        hub.close()
    }

    private fun startGame() {
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(GameMode.TIMELINE, listOf("anna", "ben"), (1L..6L).map { Card(it, 1970 + it.toInt() * 5) }, GameSettings(), seed = 1, currentYear = 2026),
        ) as ReduceResult.Accepted
        host.state = (GameEngine.reduce(start.state, GameAction.Draw) as ReduceResult.Accepted).state
        host.publish()
    }

    private var nextId = 1L

    private fun request(peer: String, method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null): Long {
        val id = nextId++
        val q = query.entries.joinToString(",") { """"${it.key}":"${it.value}"""" }
        val b = body?.let { ""","b":${Json.encodeToString(kotlinx.serialization.serializer<String>(), it)}""" }.orEmpty()
        tunnel.onMessage(peer, """{"id":$id,"m":"$method","p":"$path","q":{$q}$b}""")
        return id
    }

    /** Messages received but not asked for yet, in order. */
    private val held = mutableListOf<Pair<String, JsonObject>>()

    /** The next message for [peer] that matches [accept], as a JSON object; others are kept for later. */
    private fun next(peer: String, accept: (JsonObject) -> Boolean = { true }): JsonObject {
        held.firstOrNull { (to, obj) -> to == peer && accept(obj) }?.let { held.remove(it); return it.second }
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end) {
            val (to, text) = sent.poll(100, TimeUnit.MILLISECONDS) ?: continue
            val obj = json.parseToJsonElement(text).jsonObject
            if (to == peer && accept(obj)) return obj
            held += to to obj
        }
        error("no message for $peer")
    }

    private fun reply(peer: String, id: Long) = json.decodeFromString(TunnelReply.serializer(), next(peer) { it["id"]?.toString() == "$id" }.toString())

    private fun view(peer: String) = json.decodeFromString(ClientView.serializer(), json.decodeFromString(TunnelEvent.serializer(), next(peer) { it["ev"] != null }.toString()).d)

    @Test
    fun `texts and the link check work without the key, which the host adds`() {
        val strings = reply("p1", request("p1", "GET", "/api/strings"))
        assertEquals(200, strings.s)
        assertTrue(strings.text!!.contains("Waiting"))
        assertEquals(200, reply("p1", request("p1", "GET", "/api/check")).s, "the tunnel adds the hub's key")
        assertEquals(404, reply("p1", request("p1", "GET", "/index.html")).s, "only the API goes through the tunnel")
    }

    @Test
    fun `a guest claims a player, sees the game live and acts`() {
        startGame()
        val streamReply = reply("p1", request("p1", "GET", "/api/events", mapOf("c" to "client-1234")))
        assertTrue(streamReply.stream)
        assertEquals("LOW_POLY", view("p1").theme.choice)
        val claimed = reply("p1", request("p1", "POST", "/api/claim", body = """{"client":"client-1234","player":"anna"}"""))
        assertTrue(claimed.text!!.contains("\"ok\":true"))
        val mine = generateSequence { view("p1") }.first { it.me == "anna" }
        val selected = reply("p1", request("p1", "POST", "/api/action", body = """{"client":"client-1234","version":${mine.game!!.version},"type":"select","slot":0}"""))
        assertTrue(selected.text!!.contains("\"ok\":true"), selected.text)
        assertEquals(0, host.state!!.round!!.selectedSlot)
    }

    @Test
    fun `only internet guests learn what to play, and they can ask the host's time`() {
        startGame()
        val listen = ListenInfo("preview", "https://cdn.example/preview.mp3", playing = true, positionMs = 1200, atMs = 1000)
        hub.update(HostSnapshot(HostGame(host.state!!, players, emptyMap(), listen = listen), ThemeInfo("LOW_POLY", 4, 4)))
        reply("p1", request("p1", "GET", "/api/events", mapOf("c" to "client-1234")))
        assertEquals(listen, generateSequence { view("p1") }.first { it.listen != null }.listen)
        // A Wi-Fi guest's stream (not internet) gets no audio to play.
        val wifi = java.util.concurrent.LinkedBlockingQueue<String>()
        val stream = object : SseStream {
            override val isOpen = true
            override fun send(data: String, event: String?) = wifi.offer(data)
            override fun ping() = true
            override fun close() = Unit
            override fun onClose(listener: () -> Unit) = Unit
        }
        (hub.handle(Request("GET", "/api/events", mapOf("k" to hub.key, "c" to "wifi-client-1"), emptyMap(), ByteArray(0))) as Response.EventStream).onOpen(stream)
        val wifiView = json.decodeFromString(ClientView.serializer(), wifi.poll(3, TimeUnit.SECONDS)!!)
        assertEquals(null, wifiView.listen)
        val time = reply("p1", request("p1", "GET", "/api/time"))
        assertTrue(Regex(""""now":\d{13}""").containsMatchIn(time.text!!), time.text)
    }

    @Test
    fun `images come back as base64`() {
        startGame()
        val photo = reply("p1", request("p1", "GET", "/api/photo", mapOf("p" to "anna")))
        assertEquals("image/png", photo.ct)
        assertEquals("AQID", photo.b64)
    }

    @Test
    fun `a guest that leaves drops its live view`() {
        reply("p1", request("p1", "GET", "/api/events", mapOf("c" to "client-1234")))
        assertNotNull(generateSequence { clients.lastOrNull()?.takeIf { it.open == 1 } }.take(1).firstOrNull() ?: run { Thread.sleep(200); clients.last() })
        tunnel.onLeave("p1")
        val end = System.currentTimeMillis() + 2000
        while (clients.last().open != 0 && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(0, clients.last().open)
    }

    @Test
    fun `too many requests are refused, oversized messages ignored`() {
        tunnel.close()
        tunnel = Tunnel(hub, { peer, message -> sent += peer to message }, Tunnel.Limits(perSecond = 0.0, burst = 2.0, maxMessageChars = 200))
        reply("p1", request("p1", "GET", "/api/check"))
        reply("p1", request("p1", "GET", "/api/check"))
        assertEquals(429, reply("p1", request("p1", "GET", "/api/check")).s)
        tunnel.onMessage("p2", """{"id":99,"p":"/api/check","b":"${"x".repeat(300)}"}""")
        assertEquals(null, sent.poll(300, TimeUnit.MILLISECONDS)?.takeIf { it.first == "p2" })
    }
}
