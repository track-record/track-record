package com.trackrecord.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The keyboard's shortcuts (PLATFORMS.md phase 7): the screens on show register what their keys do, and the desktop's
 * window hands them every key before the focused element, except while a text field is typed in. The newest handler
 * is asked first, so a screen's keys win over the one it covers. Nothing calls [handle] on phones.
 *
 * Compose focuses what the mouse clicked, and Space or Enter would click it again (feedback round 55): while a screen
 * has shortcuts, Space and Enter are its own, used or not.
 */
class KeyShortcuts {
    private val handlers = mutableListOf<(Key) -> Boolean>()

    /** Keys held down since their press was used: their repeats are swallowed, so holding Space doesn't toggle on and on. */
    private val held = mutableSetOf<Key>()

    /** Whether [event] was used: presses without Ctrl, Alt or Meta, which stay the system's, and their release. */
    fun handle(event: KeyEvent): Boolean = when {
        event.type == KeyEventType.KeyUp -> release(event.key)
        event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed -> false
        else -> press(event.key)
    }

    /** Whether a press of [key] was used; a repeat of a key held down is, without asking again. */
    fun press(key: Key): Boolean {
        if (key in held) return true
        val used = handlers.asReversed().any { it(key) } || (handlers.isNotEmpty() && (key == Key.Spacebar || key.isEnter))
        if (used) held += key
        return used
    }

    /** Whether [key] was held since a used press: its release is used too. */
    fun release(key: Key): Boolean = held.remove(key)

    internal fun add(handler: (Key) -> Boolean) {
        handlers += handler
    }

    internal fun remove(handler: (Key) -> Boolean) {
        handlers -= handler
    }
}

/** The app's [KeyShortcuts], set by the desktop app around everything it shows. */
val LocalKeyShortcuts = staticCompositionLocalOf { KeyShortcuts() }

/** [onKey] answers key presses while this is in the composition; it returns whether it used the key. */
@Composable
fun KeyHandler(onKey: (Key) -> Boolean) {
    val shortcuts = LocalKeyShortcuts.current
    val current by rememberUpdatedState(onKey)
    DisposableEffect(shortcuts) {
        val handler: (Key) -> Boolean = { current(it) }
        shortcuts.add(handler)
        onDispose { shortcuts.remove(handler) }
    }
}

/** The digit [this] key types, on the number row or the keypad, or null. */
fun Key.digit(): Char? = DIGITS[this]

/** Enter, on the main keyboard or the keypad. */
val Key.isEnter: Boolean get() = this == Key.Enter || this == Key.NumPadEnter

private val DIGITS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)
