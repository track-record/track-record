package com.trackrecord.remote

import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameState
import com.trackrecord.engine.Phase
import com.trackrecord.engine.RejectReason
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** A static file of the guest page. */
class Asset(val contentType: String, val bytes: ByteArray)

/** Errors the hub itself reports to a guest (the engine's own rejections come as [RejectReason]). */
enum class HubError { NO_GAME, PICK_PLAYER, NOT_YOUR_TURN, STALE, BAD_REQUEST, PAUSED, BLOCKED, NOT_HOST }

/** What the hub needs from the host app. [dispatch] and [declineVeto] may block until the host applied them. */
interface RemoteHost {
    /** Applies a guest's game action; null when accepted, else the engine's reason. */
    fun dispatch(action: GameAction): RejectReason?

    /** A remote player tapped "Don't veto". */
    fun declineVeto(playerId: String)

    /** A remote player tapped "Veto" and picks a gap ([picking] true), or cancelled. */
    fun vetoPicking(playerId: String, picking: Boolean)

    /** Play/pause and replay of the host's player, from the guest whose turn it is. */
    fun togglePlay()

    fun replay()

    fun rejectText(reason: RejectReason): String

    fun errorText(error: HubError): String

    /** The guest page's texts by key (DECISIONS.md D-108). */
    fun strings(): Map<String, String>

    /** A file of the guest page ("index.html", "app.js", "fonts/…"), or null. */
    fun asset(path: String): Asset?

    /** A co-host phone's command (D-189); null when done, else why not. */
    fun hostCommand(command: HostCommand): String? = errorText(HubError.NOT_HOST)

    /** A player's profile photo, or null. */
    fun photo(playerId: String): Asset?

    /** A song's album cover as an image, or null. Only asked for public songs. */
    fun cover(songId: Long): Asset?
}

/**
 * The remote play protocol (SPEC v3; DECISIONS.md D-100 to D-106): serves the guest page, checks the
 * key, keeps which phone controls which player, turns guest taps into engine actions, and pushes each
 * phone its view of the game over an event stream. Thread-safe; the host calls [update] with every
 * change and is told about claims through [onClients].
 */
