package com.trackrecord.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/**
 * Wraps the real player so the media session — and with it the notification, lock screen and
 * connected devices — only ever sees placeholder text.
 *
 * ExoPlayer merges the file's own tags (title, artist, album, embedded cover) into its metadata, and
 * the media item carries the file URI. This wrapper replaces every playlist entry with a bare item
 * that has only the placeholder title and subtitle the app supplied, no URI, no artwork and no tracks.
 */
@OptIn(UnstableApi::class)
class PlaceholderPlayer(player: Player) : ForwardingSimpleBasePlayer(ConsistentLoading(player)) {
    override fun getState(): State {
        val state = super.getState()
        val playlist = state.playlist.map { item ->
            val safe = placeholderOf(item.mediaItem.mediaMetadata)
            item.buildUpon()
                .setMediaItem(MediaItem.Builder().setMediaId(item.mediaItem.mediaId).setMediaMetadata(safe).build())
                .setMediaMetadata(safe)
                .setTracks(Tracks.EMPTY)
                .setManifest(null)
                .build()
        }
        return state.buildUpon()
            .setPlaylist(playlist)
            .setPlaylistMetadata(MediaMetadata.EMPTY)
            .build()
    }

    companion object {
        /** Keeps only the app-supplied placeholder title and subtitle. */
        fun placeholderOf(requested: MediaMetadata): MediaMetadata =
            MediaMetadata.Builder()
                .setTitle(requested.title)
                .setArtist(requested.artist)
                .setIsPlayable(true)
                .build()
    }
}

/**
 * ExoPlayer can report "loading" for a moment after jumping to the end (a clip shortened below what already
 * played); the session's state may not say so while stopped or finished, or building it throws and the app
 * crashes (feedback round 34).
 */
@OptIn(UnstableApi::class)
private class ConsistentLoading(player: Player) : ForwardingPlayer(player) {
    override fun isLoading(): Boolean = super.isLoading() && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
}
