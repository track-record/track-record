package com.trackrecord.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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

/** One song to play in a round. Only the placeholder strings ever reach the media session. */
data class TrackRequest(
    val uri: String,
    /** Opaque id, e.g. "round-7"; never derived from the song. */
    val mediaId: String,
    val placeholderTitle: String,
    val placeholderSubtitle: String,
    val startOffsetMs: Long,
    /** `null` = no clip limit. */
    val clipLimitMs: Long?,
    /** Known duration of the file (0 if unknown). */
    val durationMs: Long,
    val autoPlay: Boolean,
)

data class AudioState(
    val connected: Boolean = false,
    val loaded: Boolean = false,
    val isPlaying: Boolean = false,
    val ended: Boolean = false,
    /** Position within the playable clip (0 = start offset). */
    val positionMs: Long = 0,
    /** Length of the playable clip, or 0 if unknown. */
    val clipDurationMs: Long = 0,
    val error: Boolean = false,
    /** The round uses a start offset or clip limit, so "full song" plays something different. */
    val clipped: Boolean = false,
    /** The whole song is loaded (reveal screen), ignoring offset and clip limit. */
    val fullSong: Boolean = false,
    /** Where the clip starts in the file: [positionMs] + this = the position in the file (SPEC v4). */
    val fileStartMs: Long = 0,
    /** What is playing (a content:// file or a preview URL); only for internet guests' audio (SPEC v4). */
    val uri: String? = null,
)

/** The playable window of a song: start offset and optional clip end, in file positions. */
data class ClipWindow(val startMs: Long, val endMs: Long?)

object ClipPlanner {
    /**
     * Start at the offset unless the song is shorter than that (then from the beginning); stop after
     * the clip limit unless the song ends first (DECISIONS.md D-029).
     */
    fun window(startOffsetMs: Long, clipLimitMs: Long?, durationMs: Long): ClipWindow {
        val start = if (durationMs > 0 && startOffsetMs >= durationMs) 0 else startOffsetMs.coerceAtLeast(0)
        val end = clipLimitMs?.let { start + it }?.takeIf { durationMs <= 0 || it < durationMs }
        return ClipWindow(start, end)
    }
}

/**
 * Game-side handle on [GamePlaybackService]: play/pause, replay from the start offset, clip limit.
 * Call from the main thread.
 */
class GameAudio(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var lastRequest: TrackRequest? = null
    private var fullSong = false
    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

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

    suspend fun load(request: TrackRequest) {
        val c = connect()
        lastRequest = request
        fullSong = false
        c.setMediaItem(itemFor(request, clipped = true))
        c.prepare()
        c.playWhenReady = request.autoPlay
        publish(c)
    }

    /**
     * Reveal screen: the whole song from the beginning, without offset or clip limit. The song is
     * revealed by then, so the notification shows its [title] and [artist] (SPEC v3 changes; D-110).
     */
    suspend fun playFullSong(title: String, artist: String?) {
        val request = lastRequest ?: return
        val c = connect()
        fullSong = true
        c.setMediaItem(itemFor(request, clipped = false, title = title, artist = artist))
        c.prepare()
        c.play()
        publish(c)
    }

    /**
     * A new clip length limit for the song loaded now (feedback round 25; D-191): the clip is rebuilt
     * at the same position, playing or not as before. The whole song (reveal) only remembers it.
     */
    fun changeClipLimit(clipLimitMs: Long?) {
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

    /** Jumps to [positionMs] within what is loaded (the clip, or the full song). */
    fun seekTo(positionMs: Long) {
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

    fun play() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        c.play()
    }

    fun pause() { controller?.pause() }

    fun togglePlay() { if (controller?.isPlaying == true) pause() else play() }

    /** Restarts from the start offset (position 0 of the clip) and plays. */
    fun replay() {
        val c = controller ?: return
        c.seekTo(0)
        c.play()
    }

    /** Stops playback and clears the song, e.g. when the game ends. */
    fun stop() {
        controller?.run { stop(); clearMediaItems() }
    }

    fun release() {
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