class RemoteHub(
    private val host: RemoteHost,
    val key: String = newKey(),
    private val onClients: (RemoteClients) -> Unit = {},
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private var snapshot = HostSnapshot(null, ThemeInfo("SYSTEM", 4, 4))
    /**
     * What the guests can see of the game (without the version) plus the current song's id, which
     * isn't sent: a change of either makes the version move.
     */
    private var lastVisible: Pair<GameView, Long?>? = null
    private var version = 0L
    private var gameView: GameView? = null
    private var publicSongs: Set<Long> = emptySet()

    /** Client id → the player that phone controls. */
    private val claims = LinkedHashMap<String, String>()

    /** Client id → the kind of device its page says it runs on ([DeviceKinds], DESIGN-REVIEW WD-01). */
    private val devices = HashMap<String, String>()

    /**
     * REVIEW.md S-06 (D-182): a phone's client id (random, kept on that phone) is its device token.
     * Taking over a player from a phone that is still connected waits for the host's OK; a blocked phone
     * can't do anything for the rest of the session.
     */
    private val pendingTakeovers = LinkedHashMap<String, String>()
    private val deniedTakeovers = HashMap<String, String>()
    private val blocked = HashSet<String>()

    /**
     * The phone (client id) with the host controls and the player it had then, if any (D-189). The
     * controls end with that claim: release, another player, disconnect, takeover or removal (D-257).
     */
    private var coHost: CoHost? = null

    private data class CoHost(val client: String, val player: String)

    /** Open event streams → their client id. */
    private val streams = LinkedHashMap<SseStream, String>()
    private val lastSent = HashMap<SseStream, String>()
    private var lastClients: RemoteClients? = null

    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "remote-push").apply { isDaemon = true } }
    private val pinger: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "remote-ping").apply { isDaemon = true } }

    init {
        pinger.scheduleWithFixedDelay({ pingAll() }, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * The host's game, veto window or theme changed. The version moves only when something the guests
     * can see of the game or the current song changed, not when the pool or a hidden year does
     * (DECISIONS.md D-166).
     */
    fun update(next: HostSnapshot) {
        synchronized(lock) {
            // One build per update: the version compares it without the countdown, audio and remote marks (D-262).
            val base = next.game?.let { GameViews.build(it, 0, claimedPlayers()) }
            val visible = base?.let { visibleOf(it) to next.game.state.round?.song?.songId }
            if (visible != lastVisible) {
                version++
                lastVisible = visible
            }
            snapshot = next
            rebuild(base)
        }
        broadcast()
    }

    /** The host lets the phone [client] take over the player it asked for (D-182). */
    fun approveTakeover(client: String) {
        synchronized(lock) {
            val player = pendingTakeovers.remove(client) ?: return
            if (client in blocked) return
            claims.entries.removeAll { it.value == player }
            claims[client] = player
            rebuild()
        }
        publishClients()
        broadcast()
    }

    /** The host doesn't let [client] take over; its page says so (D-182). */
    fun denyTakeover(client: String) {
        synchronized(lock) {
            val player = pendingTakeovers.remove(client) ?: return
            deniedTakeovers[client] = player
        }
        publishClients()
        broadcast()
    }

    /**
     * The host removes the phone playing [playerId] for the rest of the session (D-182): its claim goes
     * and nothing it sends is accepted any more. With an internet link, "New link" keeps it out for good.
     */
    fun block(playerId: String) {
        synchronized(lock) {
            val clients = claims.filterValues { it == playerId }.keys
            blocked += clients
            if (coHost?.client in clients) coHost = null
            claims.keys.removeAll(clients)
            pendingTakeovers.keys.removeAll(clients)
            rebuild()
        }
        publishClients()
        broadcast()
    }

    /**
     * The host gives the phone playing [playerId] the host controls (co-host, D-189), or takes them back
     * with null. One co-host at a time; the host's own phone keeps every control.
     */
    fun setCoHost(playerId: String?) {
        synchronized(lock) {
            coHost = playerId?.let { id -> claims.entries.firstOrNull { it.value == id }?.key?.let { CoHost(it, id) } }
            rebuild()
        }
        publishClients()
        broadcast()
    }

    /** The host disconnects a guest phone from [playerId] (it goes back to picking a player). */
    fun disconnect(playerId: String) {
        synchronized(lock) {
            claims.entries.removeAll { it.value == playerId }
            rebuild()
        }
        publishClients()
        broadcast()
    }

    fun close() {
        val open = synchronized(lock) { streams.keys.toList() }
        open.forEach { it.close() }
        sender.shutdownNow()
        pinger.shutdownNow()
    }

    fun handle(request: Request): Response {
        val path = request.path
        if (!path.startsWith("/api/")) {
            if (request.method != "GET" && request.method != "HEAD") return Response.text(405, "Method not allowed")
            return static(path)
        }
        // The texts are public, so even a page with an outdated link can say so.
        if (request.method == "GET" && path == "/api/strings") return Response.json(json.encodeToString(STRINGS, host.strings()))
        if (!keyMatches(request.query["k"])) return Response.text(403, "Forbidden")
        return when ("${request.method} $path") {
            "GET /api/check" -> reply(true)
            // The host's wall clock, for internet guests' clock sync (SPEC v4).
            "GET /api/time" -> Response.json("""{"now":${System.currentTimeMillis()}}""")
            "GET /api/events" -> events(request.query["c"])
            "GET /api/photo" -> photo(request.query["p"], request.query["v"])
            "GET /api/cover" -> cover(request.query["s"]?.toLongOrNull())
            "POST /api/claim" -> body(request, ClaimRequest.serializer())?.let(::claim) ?: badRequest()
            "POST /api/release" -> body(request, ClientRequest.serializer())?.let(::release) ?: badRequest()
            "POST /api/action" -> body(request, ActionRequest.serializer())?.let(::action) ?: badRequest()
            else -> Response.text(404, "Not found")
        }
    }

    // ---- Requests ----

    private fun static(path: String): Response {
        val name = path.trimStart('/').ifEmpty { "index.html" }
        if (name.contains("..") || name.contains('\\')) return Response.text(404, "Not found")
        val asset = host.asset(name) ?: return Response.text(404, "Not found")
        return Response.Bytes(200, asset.contentType, asset.bytes, cacheable = name.startsWith("fonts/"))
    }

    /** A player's photo; asked for with its [key] it may be kept, as a new photo has a new key (REVIEW.md P-03; D-270). */
    private fun photo(playerId: String?, key: String?): Response {
        val player = synchronized(lock) { (snapshot.game?.players ?: snapshot.lobby).orEmpty().firstOrNull { it.id == playerId && it.photo } }
        val asset = if (player != null && playerId != null) host.photo(playerId) else null
        if (asset == null) return Response.text(404, "Not found")
        return Response.Bytes(200, asset.contentType, asset.bytes, cacheable = key != null && key == player?.photoKey)
    }

    /** Covers only of songs everyone may see (feedback round 12; D-106). */
    private fun cover(songId: Long?): Response {
        val public = synchronized(lock) { songId != null && songId in publicSongs }
        val asset = if (public && songId != null) host.cover(songId) else null
        return if (asset == null) Response.text(404, "Not found") else Response.Bytes(200, asset.contentType, asset.bytes, cacheable = true)
    }

    private fun events(client: String?): Response {
        if (client == null || !CLIENT_ID.matches(client)) return badRequest()
        return Response.EventStream { stream ->
            synchronized(lock) { streams[stream] = client }
            stream.onClose {
                synchronized(lock) {
                    streams.remove(stream)
                    lastSent.remove(stream)
                }
                publishClients()
            }
            publishClients()
            sender.execute { push(stream) }
        }
    }

    private fun claim(r: ClaimRequest): Response {
        if (!CLIENT_ID.matches(r.client)) return badRequest()
        synchronized(lock) {
            // A running game's players, or those of the game being set up (feedback round 32).
            val ids = snapshot.game?.state?.players?.map { it.id } ?: snapshot.lobby?.map { it.id } ?: return reply(false, host.errorText(HubError.NO_GAME))
            if (r.player !in ids) return reply(false, host.errorText(HubError.PICK_PLAYER))
            if (r.client in blocked) return reply(false, host.errorText(HubError.BLOCKED))
            r.device?.takeIf { it in DeviceKinds.ALL }?.let { devices[r.client] = it }
            deniedTakeovers.remove(r.client)
            val holder = claims.entries.firstOrNull { it.value == r.player }?.key
            if (holder != null && holder != r.client && !r.takeover) return Response.json(json.encodeToString(ActionReply.serializer(), ActionReply(false, taken = true)))
            // The phone playing now is still connected: the host decides (D-182).
            if (holder != null && holder != r.client && holder in streams.values) {
                pendingTakeovers[r.client] = r.player
                return@synchronized
            }
            pendingTakeovers.remove(r.client)
            claims.entries.removeAll { it.value == r.player }
            claims[r.client] = r.player
            rebuild()
        }
        publishClients()
        broadcast()
        if (synchronized(lock) { pendingTakeovers[r.client] == r.player }) {
            return Response.json(json.encodeToString(ActionReply.serializer(), ActionReply(false, pending = true)))
        }
        return reply(true)
    }

    private fun release(r: ClientRequest): Response {
        synchronized(lock) {
            pendingTakeovers.remove(r.client)
            claims.remove(r.client)
            rebuild()
        }
        publishClients()
        broadcast()
        return reply(true)
    }

    private fun action(r: ActionRequest): Response {
        if (r.type !in RemoteProtocol.ACTIONS) return badRequest()
        // Decide under the lock, apply outside it: the host's update comes back through [update].
        if (synchronized(lock) { r.client in blocked }) return reply(false, host.errorText(HubError.BLOCKED))
        if (synchronized(lock) { snapshot.paused }) return reply(false, host.errorText(HubError.PAUSED))
        if (r.type == "togglePlay" || r.type == "replay") return audio(r)
        if (r.type.startsWith("host")) return hostCommand(r)
        if (r.type == "vetoStart" || r.type == "vetoCancel") return picking(r)
        val decision: Pair<GameAction?, String?> = synchronized(lock) {
            val game = snapshot.game ?: return reply(false, host.errorText(HubError.NO_GAME))
            val me = claims[r.client]?.takeIf { id -> game.state.players.any { it.id == id } }
                ?: return reply(false, host.errorText(HubError.PICK_PLAYER))
            // Taps on gaps, guesses and Skip must match what the phone saw; buy doesn't depend on it, and
            // "Don't veto" only on the round (other players' vetoes may come in meanwhile; D-166). So do a
            // hidden guess, which doesn't depend on the others' (D-258), and a Lock in naming its gap (D-259).
            val s = game.state
            val hiddenGuess = r.type == "guess" && s.phase == Phase.GUESSING && s.settings.closestYear.hiddenGuesses
            val lockSlot = if (r.type == "lockIn") r.slot else null
            val byRound = r.type == "decline" || r.type == "dragSelect" || hiddenGuess || lockSlot != null
            if (r.type in VERSIONED && !byRound && r.version != version) return reply(false, host.errorText(HubError.STALE))
            if (byRound && r.round != s.round?.number) return reply(false, host.errorText(HubError.STALE))
            if (lockSlot != null && s.round?.selectedSlot != lockSlot) return reply(false, host.errorText(HubError.STALE))
            val round = s.round
            val active = s.phase != Phase.GAME_OVER && s.activePlayer.id == me
            when (r.type) {
                "select" -> if (s.phase == Phase.PLACING && active && r.slot != null) GameAction.SelectSlot(r.slot) to null else null to null
                // The gap under the finger while dragging, sent as it changes so the host sees the drag
                // (feedback round 41): only the round counts, as each step moves the version on.
                "dragSelect" -> if (s.phase == Phase.PLACING && active && r.slot != null) GameAction.SelectSlot(r.slot) to null else null to null
                "lockIn" -> if (s.phase == Phase.PLACING && active) GameAction.LockIn to null else null to null
                "veto" -> if (s.phase == Phase.VETOING && r.slot != null) GameAction.PlaceVeto(me, r.slot) to null else null to null
                "decline" -> if (s.phase == Phase.VETOING && me in gameView?.vetoEligible.orEmpty()) null to me else null to null
                "guess" -> if (s.phase == Phase.GUESSING && isTurnOf(s, me) && r.year != null) GameAction.SubmitGuess(r.year, me) to null else null to null
                // Timeline: the active player while placing; Closest Year: the first guesser before any guess (feedback round 11).
                "skip" -> {
                    val allowed = (s.phase == Phase.PLACING && active) ||
                        (s.phase == Phase.GUESSING && round?.currentGuesserId == me && round.guesses.isEmpty())
                    if (allowed) GameAction.Skip to null else null to null
                }
                "buy" -> GameAction.BuyCard(me) to null
                else -> return badRequest()
            }
        }
        val (gameAction, decliner) = decision
        return when {
            gameAction != null -> host.dispatch(gameAction)?.let { reply(false, host.rejectText(it)) } ?: reply(true)
            decliner != null -> {
                host.declineVeto(decliner)
                reply(true)
            }
            else -> reply(false, host.errorText(HubError.NOT_YOUR_TURN))
        }
    }

    /** A co-host phone's host control (D-189): only from that phone, on the state it saw. */
    private fun hostCommand(r: ActionRequest): Response {
        val command: HostCommand = synchronized(lock) {
            if (snapshot.game == null) return reply(false, host.errorText(HubError.NO_GAME))
            if (r.client != coHost?.client) return reply(false, host.errorText(HubError.NOT_HOST))
            if (r.type !in UNVERSIONED_HOST && r.version != version) return reply(false, host.errorText(HubError.STALE))
            when (r.type) {
                "hostReveal" -> HostCommand.Reveal
                "hostNext" -> HostCommand.Next
                "hostUndo" -> HostCommand.Undo
                "hostNamed" -> HostCommand.Named(r.player ?: return badRequest(), r.named ?: return badRequest())
                "hostYear" -> HostCommand.CorrectYear(r.year ?: return badRequest())
                "hostAdjust" -> HostCommand.Adjust(r.player ?: return badRequest(), r.delta?.takeIf { it == 1 || it == -1 } ?: return badRequest())
                // seconds in year; 0 means no limit (D-191).
                "hostClip" -> HostCommand.ClipLimit(r.year?.takeIf { it > 0 })
                // seconds in year, 0 included (feedback round 34).
                "hostOffset" -> HostCommand.StartOffset(r.year ?: return badRequest())
                "hostWhole" -> HostCommand.WholeSong
                "hostRemove" -> HostCommand.Remove(r.player ?: return badRequest())
                "hostSeek" -> HostCommand.Seek(r.positionMs?.takeIf { it >= 0 } ?: return badRequest())
                else -> return badRequest()
            }
        }
        return host.hostCommand(command)?.let { reply(false, it) } ?: reply(true)
    }

    /** "Veto" tapped: Reveal waits until the veto is placed or cancelled (feedback round 10). */
    private fun picking(r: ActionRequest): Response {
        val me = synchronized(lock) {
            val game = snapshot.game ?: return reply(false, host.errorText(HubError.NO_GAME))
            val me = claims[r.client]?.takeIf { id -> game.state.players.any { it.id == id } }
                ?: return reply(false, host.errorText(HubError.PICK_PLAYER))
            if (r.type == "vetoStart" && r.round != game.state.round?.number) return reply(false, host.errorText(HubError.STALE))
            if (r.type == "vetoStart" && (game.state.phase != Phase.VETOING || me !in gameView?.vetoEligible.orEmpty())) {
                return reply(false, host.errorText(HubError.NOT_YOUR_TURN))
            }
            me
        }
        host.vetoPicking(me, r.type == "vetoStart")
        return reply(true)
    }

    /** Play and replay are only for the guest whose turn it is (feedback round 8). */
    private fun audio(r: ActionRequest): Response {
        val allowed = synchronized(lock) {
            val game = snapshot.game ?: return reply(false, host.errorText(HubError.NO_GAME))
            val me = claims[r.client]?.takeIf { id -> game.state.players.any { it.id == id } }
                ?: return reply(false, host.errorText(HubError.PICK_PLAYER))
            isTurnOf(game.state, me) || r.client == coHost?.client
        }
        if (!allowed) return reply(false, host.errorText(HubError.NOT_YOUR_TURN))
        if (r.type == "togglePlay") host.togglePlay() else host.replay()
        return reply(true)
    }

    private fun isTurnOf(s: GameState, playerId: String): Boolean = when (s.phase) {
        Phase.PLACING -> s.activePlayer.id == playerId
        // With hidden guesses everyone who hasn't guessed yet (feedback round 36).
        Phase.GUESSING -> if (s.settings.closestYear.hiddenGuesses) playerId in s.round?.waitingFor.orEmpty() else s.round?.currentGuesserId == playerId
        // The player who placed the card, and those who could still veto (feedback rounds 23, 24; D-186, D-188).
        Phase.VETOING -> playerId == s.activePlayer.id || playerId in GameEngine.vetoEligible(s)
        else -> false
    }

    // ---- Views and pushes ----

    /** Rebuilds the shared game view, from [base] when the caller just built it. Call with [lock] held. */
    private fun rebuild(base: GameView? = null) {
        val c = coHost
        if (c != null && (claims[c.client] != c.player || snapshot.game?.state?.removed.orEmpty().any { it.id == c.player })) coHost = null
        gameView = (base ?: snapshot.game?.let { GameViews.build(it, 0, claimedPlayers()) })?.copy(version = version)
        publicSongs = snapshot.game?.state?.let(GameViews::publicSongs).orEmpty()
    }

    /** What moves the version: the view without the veto countdown, the player's progress and who plays remotely. */
    private fun visibleOf(v: GameView) = v.copy(
        vetoWindow = null,
        audio = AudioInfo(),
        players = v.players.map { it.copy(remote = false) },
        removed = v.removed.map { it.copy(remote = false) },
    )

    private fun claimedPlayers(): Set<String> = claims.values.toSet()

    /** Player → the kind of device it is played on, where its page said. Call with [lock] held. */
    private fun deviceKinds(): Map<String, String> = claims.entries.mapNotNull { (client, player) -> devices[client]?.let { player to it } }.toMap()

    /** The view for one phone. Call with [lock] held. */
    private fun viewFor(client: String, internet: Boolean = false): ClientView {
        if (client in blocked) return ClientView(theme = snapshot.theme, game = null, me = null, taken = emptyList(), blocked = true)
        val lobby = snapshot.lobby?.takeIf { snapshot.game == null }
        val inGame = (snapshot.game?.state?.players?.map { it.id } ?: lobby?.map { it.id }).orEmpty().toSet()
        val me = claims[client]?.takeIf { it in inGame }
        return ClientView(
            theme = snapshot.theme,
            game = gameView?.let { GameViews.forViewer(it, me) },
            lobby = lobby?.map { LobbyPlayer(it.id, it.name, it.initials, it.color, it.onColor, it.photo, it.photoKey) },
            me = me,
            left = snapshot.game?.state?.removed.orEmpty().any { it.id == claims[client] },
            taken = claims.filterKeys { it != client }.values.distinct(),
            devices = deviceKinds(),
            paused = snapshot.paused && snapshot.game != null,
            confirmTwice = snapshot.confirmTwice,
            showCovers = snapshot.showCovers,
            tone = claims[client]?.let { me -> snapshot.game?.state?.let { OutcomeTones.forViewer(it, me) } }?.name,
            listen = if (internet && snapshot.paused.not()) snapshot.game?.listen else null,
            pendingPlayer = pendingTakeovers[client],
            deniedPlayer = deniedTakeovers[client],
            coHost = client == coHost?.client,
        )
    }

    private fun broadcast() {
        try {
            sender.execute {
                val open = synchronized(lock) { streams.keys.toList() }
                open.forEach(::push)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closed.
        }
    }

    /** Sends [stream] its view if it changed since the last push. Runs on the sender thread. */
    private fun push(stream: SseStream) {
        val text = synchronized(lock) {
            val client = streams[stream] ?: return
            val text = json.encodeToString(ClientView.serializer(), viewFor(client, stream.internet))
            if (lastSent[stream] == text) return
            lastSent[stream] = text
            text
        }
        stream.send(text, event = "view")
    }

    private fun pingAll() {
        val open = synchronized(lock) { streams.keys.toList() }
        open.forEach { it.ping() }
    }

    private fun publishClients() {
        val clients = synchronized(lock) {
            val connected = streams.values.toSet()
            val now = RemoteClients(
                claims.entries.associate { (client, player) -> player to (client in connected) },
                open = streams.size,
                devices = deviceKinds(),
                takeovers = pendingTakeovers.map { (client, player) -> Takeover(client, player, devices[client]) },
                coHost = coHost?.player,
            )
            if (now == lastClients) return
            lastClients = now
            now
        }
        onClients(clients)
    }

    // ---- Helpers ----

    private fun keyMatches(given: String?): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(), key.toByteArray())

    private fun <T> body(request: Request, serializer: KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, request.bodyText) }.getOrNull()

    private fun reply(ok: Boolean, error: String? = null) = Response.json(json.encodeToString(ActionReply.serializer(), ActionReply(ok, error)))

    private fun badRequest() = Response.json(json.encodeToString(ActionReply.serializer(), ActionReply(false, host.errorText(HubError.BAD_REQUEST))), status = 400)

    companion object {
        private const val PING_SECONDS = 15L
        private val CLIENT_ID = Regex("[A-Za-z0-9_-]{8,64}")
        private val VERSIONED = setOf("select", "lockIn", "veto", "guess", "skip")

        /** Co-host commands that name what they change, so they don't depend on the state the phone saw. */
        private val UNVERSIONED_HOST = setOf("hostAdjust", "hostClip", "hostOffset", "hostWhole", "hostRemove", "hostSeek")
        private val STRINGS = MapSerializer(String.serializer(), String.serializer())

        /** A random 128-bit key, URL-safe (DECISIONS.md D-100). */
        fun newKey(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
