package com.trackrecord.app.remote

import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import com.trackrecord.app.BuildConfig
import com.trackrecord.app.R
import com.trackrecord.app.game.ActionSource
import com.trackrecord.app.game.GameController
import com.trackrecord.app.game.GameInfo
import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.game.GameSession
import com.trackrecord.app.game.PlayerColors
import com.trackrecord.app.game.SongInfo
import com.trackrecord.app.game.message
import com.trackrecord.app.remote.WebStrings.text
import com.trackrecord.app.ui.components.CoverLoader
import com.trackrecord.data.settings.AppSettings
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.Phase
import com.trackrecord.engine.RejectReason
import com.trackrecord.engine.reveal.RevealStatus
import com.trackrecord.playback.AudioState
import com.trackrecord.remote.Asset
import com.trackrecord.remote.AudioInfo
import com.trackrecord.remote.HostCommand
import com.trackrecord.remote.HostGame
import com.trackrecord.remote.HostSnapshot
import com.trackrecord.remote.HttpServer
import com.trackrecord.remote.HubError
import com.trackrecord.remote.InfoView
import com.trackrecord.remote.ListenInfo
import com.trackrecord.remote.LocalAddress
import com.trackrecord.remote.PlayerInfo
import com.trackrecord.remote.RemoteClients
import com.trackrecord.remote.RemoteHost
import com.trackrecord.remote.RemoteHub
import com.trackrecord.remote.RequestsView
import com.trackrecord.remote.RevealInfo
import com.trackrecord.remote.SongText
import com.trackrecord.remote.ThemeInfo
import com.trackrecord.remote.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Remote play is off, or serves guests on [port] with [key] (SPEC v3). */
sealed interface RemoteState {
    data object Off : RemoteState

    /**
     * Remote play runs: Wi-Fi guests reach the server on [port] (null until a QR code was shown on a
     * Wi-Fi network), internet guests the room behind [internetLink] (null until invited; SPEC v4).
     */
    data class On(
        val port: Int? = null,
        val key: String,
        val clients: RemoteClients = RemoteClients(emptyMap()),
        val internetLink: String? = null,
    ) : RemoteState
}

/**
 * Remote play on the host (SPEC v3; DECISIONS.md D-100 to D-109): starts the guest page's server
 * when a QR code is generated, feeds it the game, and applies the guests' moves through the
 * [GameController] like the host's own taps. Runs until [stop], the app is closed, or no guest page
 * has been open for a while (DECISIONS.md D-165).
 */
