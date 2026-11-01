package com.trackrecord.app.ui

import androidx.compose.ui.input.key.Key
import com.trackrecord.app.ui.components.KeyShortcuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The keyboard's shortcuts (PLATFORMS.md phase 7; feedback round 55). */
class KeyShortcutsTest {
    private val keys = KeyShortcuts()
    private val pressed = mutableListOf<String>()

    @Test
    fun `the newest handler is asked first`() {
        keys.add { pressed += "screen:$it"; it == Key.A }
        keys.add { pressed += "keypad:$it"; it == Key.One }
        assertTrue(keys.press(Key.One))
        assertTrue(keys.press(Key.A))
        assertFalse(keys.press(Key.B))
        assertEquals(listOf("keypad:${Key.One}", "keypad:${Key.A}", "screen:${Key.A}", "keypad:${Key.B}", "screen:${Key.B}"), pressed)
    }

    @Test
    fun `a held key's repeats are swallowed until it is released`() {
        var toggles = 0
        keys.add { (it == Key.Spacebar).also { used -> if (used) toggles++ } }
        repeat(5) { assertTrue(keys.press(Key.Spacebar)) }
        assertEquals(1, toggles)
        assertTrue(keys.release(Key.Spacebar))
        keys.press(Key.Spacebar)
        assertEquals(2, toggles)
    }

    @Test
    fun `Space and Enter belong to a screen with shortcuts, so a clicked button isn't clicked again`() {
        assertFalse(keys.press(Key.Enter), "no screen with shortcuts: the focused button may take it")
        keys.add { false }
        assertTrue(keys.press(Key.Enter))
        assertTrue(keys.press(Key.Spacebar))
        assertFalse(keys.press(Key.Tab), "other keys still move the focus")
    }
}
