package com.trackrecord.app.audio

import kotlinx.coroutines.flow.StateFlow

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
 * The game's player (PLATFORMS.md, the AudioEngine seam): Android's media session service, Chromium on the desktop.
 * Call from the main thread.
 */
interface GameAudio {
    val state: StateFlow<AudioState>

    suspend fun load(request: TrackRequest)

    /**
     * Reveal screen: the whole song from the beginning, without offset or clip limit. The song is revealed by then,
     * so the platform may show its [title] and [artist] (SPEC v3 changes; D-110).
     */
    suspend fun playFullSong(title: String, artist: String?)

    /**
     * A new clip length limit for the song loaded now (feedback round 25; D-191): the clip is rebuilt at the same
     * position, playing or not as before. The whole song (reveal) only remembers it.
     */
    fun changeClipLimit(clipLimitMs: Long?)

    /** Jumps to [positionMs] within what is loaded (the clip, or the full song). */
    fun seekTo(positionMs: Long)

    fun play()

    fun pause()

    fun togglePlay()

    /** Restarts from the start offset (position 0 of the clip) and plays. */
    fun replay()

    /** Stops playback and clears the song, e.g. when the game ends. */
    fun stop()

    fun release()
}
