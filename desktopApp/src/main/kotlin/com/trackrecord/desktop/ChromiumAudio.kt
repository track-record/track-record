package com.trackrecord.desktop

import com.trackrecord.app.audio.AudioState
import com.trackrecord.app.audio.ClipPlanner
import com.trackrecord.app.audio.GameAudio
import com.trackrecord.app.audio.TrackRequest
import com.trackrecord.app.platform.PreviewPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI

/** What player.js reports. */
private data class PageState(val loaded: Boolean, val playing: Boolean, val ended: Boolean, val positionMs: Long, val clipDurationMs: Long, val error: Boolean)

/** One hidden player page (desktop/player.js): commands out, its state back. */
private class PlayerPage(private val chromium: Chromium, name: String, volume: Double, private val onState: (PageState) -> Unit) {
    private val port = PagePort(name) { text -> received(text) }

    init {
        chromium.open("desktop/player.html", port)
        send("volume") { put("v", volume) }
    }

    fun send(type: String, fill: JsonObjectBuilder.() -> Unit = {}) = port.send(
        buildJsonObject {
            put("t", type)
            fill()
        }.toString(),
    )

    /** A local file (file: URI) is served to the page at an address of its own; preview URLs as they are. */
    fun source(uri: String): String = if (uri.startsWith("file:")) chromium.publish(File(URI(uri))) else uri

    private fun received(text: String) {
        val m = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        when (m["t"]?.jsonPrimitive?.content) {
            "state" -> onState(
                PageState(
                    loaded = m.getValue("loaded").jsonPrimitive.boolean,
                    playing = m.getValue("playing").jsonPrimitive.boolean,
                    ended = m.getValue("ended").jsonPrimitive.boolean,
                    positionMs = m.getValue("positionMs").jsonPrimitive.long,
                    clipDurationMs = m.getValue("clipDurationMs").jsonPrimitive.long,
                    error = m.getValue("error").jsonPrimitive.boolean,
                ),
            )
            "log" -> Log.w("player: ${m["text"]?.jsonPrimitive?.content}")
        }
    }
}

/**
 * The desktop's [GameAudio] (PLATFORMS.md phase 6, `ChromiumAudioEngine`): a hidden Chromium page with one `<audio>`
 * element. It plays the round's clip window, as Media3's clipping does on Android, and reports positions within it.
 */
class ChromiumAudio(chromium: Chromium, volume: Double) : GameAudio {
    private val _state = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = _state.asStateFlow()
    private var lastRequest: TrackRequest? = null
    private var fullSong = false
    private val page = PlayerPage(chromium, "game-audio", volume) { s ->
        val request = lastRequest
        _state.value = AudioState(
            connected = true,
            loaded = s.loaded,
            isPlaying = s.playing,
            ended = s.ended,
            positionMs = s.positionMs,
            clipDurationMs = s.clipDurationMs,
            error = s.error,
            clipped = request?.let { it.startOffsetMs > 0 || it.clipLimitMs != null } ?: false,
            fullSong = fullSong,
            fileStartMs = request?.takeIf { !fullSong }?.let { ClipPlanner.window(it.startOffsetMs, it.clipLimitMs, it.durationMs).startMs } ?: 0,
            uri = request?.uri,
        )
    }

    override suspend fun load(request: TrackRequest) {
        lastRequest = request
        fullSong = false
        // Nothing of the previous song counts until the page reports on this one.
        _state.value = AudioState(connected = true, uri = request.uri)
        val window = ClipPlanner.window(request.startOffsetMs, request.clipLimitMs, request.durationMs)
        page.send("load") {
            put("src", page.source(request.uri))
            put("startMs", window.startMs)
            window.endMs?.let { put("endMs", it) } ?: put("endMs", JsonNull)
            put("play", request.autoPlay)
        }
    }

    // Nothing reaches a notification on the desktop, so the title and artist aren't needed here.
    override suspend fun playFullSong(title: String, artist: String?) {
        val request = lastRequest ?: return
        fullSong = true
        page.send("load") {
            put("src", page.source(request.uri))
            put("startMs", 0)
            put("endMs", JsonNull)
            put("play", true)
        }
    }

    override fun changeClipLimit(clipLimitMs: Long?) {
        val request = lastRequest ?: return
        lastRequest = request.copy(clipLimitMs = clipLimitMs)
        if (fullSong || !state.value.loaded) return
        val window = ClipPlanner.window(request.startOffsetMs, clipLimitMs, request.durationMs)
        val now = state.value
        page.send("window") {
            window.endMs?.let { put("endMs", it) } ?: put("endMs", JsonNull)
            put("positionMs", now.positionMs)
            put("play", now.isPlaying)
        }
    }

    override fun seekTo(positionMs: Long) {
        page.send("seek") { put("ms", positionMs) }
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0)) }
    }

    override fun play() = page.send("play")

    override fun pause() = page.send("pause")

    override fun togglePlay() = if (state.value.isPlaying) pause() else play()

    override fun replay() {
        seekTo(0)
        play()
    }

    override fun stop() {
        page.send("stop")
    }

    override fun release() = stop()
}

/** The library's preview on the desktop: another hidden player page, whole songs. */
class ChromiumPreview(chromium: Chromium, volume: Double) : PreviewPlayer {
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val page = PlayerPage(chromium, "preview", volume) { s -> _playing.value = s.playing }

    override fun play(uri: String) = page.send("load") {
        put("src", page.source(uri))
        put("startMs", 0)
        put("endMs", JsonNull)
        put("play", true)
    }

    override fun pause() = page.send("pause")

    override fun release() = page.send("stop")
}
