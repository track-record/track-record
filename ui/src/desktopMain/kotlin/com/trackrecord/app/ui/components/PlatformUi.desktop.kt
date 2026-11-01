package com.trackrecord.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi

@Composable
actual fun HideNavigationBar() = Unit

@Composable
actual fun KeepScreenOn() = Unit

// Esc is Back: Compose's window hands it to the same handlers as the navigation's (PLATFORMS.md phase 7).
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.compose.ui.backhandler.BackHandler(enabled, onBack)

actual val WindowInsets.Companion.statusBarsAlways: WindowInsets
    @Composable get() = WindowInsets(0, 0, 0, 0)

actual val WindowInsets.Companion.navigationBarsAlways: WindowInsets
    @Composable get() = WindowInsets(0, 0, 0, 0)
