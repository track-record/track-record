package com.trackrecord.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * SPEC v4 (DECISIONS.md D-181): while the internet room streams a local file to guests, it plays that
 * file too and so takes Android's audio focus. The game's player must not pause on losing focus to it,
 * so [sharedWithRoom] turns the player's focus handling off for that time only.
 */
object PlaybackFocus {
    val sharedWithRoom = MutableStateFlow(false)
}
