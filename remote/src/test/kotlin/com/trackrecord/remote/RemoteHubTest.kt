package com.trackrecord.remote

import com.trackrecord.engine.Card
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.GameState
import com.trackrecord.engine.Phase
import com.trackrecord.engine.ReduceResult
import com.trackrecord.engine.RejectReason
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteHubTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Plays the host: applies actions through the engine and hands every new state back to the hub. */
    private inner class FakeHost : RemoteHost {
        var state: GameState? = null
        /** The players of a game being set up (feedback round 32). */
        var lobby: List<PlayerInfo>? = null
        val declined = mutableListOf<String>()
        lateinit var hub: RemoteHub

        fun publish() = hub.update(HostSnapshot(state?.let { HostGame(it, players, songs) }, ThemeInfo("VINYL", 4, 25), lobby = lobby))

        override fun dispatch(action: GameAction): RejectReason? =
            when (val r = GameEngine.reduce(state, action)) {
                is ReduceResult.Accepted -> {
                    state = r.state
                    publish()
                    null
                }
                is ReduceResult.Rejected -> r.reason
            }

        override fun declineVeto(playerId: String) {
            declined += playerId
        }

        val picking = mutableListOf<Pair<String, Boolean>>()
        override fun vetoPicking(playerId: String, picking: Boolean) { this.picking += playerId to picking }

        val audio = mutableListOf<String>()
        override fun togglePlay() { audio += "toggle" }
        override fun replay() { audio += "replay" }

        val commands = mutableListOf<HostCommand>()
        override fun hostCommand(command: HostCommand): String? {
            commands += command
            val action = when (command) {
                HostCommand.Reveal -> GameAction.Reveal
                HostCommand.Next -> GameAction.Next
                HostCommand.Undo -> GameAction.UndoVeto
                is HostCommand.Named -> GameAction.SetNamedBy(command.playerId, command.named)
                is HostCommand.CorrectYear -> GameAction.CorrectYear(command.year)
                is HostCommand.Adjust -> GameAction.AdjustVetoes(command.playerId, command.delta)
                is HostCommand.ClipLimit -> GameAction.SetClipLimit(command.seconds)
                is HostCommand.StartOffset -> GameAction.SetStartOffset(command.seconds)
                is HostCommand.Remove -> GameAction.RemovePlayer(command.playerId)
                HostCommand.WholeSong, is HostCommand.Seek -> return null
            }
            return dispatch(action)?.let(::rejectText)
        }

        override fun rejectText(reason: RejectReason) = "reject:$reason"
        override fun errorText(error: HubError) = "error:$error"
        override fun strings() = mapOf("web_waiting" to "Waiting")
        override fun asset(path: String) = if (path == "index.html") Asset("text/html", "<html>".toByteArray()) else null
        override fun photo(playerId: String): Asset? = null
        override fun cover(songId: Long): Asset? = Asset("image/jpeg", byteArrayOf(songId.toByte()))
    }

    private val players = listOf("anna", "ben", "cara").map { PlayerInfo(it, it.replaceFirstChar(Char::uppercase), it.take(2).uppercase(), "#ff0000", "#000000") }

    /** Song 1–3 are the starting cards (1970, 1990, 2010), 4 is the first drawn song (1985). */
    private val songs = mapOf(
        1L to SongText("Starter A", "X"), 2L to SongText("Starter B", "Y"), 3L to SongText("Starter C", "Z"),
        4L to SongText("Secret Song", "Hidden Artist"), 5L to SongText("Next", "N"),
    )

    private val host = FakeHost()
    private val clients = CopyOnWriteArrayList<RemoteClients>()
    private val hub = RemoteHub(host, key = "k123", onClients = { clients += it }).also { host.hub = it }

    @AfterTest
    fun close() = hub.close()

    private fun startTimeline(): GameState {
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(
                GameMode.TIMELINE, listOf("anna", "ben", "cara"),
                listOf(Card(1, 1970), Card(2, 1990), Card(3, 2010), Card(4, 1985), Card(5, 2001)),
                GameSettings(), seed = 1, currentYear = 2026,
            ),
        ) as ReduceResult.Accepted
        // Deal in a fixed order regardless of the seed: Anna 1970, Ben 1990, Cara 2010, then 1985 is drawn.
        val dealt = start.state.copy(
            players = start.state.players.mapIndexed { i, p -> p.copy(timeline = listOf(Card(i + 1L, listOf(1970, 1990, 2010)[i]))) },
            pool = listOf(Card(4, 1985), Card(5, 2001)),
        )
        host.state = (GameEngine.reduce(dealt, GameAction.Draw) as ReduceResult.Accepted).state
        host.publish()
        return host.state!!
    }

    private fun get(path: String) = hub.handle(Request("GET", path.substringBefore('?'), HttpServer.parseQuery(path.substringAfter('?', "")), emptyMap(), ByteArray(0)))

    private fun post(path: String, body: String) =
        hub.handle(Request("POST", path, mapOf("k" to "k123"), emptyMap(), body.toByteArray()))

    private fun Response.text() = (this as Response.Bytes).body.toString(Charsets.UTF_8)

    private fun Response.reply() = json.decodeFromString(ActionReply.serializer(), text())

    /** Opens an event stream for [client] and returns the views it receives. */
    private fun open(client: String): FakeStream {
        val stream = FakeStream()
        (get("/api/events?k=k123&c=$client") as Response.EventStream).onOpen(stream)
        return stream
    }

    private inner class FakeStream : SseStream {
        val views = CopyOnWriteArrayList<ClientView>()
        private val closers = mutableListOf<() -> Unit>()
        override var isOpen = true

        override fun send(data: String, event: String?): Boolean {
            views += json.decodeFromString(ClientView.serializer(), data)
            return true
        }

        override fun ping() = true

        override fun close() {
            isOpen = false
            closers.forEach { it() }
        }

        override fun onClose(listener: () -> Unit) {
            closers += listener
        }

        /** Waits for the push thread and returns the newest view. */
        fun latest(): ClientView {
            repeat(100) {
                drain()
                if (views.isNotEmpty()) return views.last()
            }
            error("no view received")
        }
    }

    /** Lets the single push thread finish everything queued so far. */
    private fun drain() {
        val done = java.util.concurrent.CountDownLatch(1)
        host.publish()
        Thread { Thread.sleep(20); done.countDown() }.start()
        done.await(1, TimeUnit.SECONDS)
    }

    private fun claim(client: String, player: String, takeover: Boolean = false) =
        post("/api/claim", """{"client":"$client","player":"$player","takeover":$takeover}""").reply()

    private fun act(client: String, type: String, slot: Int? = null, year: Int? = null, version: Long? = null, player: String? = null, delta: Int? = null): ActionReply {
        val v = version ?: open("probe-$client").latest().game!!.version
        val round = host.state?.round?.number
        val extra = listOfNotNull(
            slot?.let { "\"slot\":$it" }, year?.let { "\"year\":$it" }, round?.let { "\"round\":$it" },
            player?.let { "\"player\":\"$it\"" }, delta?.let { "\"delta\":$it" },
        ).joinToString("") { ",$it" }
        return post("/api/action", """{"client":"$client","version":$v,"type":"$type"$extra}""").reply()
    }

    @Test
    fun `api requests need the key, the page does not`() {
        assertEquals(403, (get("/api/check") as Response.Bytes).status)
        assertEquals(403, (get("/api/check?k=wrong") as Response.Bytes).status)
        assertEquals(403, (get("/api/events?k=wrong&c=phone-0001") as Response.Bytes).status)
        assertTrue(get("/api/check?k=k123").reply().ok)
        assertEquals("""{"web_waiting":"Waiting"}""", get("/api/strings").text(), "texts need no key")
        assertEquals("<html>", get("/").text())
        assertEquals(404, (get("/../secret") as Response.Bytes).status)
    }

    @Test
    fun `without a game the phone waits`() {
        host.publish()
        val view = open("phone-0001").latest()
        assertNull(view.game)
        assertEquals("VINYL", view.theme.choice)
        assertEquals("error:NO_GAME", claim("phone-0001", "anna").error)
    }

    @Test
    fun `a player can be picked while the host sets up the game and stays picked when it starts`() {
        host.lobby = players
        host.publish()
        val stream = open("phone-0001")
        assertEquals(players.map { it.id }, stream.latest().lobby?.map { it.id }, "the setup's players")
        assertTrue(claim("phone-0001", "ben").ok)
        assertEquals("ben", stream.latest().me)
        assertEquals("error:PICK_PLAYER", claim("phone-0002", "nobody").error)

        host.lobby = null
        startTimeline()
        val view = stream.latest()
        assertNull(view.lobby)
        assertEquals("ben", view.me, "the pick carries into the game (feedback round 32)")
    }

    @Test
    fun `claiming a player, taking over, and the host disconnecting`() {
        startTimeline()
        val ben = open("phone-ben1")
        assertTrue(claim("phone-ben1", "ben").ok)
        assertEquals("ben", ben.latest().me)
        assertEquals(mapOf("ben" to true), clients.last().claimed)

        val other = claim("phone-ben2", "ben")
        assertFalse(other.ok)
        assertTrue(other.taken)
        // Ben's first phone is still connected: the host decides (D-182).
        val pending = claim("phone-ben2", "ben", takeover = true)
        assertFalse(pending.ok)
        assertTrue(pending.pending)
        assertEquals(listOf(Takeover("phone-ben2", "ben")), clients.last().takeovers)
        assertEquals("ben", ben.latest().me, "nothing changes before the host allows it")
        hub.approveTakeover("phone-ben2")
        assertNull(generateSequence { ben.latest() }.first { it.me == null }.me, "the first phone lost Ben")
        assertEquals(listOf("ben"), ben.latest().taken)

        hub.disconnect("ben")
        assertEquals(emptyMap(), clients.last().claimed)
    }

    @Test
    fun `a denied takeover tells the asking phone, a phone that went away is taken over at once`() {
        startTimeline()
        val first = open("phone-ben1")
        assertTrue(claim("phone-ben1", "ben").ok)
        val second = open("phone-ben2")
        assertTrue(claim("phone-ben2", "ben", takeover = true).pending)
        assertEquals("ben", generateSequence { second.latest() }.first { it.pendingPlayer != null }.pendingPlayer)
        hub.denyTakeover("phone-ben2")
        assertEquals("ben", generateSequence { second.latest() }.first { it.deniedPlayer != null }.deniedPlayer)
        assertEquals("ben", first.latest().me)
        assertTrue(clients.last().takeovers.isEmpty())

        first.close() // the first phone goes away
        assertTrue(claim("phone-ben2", "ben", takeover = true).ok, "no one to ask: taken over right away")
    }

    @Test
    fun `a blocked phone loses its player and can't do anything`() {
        startTimeline()
        val phone = open("phone-anna")
        assertTrue(claim("phone-anna", "anna").ok)
        hub.block("anna")
        assertTrue(generateSequence { phone.latest() }.first { it.blocked }.blocked)
        assertNull(phone.latest().game, "a blocked phone sees nothing of the game")
        assertEquals(emptyMap(), clients.last().claimed)
        assertEquals("error:BLOCKED", claim("phone-anna", "anna").error)
        assertEquals("error:BLOCKED", act("phone-anna", "select", slot = 0).error)
    }

    @Test
    fun `the current song stays hidden until reveal`() {
        startTimeline()
        val phone = open("phone-cara")
        claim("phone-cara", "cara")
        val before = json.encodeToString(ClientView.serializer(), phone.latest())
        assertFalse("Secret Song" in before)
        assertFalse("Hidden Artist" in before)
        assertFalse("1985" in before)
        assertFalse("2001" in before, "the pool is never sent")

        host.dispatch(GameAction.SelectSlot(1))
        host.dispatch(GameAction.LockIn)
        host.dispatch(GameAction.Reveal)
        val revealed = phone.latest().game!!.revealed
        assertNotNull(revealed)
        assertEquals("Secret Song", revealed.title)
        assertEquals(1985, revealed.year)
        assertEquals("kept", revealed.outcome)
        assertEquals("anna", revealed.outcomePlayer)
    }

    @Test
    fun `only the active player places, others veto or decline`() {
        startTimeline()
        claim("phone-anna", "anna")
        claim("phone-ben1", "ben")
        assertEquals("error:NOT_YOUR_TURN", act("phone-ben1", "select", slot = 0).error)
        assertTrue(act("phone-anna", "select", slot = 1).ok)
        assertTrue(act("phone-anna", "lockIn").ok)
        assertEquals(Phase.VETOING, host.state!!.phase)

        assertEquals("reject:SLOT_TAKEN", act("phone-ben1", "veto", slot = 1).error)
        assertTrue(act("phone-ben1", "decline").ok)
        assertEquals(listOf("ben"), host.declined)
        assertEquals("error:NOT_YOUR_TURN", act("phone-anna", "decline").error, "the active player can't veto")
    }

    @Test
    fun `play and replay only on the phone's own turn`() {
        startTimeline()
        claim("phone-anna", "anna")
        claim("phone-ben1", "ben")
        assertEquals("error:NOT_YOUR_TURN", act("phone-ben1", "togglePlay").error)
        assertTrue(act("phone-anna", "togglePlay").ok)
        assertTrue(act("phone-anna", "replay").ok)
        assertEquals(listOf("toggle", "replay"), host.audio)
        host.dispatch(GameAction.SelectSlot(1))
        host.dispatch(GameAction.LockIn)
        // After Lock in Anna keeps playback (D-188); Cara, who can't veto, doesn't get it either way.
        claim("phone-cara", "cara")
        host.state = host.state!!.copy(players = host.state!!.players.map { if (it.id == "cara") it.copy(vetoes = 0) else it })
        host.publish()
        assertEquals("error:NOT_YOUR_TURN", act("phone-cara", "togglePlay").error, "no veto left: no playback")
        assertTrue(act("phone-ben1", "togglePlay").ok, "a player who could veto may listen again")
        assertTrue(act("phone-ben1", "replay").ok)
    }

    @Test
    fun `the player who placed the card keeps playback while others veto`() {
        startTimeline()
        claim("phone-anna", "anna")
        host.dispatch(GameAction.SelectSlot(1))
        host.dispatch(GameAction.LockIn)
        assertTrue(act("phone-anna", "togglePlay").ok)
    }

    @Test
    fun `a co-host phone gets the host controls, other phones don't`() {
        startTimeline()
        claim("phone-anna", "anna")
        claim("phone-ben1", "ben")
        val ben = open("phone-ben1")
        assertEquals("error:NOT_HOST", act("phone-ben1", "hostReveal").error)
        hub.setCoHost("ben")
        assertEquals("ben", clients.last().coHost)
        assertTrue(generateSequence { ben.latest() }.first { it.coHost }.coHost)
        assertTrue(act("phone-ben1", "togglePlay").ok, "a co-host controls playback any time")

        host.dispatch(GameAction.SelectSlot(1))
        host.dispatch(GameAction.LockIn)
        assertTrue(act("phone-ben1", "hostReveal").ok)
        assertEquals(Phase.REVEALED, host.state!!.phase)
        assertTrue(act("phone-ben1", "hostAdjust", player = "cara", delta = -1).ok)
        assertTrue(act("phone-ben1", "hostWhole").ok)
        assertTrue(act("phone-ben1", "hostNext").ok)
        assertEquals(listOf(HostCommand.Reveal, HostCommand.Adjust("cara", -1), HostCommand.WholeSong, HostCommand.Next), host.commands)
        assertEquals("error:NOT_HOST", act("phone-anna", "hostNext").error)

        hub.setCoHost(null)
        assertEquals(null, clients.last().coHost)
        assertEquals("error:NOT_HOST", act("phone-ben1", "hostNext").error)
    }

    @Test
    fun `a co-host removes a player like the host, whatever they saw`() {
        startTimeline()
        claim("phone-ben1", "ben")
        hub.setCoHost("ben")
        assertEquals("error:BAD_REQUEST", act("phone-ben1", "hostRemove").error)
        assertTrue(act("phone-ben1", "hostRemove", player = "cara", version = -1).ok)
        assertEquals(listOf("anna", "ben"), host.state!!.players.map { it.id })
        assertEquals("error:NOT_HOST", act("phone-anna", "hostRemove", player = "ben").error)
    }

    /** The co-host's controls end with the claim they were given for (REVIEW-2 S-11; D-257). */
    private fun coHostLosesControls(lose: () -> Unit) {
        startTimeline()
        claim("phone-anna", "anna")
        claim("phone-ben1", "ben")
        hub.setCoHost("ben")
        assertEquals("ben", clients.last().coHost)
        lose()
        assertEquals("error:NOT_HOST", act("phone-ben1", "hostAdjust", player = "cara", delta = -1).error)
        assertEquals(null, clients.last().coHost, "the host sees that nobody has the controls")
        assertFalse(open("phone-ben1").latest().coHost)
    }

    @Test
    fun `a co-host who lets go of their player loses the host controls`() =
        coHostLosesControls { assertTrue(post("/api/release", """{"client":"phone-ben1"}""").reply().ok) }

    @Test
    fun `a co-host who switches player loses the host controls`() = coHostLosesControls { assertTrue(claim("phone-ben1", "cara").ok) }

    @Test
    fun `a disconnected co-host loses the host controls`() = coHostLosesControls { hub.disconnect("ben") }

    @Test
    fun `taking over the co-host's player doesn't pass on the host controls`() = coHostLosesControls {
        assertTrue(claim("phone-new1", "ben", takeover = true).ok)
        assertEquals("error:NOT_HOST", act("phone-new1", "hostAdjust", player = "cara", delta = -1).error)
    }

    @Test
    fun `a removed co-host loses the host controls`() = coHostLosesControls { assertNull(host.dispatch(GameAction.RemovePlayer("ben"))) }

    @Test
    fun `while the host is in the menu guests see the pause and can't act`() {
        startTimeline()
        claim("phone-anna", "anna")
        val phone = open("phone-anna")
        val version = phone.latest().game!!.version
        hub.update(HostSnapshot(HostGame(host.state!!, players, songs), ThemeInfo("VINYL", 4, 25), paused = true))
        repeat(50) { if (!phone.views.last().paused) Thread.sleep(20) }
        assertTrue(phone.views.last().paused)
        assertEquals("error:PAUSED", act("phone-anna", "select", slot = 0, version = version).error)
        assertEquals("error:PAUSED", act("phone-anna", "togglePlay", version = version).error)
        assertEquals(Phase.PLACING, host.state!!.phase)
    }

    @Test
    fun `tapping veto tells the host, only when the phone's player could veto`() {
        startTimeline()
        claim("phone-ben1", "ben")
        claim("phone-anna", "anna")
        assertEquals("error:NOT_YOUR_TURN", act("phone-ben1", "vetoStart").error, "not before Lock in")
        host.dispatch(GameAction.SelectSlot(1))
        host.dispatch(GameAction.LockIn)
        assertTrue(act("phone-ben1", "vetoStart").ok)
        assertTrue(act("phone-ben1", "vetoCancel").ok)
        assertEquals("error:NOT_YOUR_TURN", act("phone-anna", "vetoStart").error, "the active player can't veto")
        assertEquals(listOf("ben" to true, "ben" to false), host.picking)
    }

    @Test
    fun `covers only for public songs, scoreboard after reveal`() {
        startTimeline()
        assertEquals(200, (get("/api/cover?k=k123&s=1") as Response.Bytes).status, "a starting card")
        assertEquals(404, (get("/api/cover?k=k123&s=4") as Response.Bytes).status, "the current song before reveal")
        assertEquals(404, (get("/api/cover?k=k123&s=5") as Response.Bytes).status, "a pool song")
        host.dispatch(GameAction.SelectSlot(1))
        host.dispatch(GameAction.LockIn)
        host.dispatch(GameAction.Reveal)
        assertEquals(200, (get("/api/cover?k=k123&s=4") as Response.Bytes).status, "revealed")
        val view = open("phone-x1234567").latest().game!!
        assertEquals(4L, view.revealed!!.songId)
        assertEquals(ScoreView("anna", 2, 1, vetoes = 2), view.scoreboard.first())
    }

    @Test
    fun `skip and buy from a phone`() {
        val state = startTimeline()
        val rules = state.settings.copy(timeline = state.settings.timeline.copy(skipCost = 1, buyCost = 1))
        host.state = state.copy(settings = rules, pool = state.pool + listOf(Card(6, 1960), Card(7, 1950)))
        host.publish()
        claim("phone-anna", "anna")
        claim("phone-ben1", "ben")
        assertEquals("error:NOT_YOUR_TURN", act("phone-ben1", "skip").error, "only the active player skips")
        assertTrue(act("phone-anna", "skip").ok)
        assertEquals(1, host.state!!.player("anna")!!.vetoes)
        assertTrue(act("phone-ben1", "buy").ok)
        val view = open("phone-ben1").latest().game!!
        assertEquals("ben", view.purchase?.playerId)
        assertEquals(1, view.skipCost)
    }

    @Test
    fun `a veto from a phone is placed for that phone's player`() {
        startTimeline()
        claim("phone-cara", "cara")
        host.dispatch(GameAction.SelectSlot(0))
        host.dispatch(GameAction.LockIn)
        assertTrue(act("phone-cara", "veto", slot = 1).ok)
        assertEquals("cara", host.state!!.round!!.vetoes.single().playerId)
    }

    @Test
    fun `actions on an outdated view are refused`() {
        startTimeline()
        claim("phone-anna", "anna")
        val old = open("phone-anna").latest().game!!.version
        host.dispatch(GameAction.SelectSlot(0))
        assertEquals("error:STALE", act("phone-anna", "select", slot = 1, version = old).error)
    }

    @Test
    fun `changes the guests can't see keep the version, so their taps still count`() {
        startTimeline()
        claim("phone-anna", "anna")
        val seen = open("phone-anna").latest().game!!.version
        // A background lookup adds a song to the pool and corrects the hidden year of the current song.
        host.state = (GameEngine.reduce(host.state, GameAction.AddToPool(Card(9, 1999))) as ReduceResult.Accepted).state
        host.state = (GameEngine.reduce(host.state, GameAction.ApplyLookupYear(4, 1986)) as ReduceResult.Accepted).state
        host.publish()
        assertEquals(seen, open("phone-anna").latest().game!!.version)
        assertTrue(act("phone-anna", "select", slot = 1, version = seen).ok)
    }

    @Test
    fun `a late Skip or Don't veto doesn't hit the next song or round`() {
        val state = startTimeline()
        host.state = state.copy(settings = state.settings.copy(timeline = state.settings.timeline.copy(skipCost = 1)))
        host.publish()
        claim("phone-anna", "anna")
        claim("phone-ben1", "ben")
        val before = open("phone-anna").latest().game!!.version
        host.dispatch(GameAction.ReplaceUnplayable) // the song changed before the tap arrived
        assertEquals("error:STALE", act("phone-anna", "skip", version = before).error)

        host.dispatch(GameAction.SelectSlot(0))
        host.dispatch(GameAction.LockIn)
        val round = host.state!!.round!!.number
        val late = post("/api/action", """{"client":"phone-ben1","version":0,"type":"decline","round":${round - 1}}""").reply()
        assertEquals("error:STALE", late.error)
        assertTrue(host.declined.isEmpty())
        assertTrue(act("phone-ben1", "decline").ok, "a decline for this round counts whatever the version")
    }

    @Test
    fun `without a claimed player nothing can be done`() {
        startTimeline()
        assertEquals("error:PICK_PLAYER", act("phone-none", "select", slot = 0).error)
        assertEquals(400, (post("/api/action", "not json") as Response.Bytes).status)
    }

    @Test
    fun `closest year guesses only on the phone's turn`() {
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(GameMode.CLOSEST_YEAR, listOf("anna", "ben"), listOf(Card(4, 1985), Card(5, 2001)), GameSettings(), seed = 3, currentYear = 2026),
        ) as ReduceResult.Accepted
        host.state = (GameEngine.reduce(start.state, GameAction.Draw) as ReduceResult.Accepted).state
        host.publish()
        val first = host.state!!.round!!.currentGuesserId!!
        val second = if (first == "anna") "ben" else "anna"
        claim("phone-$first", first)
        claim("phone-$second", second)
        assertEquals("error:NOT_YOUR_TURN", act("phone-$second", "guess", year = 1990).error)
        assertTrue(act("phone-$first", "guess", year = 1990).ok)
        assertEquals("reject:DUPLICATE_GUESS", act("phone-$second", "guess", year = 1990).error)
        val view = open("phone-$second").latest().game!!
        assertEquals(second, view.currentGuesser)
        assertEquals(listOf(GuessView(first, 1990)), view.guesses)
    }

    @Test
    fun `hidden guesses come from any phone and only the own year is sent`() {
        val rules = com.trackrecord.engine.ClosestYearSettings(hiddenGuesses = true)
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(GameMode.CLOSEST_YEAR, listOf("anna", "ben", "cara"), listOf(Card(4, 1985), Card(5, 2001)), GameSettings(closestYear = rules), seed = 3, currentYear = 2026),
        ) as ReduceResult.Accepted
        host.state = (GameEngine.reduce(start.state, GameAction.Draw) as ReduceResult.Accepted).state
        host.publish()
        val last = host.state!!.round!!.guessOrder.last()
        val other = host.state!!.round!!.guessOrder.first()
        claim("phone-$last", last)
        claim("phone-$other", other)
        assertTrue(act("phone-$last", "guess", year = 1990).ok, "out of turn")
        assertTrue(act("phone-$other", "guess", year = 1990).ok, "the same year")
        assertEquals("error:NOT_YOUR_TURN", act("phone-$last", "guess", year = 1991).error, "once")
        val view = open("phone-$other").latest().game!!
        assertEquals(listOf(GuessView(last, null), GuessView(other, 1990)), view.guesses)
        assertTrue(view.hiddenGuesses)
        assertEquals(1, view.waitingFor.size)
    }

    private fun startHidden(): List<String> {
        val rules = com.trackrecord.engine.ClosestYearSettings(hiddenGuesses = true)
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(GameMode.CLOSEST_YEAR, listOf("anna", "ben", "cara"), listOf(Card(4, 1985), Card(5, 2001)), GameSettings(closestYear = rules), seed = 3, currentYear = 2026),
        ) as ReduceResult.Accepted
        host.state = (GameEngine.reduce(start.state, GameAction.Draw) as ReduceResult.Accepted).state
        host.publish()
        return host.state!!.round!!.guessOrder.onEach { claim("phone-$it", it) }
    }

    @Test
    fun `hidden guesses made at the same time both count`() {
        val order = startHidden()
        val seen = open("probe-phone").latest().game!!.version
        assertTrue(act("phone-${order[2]}", "guess", year = 1990, version = seen).ok)
        assertTrue(act("phone-${order[0]}", "guess", year = 1980, version = seen).ok, "the version moved, but a hidden guess only needs the round")
        assertEquals(2, host.state!!.round!!.guesses.size)
        val lateRound = post("/api/action", """{"client":"phone-${order[1]}","version":$seen,"type":"guess","year":1970,"round":9}""").reply()
        assertEquals("error:STALE", lateRound.error)
    }

    @Test
    fun `guesses in turn still need the version the phone saw`() {
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(GameMode.CLOSEST_YEAR, listOf("anna", "ben"), listOf(Card(4, 1985), Card(5, 2001)), GameSettings(), seed = 3, currentYear = 2026),
        ) as ReduceResult.Accepted
        host.state = (GameEngine.reduce(start.state, GameAction.Draw) as ReduceResult.Accepted).state
        host.publish()
        val first = host.state!!.round!!.guessOrder.first()
        claim("phone-$first", first)
        val seen = open("probe-phone").latest().game!!.version
        assertEquals("error:STALE", act("phone-$first", "guess", year = 1990, version = seen - 1).error)
        assertTrue(act("phone-$first", "guess", year = 1990, version = seen).ok)
    }

    @Test
    fun `Lock in naming its gap goes through right after a drag, whatever the version`() {
        startTimeline()
        claim("phone-anna", "anna")
        assertTrue(act("phone-anna", "dragSelect", slot = 1, version = 0).ok)
        assertEquals("error:STALE", act("phone-anna", "lockIn", slot = 0, version = 0).error, "not the gap the host has")
        assertEquals("error:STALE", act("phone-anna", "lockIn", version = 0).error, "without its gap the version counts")
        assertTrue(act("phone-anna", "lockIn", slot = 1, version = 0).ok)
        assertEquals(Phase.VETOING, host.state!!.phase)
    }

    @Test
    fun `gaps dragged on a phone reach the host as they change, whatever the version`() {
        startTimeline()
        claim("phone-anna", "anna")
        claim("phone-ben", "ben")
        assertTrue(act("phone-anna", "dragSelect", slot = 1, version = 0).ok)
        assertEquals(1, host.state!!.round!!.selectedSlot)
        assertTrue(act("phone-anna", "dragSelect", slot = 0, version = 0).ok, "an older version is fine while dragging")
        assertEquals(0, host.state!!.round!!.selectedSlot)
        assertEquals("error:NOT_YOUR_TURN", act("phone-ben", "dragSelect", slot = 1).error)
        val wrongRound = post("/api/action", """{"client":"phone-anna","version":0,"type":"dragSelect","slot":1,"round":7}""").reply()
        assertEquals("error:STALE", wrongRound.error)
    }

    @Test
    fun `a removed player's phone watches`() {
        startTimeline()
        claim("phone-cara", "cara")
        assertNull(host.dispatch(GameAction.RemovePlayer("cara")))
        val view = open("phone-cara").latest()
        assertNull(view.me)
        assertTrue(view.left)
        assertEquals(listOf("cara"), view.game!!.removed.map { it.id })
        assertEquals("error:PICK_PLAYER", act("phone-cara", "buy").error)
    }

    @Test
    fun `players show who is remote and the gaps that can be chosen`() {
        startTimeline()
        claim("phone-ben1", "ben")
        val view = open("phone-ben1").latest().game!!
        assertEquals(listOf(false, true, false), view.players.map { it.remote })
        assertEquals("anna", view.activeId)
        assertEquals(listOf(0, 1), view.gaps)
        assertEquals(listOf(CardView(1990, "Starter B", "Y", 2)), view.cards["ben"])
    }

    @Test
    fun `closing a stream marks the player disconnected`() {
        startTimeline()
        val phone = open("phone-ben1")
        claim("phone-ben1", "ben")
        assertEquals(mapOf("ben" to true), clients.last().claimed)
        phone.close()
        assertEquals(mapOf("ben" to false), clients.last().claimed)
    }
}
