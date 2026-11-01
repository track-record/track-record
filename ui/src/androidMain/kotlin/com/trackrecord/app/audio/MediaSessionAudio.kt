package com.trackrecord.app.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.trackrecord.playback.GamePlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android's [GameAudio]: a handle on [GamePlaybackService] (Media3's session): play/pause, replay from the start offset,
 * clip limit. Call from the main thread.
 */
class MediaSessionAudio(private val context: Context) : GameAudio {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var lastRequest: TrackRequest? = null
    private var fullSong = false
    private val _state = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    suspend fun connect(): MediaController = connectMutex.withLock {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(context, ComponentName(context, GamePlaybackService::class.java))
        val c = MediaController.Builder(context, token).buildAsync().await()
        c.addListener(listener)
        controller = c
        publish(c)
        c
    }

    override suspend fun load(request: TrackRequest) {
        val c = connect()
        lastRequest = request
        fullSong = false
        c.setMediaItem(itemFor(request, clipped = true))
        c.prepare()
        c.playWhenReady = request.autoPlay
        publish(c)
    }

    override suspend fun playFullSong(title: String, artist: String?) {
        val request = lastRequest ?: return
        val c = connect()
        fullSong = true
        c.setMediaItem(itemFor(request, clipped = false, title = title, artist = artist))
        c.prepare()
        c.play()
        publish(c)
    }

    override fun changeClipLimit(clipLimitMs: Long?) {
        val request = lastRequest ?: return
        lastRequest = request.copy(clipLimitMs = clipLimitMs)
        val c = controller ?: return
        if (fullSong || c.mediaItemCount == 0) return
        val position = c.currentPosition.coerceAtLeast(0)
        val playing = c.isPlaying
        val window = ClipPlanner.window(request.startOffsetMs, clipLimitMs, request.durationMs)
        val length = window.endMs?.let { it - window.startMs }
        // Already past the new end: the clip is over, as if it had played to its end (feedback round 34).
        val over = length != null && position >= length
        c.setMediaItem(itemFor(lastRequest!!, clipped = true), if (over) length!! else position)
        c.prepare()
        // Placed at its end and playing, the clip finishes at once: the normal "ended" state, no sound.
        c.playWhenReady = playing || over
        publish(c)
    }

    override fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs.coerceAtLeast(0))
        publish(c)
    }

    /** Before reveal only the placeholders are passed; [title] and [artist] only for the revealed full song. */
    private fun itemFor(request: TrackRequest, clipped: Boolean, title: String? = null, artist: String? = null): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(request.uri))
            .setMediaId(request.mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title ?: request.placeholderTitle)
                    .setArtist(if (title != null) artist else request.placeholderSubtitle)
                    .build(),
            )
        if (clipped) {
            val window = ClipPlanner.window(request.startOffsetMs, request.clipLimitMs, request.durationMs)
            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(window.startMs)
            window.endMs?.let { clipping.setEndPositionMs(it) }
            builder.setClippingConfiguration(clipping.build())
        }
        return builder.build()
    }

    override fun play() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        c.play()
    }

    override fun pause() { controller?.pause() }

    override fun togglePlay() { if (controller?.isPlaying == true) pause() else play() }

    override fun replay() {
        val c = controller ?: return
        c.seekTo(0)
        c.play()
    }

    override fun stop() {
        controller?.run { stop(); clearMediaItems() }
    }

    override fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        scope.cancel()
    }

    private fun publish(p: Player) {
        _state.value = AudioState(
            connected = true,
            loaded = p.mediaItemCount > 0,
            isPlaying = p.isPlaying,
            ended = p.playbackState == Player.STATE_ENDED,
            positionMs = p.currentPosition.coerceAtLeast(0),
            clipDurationMs = p.duration.takeIf { it > 0 } ?: 0,
            error = p.playerError != null,
            clipped = lastRequest?.let { it.startOffsetMs > 0 || it.clipLimitMs != null } ?: false,
            fullSong = fullSong,
            fileStartMs = lastRequest?.takeIf { !fullSong }?.let { ClipPlanner.window(it.startOffsetMs, it.clipLimitMs, it.durationMs).startMs } ?: 0,
            uri = lastRequest?.uri,
        )
        if (p.isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive && controller?.isPlaying == true) {
                    controller?.let { publish(it) }
                    delay(250)
                }
            }
        }
    }
}
