package com.trackrecord.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

// What the screens do differently on each platform (PLATFORMS.md, PlatformActions): Android's system bars, back
// gesture and screen timeout; nothing or the keyboard on the desktop.

/** Hides the navigation bar while this is in the composition (the game screens; feedback round 31). */
@Composable
expect fun HideNavigationBar()

/** Keeps the screen on while the calling composable is shown (game screens). */
@Composable
expect fun KeepScreenOn()

/** Handles the system back (Android's gesture or button) while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** The status bar's height even while it is hidden (Android); none on the desktop. */
expect val WindowInsets.Companion.statusBarsAlways: WindowInsets
    @Composable get

/** The navigation bar's height even while it is hidden (Android); none on the desktop. */
expect val WindowInsets.Companion.navigationBarsAlways: WindowInsets
    @Composable get
