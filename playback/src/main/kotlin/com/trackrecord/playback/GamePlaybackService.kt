package com.trackrecord.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Plays game songs in the background. The session only ever exposes [PlaceholderPlayer]'s sanitized
 * view, so the notification and lock screen show "Mystery song" / "Round 7", the app glyph and no artwork.
 */
@OptIn(UnstableApi::class)
class GamePlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

    override fun onCreate() {
        super.onCreate()
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(ManualResumeOnFocusLoss(exo))
        // While the internet room plays the same file for its guests, its focus mustn't pause this player.
        scope.launch { PlaybackFocus.sharedWithRoom.collect { shared -> exo.setAudioAttributes(attributes, !shared) } }

        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val builder = MediaSession.Builder(this, PlaceholderPlayer(exo)).setId(SESSION_ID)
        if (launch != null) {
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        }
        session = builder.build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification_note) },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The game is over when the app is swiped away; don't keep a notification around.
        session?.player?.let { it.stop(); it.clearMediaItems() }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * ExoPlayer resumes by itself when a transient focus loss (e.g. a call) ends. The spec wants losing
     * audio focus to pause with manual resume, so a transient loss becomes a real pause.
     */
    private class ManualResumeOnFocusLoss(private val player: Player) : Player.Listener {
        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            if (reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) player.pause()
        }
    }

    companion object {
        const val SESSION_ID = "trackrecord-game"
    }
}