class RemotePlay(
    private val context: Context,
    private val game: GameController,
    private val settings: Flow<AppSettings>,
    /** Shared with the game's reveal timer: who plays from a guest phone. */
    private val remotePlayers: MutableStateFlow<Set<String>>,
    /** The app's covers (MusicBrainz, embedded, Deezer), sent to guests as JPEG (feedback round 12). */
    private val covers: CoverLoader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: HttpServer? = null
    private var hub: RemoteHub? = null
    private var feed: Job? = null
    private var idleWatch: Job? = null

    /** Internet guests (SPEC v4): the room in a background WebView, and the tunnel into the hub. */
    private var room: InternetRoom? = null
    private var tunnel: Tunnel? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** The address the server listens on (the one in the QR link). */
    private var boundAddress: String? = null

    /** When a guest page was last open (elapsed realtime), for stopping an unused server. */
    @Volatile private var lastGuestAt = 0L

    private val _state = MutableStateFlow<RemoteState>(RemoteState.Off)
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    /**
     * The guest link for the QR code, starting the server if it isn't running. Null when this phone
     * has no Wi-Fi or hotspot address; the server then stays off.
     */
    @Synchronized
    fun start(): String? {
        val address = LocalAddress.find() ?: return null
        val h = ensureHub()
        // First QR code on this network, or the phone changed networks: listen on this address,
        // keeping the key and the claims.
        if (address != boundAddress) {
            val port = listen(h, address)
            _state.update { (it as? RemoteState.On)?.copy(port = port) ?: it }
        }
        lastGuestAt = SystemClock.elapsedRealtime()
        val on = _state.value as RemoteState.On
        return "http://$address:${on.port}/?k=${on.key}"
    }

    /**
     * The internet invite link, opening the room on the first call (SPEC v4). Nothing connects to the
     * public relays before this.
     */
    @Synchronized
    fun inviteOverInternet(): String {
        val h = ensureHub()
        val open = room
        if (open != null) return open.link
        val newTunnel = Tunnel(h, { peer, message -> room?.send(peer, message) })
        val newRoom = InternetRoom(context, onMessage = newTunnel::onMessage, onLeave = newTunnel::onLeave)
        tunnel = newTunnel
        room = newRoom
        newRoom.open()
        lastGuestAt = SystemClock.elapsedRealtime()
        _state.update { (it as? RemoteState.On)?.copy(internetLink = newRoom.link) ?: it }
        return newRoom.link
    }

    /** "New link": a new room; the old link stops working and internet guests join again. */
    @Synchronized
    fun newInternetLink(): String {
        closeRoom()
        return inviteOverInternet()
    }

    private fun closeRoom() {
        room?.close()
        room = null
        tunnel?.close()
        tunnel = null
        _state.update { (it as? RemoteState.On)?.copy(internetLink = null) ?: it }
    }

    @Synchronized
    fun stop() {
        feed?.cancel()
        feed = null
        idleWatch?.cancel()
        idleWatch = null
        closeRoom()
        hub?.close()
        hub = null
        server?.stop()
        server = null
        boundAddress = null
        holdWifi(false)
        wifiLock = null
        remotePlayers.value = emptySet()
        _state.value = RemoteState.Off
    }

    /** The host lets [client] take over the player it asked for, or not (D-182). */
    fun approveTakeover(client: String) {
        hub?.approveTakeover(client)
    }

    fun denyTakeover(client: String) {
        hub?.denyTakeover(client)
    }

    /** Gives the phone playing [playerId] the host controls, or takes them back with null (D-189). */
    fun setCoHost(playerId: String?) {
        hub?.setCoHost(playerId)
    }

    /** The host removes the phone playing [playerId] for this session (D-182). */
    fun block(playerId: String) {
        hub?.block(playerId)
    }

    /** The host disconnects the guest phone that plays [playerId]. */
    fun disconnect(playerId: String) {
        hub?.disconnect(playerId)
    }

    /** The hub for every guest, Wi-Fi or internet, started with the first of them. */
    private fun ensureHub(): RemoteHub {
        hub?.let { return it }
        val newHub = RemoteHub(Host()) { clients ->
            if (clients.open > 0) lastGuestAt = SystemClock.elapsedRealtime()
            holdWifi(clients.open > 0)
            // A phone that went away (or was disconnected) can't finish its veto: Reveal stops waiting.
            val gone = (_state.value as? RemoteState.On)?.clients?.claimed.orEmpty().keys.filter { clients.claimed[it] != true }
            if (gone.isNotEmpty()) scope.launch(Dispatchers.Main) { gone.forEach { game.setVetoPicking(it, false) } }
            remotePlayers.value = clients.players
            _state.update { (it as? RemoteState.On)?.copy(clients = clients) ?: it }
        }
        hub = newHub
        feed = scope.launch {
            val audio = game.audio.state.map(::audioChange).filterNotNull()
            val sessionOrLobby = combine(game.session, game.lobby) { session, lobby -> session to lobby }
            val onGameAndRequests = combine(game.hostOnGame, game.requests) { onGame, requests -> onGame to requests }
            combine(sessionOrLobby, game.revealStatus, settings, audio, onGameAndRequests) { (session, lobby), reveal, app, player, (onGame, requests) ->
                // While the host sets up a new game, guests see its players, not an earlier game (feedback round 32).
                snapshot(session.takeIf { lobby == null }, reveal, app, player)
                    .copy(
                        paused = !onGame, confirmTwice = app.confirmByTappingTwice, showCovers = app.showCovers, lobby = lobby?.map(::playerInfo),
                        requests = RequestsView(requests.yearsDone, requests.yearsTotal, requests.coversDone, requests.coversTotal, requests.stopped),
                    )
            }
                // The reveal timer ticks 5 times a second, guests see whole seconds: equal snapshots go once (REVIEW.md P-02).
                .distinctUntilChanged()
                .collect {
                    newHub.update(it)
                    // Local files reach internet guests as the room's stream (SPEC v4; D-181).
                    room?.follow(it.game?.listen?.takeIf { _ -> !it.paused }, lastAudioUri)
                }
        }
        lastGuestAt = SystemClock.elapsedRealtime()
        idleWatch = scope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                val running = game.session.value?.state?.phase.let { it != null && it != Phase.GAME_OVER }
                val idleFor = SystemClock.elapsedRealtime() - lastGuestAt
                val open = (_state.value as? RemoteState.On)?.clients?.open ?: 0
                if (open == 0 && idleFor >= if (running) IDLE_IN_GAME_MS else IDLE_MS) {
                    stop()
                    break
                }
            }
        }
        _state.value = RemoteState.On(key = newHub.key)
        return newHub
    }

    /**
     * (Re)starts the server on the Wi-Fi or hotspot [address] only, never on mobile data or a VPN
     * (DECISIONS.md D-165); debug builds also listen on loopback, for adb port forwarding.
     */
    private fun listen(hub: RemoteHub, address: String): Int {
        server?.stop()
        val addresses = listOf(InetAddress.getByName(address)) + if (BuildConfig.DEBUG) listOf(InetAddress.getLoopbackAddress()) else emptyList()
        val newServer = HttpServer(handler = hub::handle)
        val port = newServer.start(PORT, addresses)
        server = newServer
        boundAddress = address
        return port
    }

    /** Keeps Wi-Fi responsive while a guest page is open, and only then (DECISIONS.md D-109, D-165). */
    @Synchronized
    private fun holdWifi(hold: Boolean) {
        val lock = wifiLock ?: if (!hold) return else createWifiLock()?.also { wifiLock = it } ?: return
        runCatching { if (hold && !lock.isHeld) lock.acquire() else if (!hold && lock.isHeld) lock.release() }
    }

    private fun createWifiLock(): WifiManager.WifiLock? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        return runCatching { wifi.createWifiLock(mode, "TrackRecord:remote").apply { setReferenceCounted(false) } }.getOrNull()
    }

    // ---- What the guests see ----

    private var lastAudio: AudioInfo? = null
    private var lastAudioAt = 0L
    @Volatile private var lastAudioUri: String? = null

    /** The player as guests see it, and what internet guests play themselves (SPEC v4). */
    private data class AudioSample(val info: AudioInfo, val listen: ListenInfo?)

    /**
     * The player for the guests' progress bars, only when it changed in a way the page can't work out
     * by itself: play/pause, a new clip, or a jump (seek, replay). Null otherwise (feedback round 11).
     */
    private fun audioChange(a: AudioState): AudioSample? {
        val now = SystemClock.elapsedRealtime()
        val preview = a.uri?.let { it.startsWith("https://") || it.startsWith("http://") } == true
        val info = AudioInfo(a.loaded, a.isPlaying, if (a.ended) a.clipDurationMs else a.positionMs, a.clipDurationMs, a.ended, a.error, preview, a.clipped && !a.fullSong)
        val last = lastAudio
        val expected = last?.let { if (it.playing) it.positionMs + (now - lastAudioAt) else it.positionMs }
        val changed = last == null || last.loaded != info.loaded || last.playing != info.playing ||
            last.ended != info.ended || last.error != info.error || last.whole != info.whole ||
            last.durationMs != info.durationMs || abs(info.positionMs - (expected ?: 0)) > JUMP_MS || a.uri != lastAudioUri
        if (!changed) return null
        lastAudio = info
        lastAudioAt = now
        lastAudioUri = a.uri
        return AudioSample(info, listenOf(a, info))
    }

    /**
     * What internet guests play (SPEC v4; D-181): a Deezer preview themselves, from its URL, or else
     * the room's stream of the local file; at the file position, as of the host's wall clock.
     */
    private fun listenOf(a: AudioState, info: AudioInfo): ListenInfo? {
        val uri = a.uri ?: return null
        if (!a.loaded) return null
        val preview = uri.startsWith("https://") || uri.startsWith("http://")
        return ListenInfo(
            kind = if (preview) "preview" else "stream",
            url = uri.takeIf { preview },
            playing = a.isPlaying,
            positionMs = a.fileStartMs + info.positionMs,
            atMs = System.currentTimeMillis(),
        )
    }

    private var infoFor: Pair<Any, InfoView>? = null

    /** The info sheet (feedback round 36), worded again only when what it shows changes. */
    private fun info(s: GameSession): InfoView {
        val key = listOf(s.state.mode, s.state.settings, s.state.seats, s.playlistNames)
        infoFor?.takeIf { it.first == key }?.let { return it.second }
        return GameInfo.of(context, s).also { infoFor = key to it }
    }

    private var songsFor: Map<Long, SongInfo>? = null
    private var songTexts: Map<Long, SongText> = emptyMap()

    private fun snapshot(session: GameSession?, reveal: RevealStatus?, app: AppSettings, audio: AudioSample): HostSnapshot {
        val theme = ThemeInfo(app.theme.name, app.cornerRadiusDp, app.buttonCornerRadiusDp, WebTheme.palettes)
        val game = session?.let { s ->
            HostGame(
                state = s.state,
                players = s.players.map(::playerInfo),
                songs = texts(s.songs),
                reveal = reveal?.let { r -> RevealInfo(r.waitingFor, r.declined.toList(), seconds(r.lockedForMs), r.autoRevealInMs?.let(::seconds), r.picking) },
                audio = audio.info,
                listen = audio.listen,
                info = info(s),
            )
        }
        return HostSnapshot(game, theme)
    }

    /** Photo files found: a photo is never changed in place (ProfilePhotos), so one found stays (REVIEW.md P-02). */
    private val photoFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun playerInfo(p: GamePlayer): PlayerInfo {
        val path = p.photoPath
        val photo = path != null && (path in photoFiles || File(path).isFile.also { if (it) photoFiles += path })
        return PlayerInfo(
            p.id, p.name, p.initials, hex(PlayerColors.color(p.colorIndex)), hex(PlayerColors.onColor(p.colorIndex)),
            photo = photo,
            // The file's random name: a new photo, a new key (D-270).
            photoKey = if (photo) path?.let { File(it).nameWithoutExtension.take(PHOTO_KEY_CHARS) } else null,
        )
    }

    /** Title and artist per song, rebuilt only when the game's song list changes. */
    private fun texts(songs: Map<Long, SongInfo>): Map<Long, SongText> {
        if (songs !== songsFor) {
            songsFor = songs
            songTexts = songs.mapValues { (_, s) -> SongText(s.title, s.artist, s.deezerUrl, s.album, s.baseYear, s.musicBrainzYear) }
        }
        return songTexts
    }

    private fun seconds(ms: Long) = ((ms + 999) / 1000).toInt()

    private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

    private inner class Host : RemoteHost {
        private val strings by lazy { WebStrings.build(context) }

        override fun dispatch(action: GameAction): RejectReason? =
            runBlocking(Dispatchers.Main) { game.tryDispatch(action, ActionSource.REMOTE) }

        override fun declineVeto(playerId: String) = runBlocking(Dispatchers.Main) { game.declineVeto(playerId) }

        override fun vetoPicking(playerId: String, picking: Boolean) = runBlocking(Dispatchers.Main) { game.setVetoPicking(playerId, picking) }

        override fun togglePlay() = runBlocking(Dispatchers.Main) { game.audio.togglePlay() }

        override fun replay() = runBlocking(Dispatchers.Main) { game.audio.replay() }

        /** A co-host's command (D-189), checked like the host's own taps: Reveal waits for the veto window. */
        override fun hostCommand(command: HostCommand): String? = runBlocking(Dispatchers.Main) {
            val state = game.session.value?.state ?: return@runBlocking context.getString(HubError.NO_GAME.text)
            val timeline = state.mode == GameMode.TIMELINE
            val rejected = when (command) {
                HostCommand.Reveal -> {
                    if (game.revealStatus.value?.locked == true) return@runBlocking context.getString(R.string.web_error_reveal_locked)
                    game.tryDispatch(GameAction.Reveal, ActionSource.REMOTE)
                }
                HostCommand.Next -> game.tryDispatch(GameAction.Next, ActionSource.REMOTE)
                HostCommand.Undo -> game.tryDispatch(if (timeline) GameAction.UndoVeto else GameAction.UndoGuess, ActionSource.REMOTE)
                is HostCommand.Named -> game.tryDispatch(GameAction.SetNamedBy(command.playerId, command.named), ActionSource.REMOTE)
                is HostCommand.CorrectYear -> game.correctYear(command.year, ActionSource.REMOTE)
                is HostCommand.StartOffset -> game.tryDispatch(GameAction.SetStartOffset(command.seconds), ActionSource.REMOTE)
                is HostCommand.ClipLimit -> game.tryDispatch(GameAction.SetClipLimit(command.seconds), ActionSource.REMOTE)
                HostCommand.WholeSong -> {
                    game.playFullSong()
                    null
                }
                is HostCommand.Seek -> {
                    // Only on the reveal, where the host's bar can be dragged too.
                    if (state.phase != Phase.REVEALED) return@runBlocking context.getString(HubError.NOT_YOUR_TURN.text)
                    game.audio.seekTo(command.positionMs)
                    null
                }
                is HostCommand.Remove -> game.tryDispatch(GameAction.RemovePlayer(command.playerId), ActionSource.REMOTE)
                HostCommand.StopLookups -> {
                    game.stopRequests()
                    null
                }
                is HostCommand.Adjust -> game.tryDispatch(
                    if (timeline) GameAction.AdjustVetoes(command.playerId, command.delta) else GameAction.AdjustSkips(command.playerId, command.delta),
                    ActionSource.REMOTE,
                )
            }
            rejected?.let { context.getString(it.message) }
        }

        override fun rejectText(reason: RejectReason): String = context.getString(reason.message)

        override fun errorText(error: HubError): String = context.getString(error.text)

        override fun strings(): Map<String, String> = strings

        override fun asset(path: String): Asset? {
            val type = CONTENT_TYPES[path.substringAfterLast('.', "")] ?: return null
            return runCatching { context.assets.open("$ASSET_DIR/$path").use { Asset(type, it.readBytes()) } }.getOrNull()
        }

        /** Recently sent covers, so re-renders on the guest phones don't re-encode them. */
        private val coverCache = object : LinkedHashMap<Long, Asset>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Asset>?) = size > 48
        }

        override fun cover(songId: Long): Asset? {
            synchronized(coverCache) { coverCache[songId]?.let { return it } }
            val image = runBlocking(Dispatchers.IO) { covers.load(songId) } ?: return null
            val bytes = ByteArrayOutputStream().use { out ->
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
            return Asset("image/jpeg", bytes).also { synchronized(coverCache) { coverCache[songId] = it } }
        }

        /** Recently sent photos by file, read from disk once (REVIEW.md P-03); a photo file never changes. */
        private val photoCache = object : LinkedHashMap<String, Asset>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Asset>?) = size > 24
        }

        override fun photo(playerId: String): Asset? {
            val players = game.lobby.value ?: game.session.value?.players.orEmpty()
            val path = players.firstOrNull { it.id == playerId }?.photoPath ?: return null
            synchronized(photoCache) { photoCache[path]?.let { return it } }
            val file = File(path).takeIf { it.isFile } ?: return null
            val asset = Asset(if (file.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg", file.readBytes())
            return asset.also { synchronized(photoCache) { photoCache[path] = it } }
        }
    }

    private companion object {
        const val PORT = 8080

        /** Enough of a photo's random file name to tell photos apart. */
        const val PHOTO_KEY_CHARS = 12

        /** Without any guest page open, the server stops after this long, or [IDLE_IN_GAME_MS] while a game runs. */
        const val IDLE_MS = 10 * 60_000L
        const val IDLE_IN_GAME_MS = 30 * 60_000L
        const val IDLE_CHECK_MS = 30_000L

        /** A position this far off what the page expects is a seek or replay. */
        const val JUMP_MS = 1500L
        const val ASSET_DIR = "remote"
        val CONTENT_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "woff2" to "font/woff2",
            "txt" to "text/plain; charset=utf-8",
        )
    }
}
