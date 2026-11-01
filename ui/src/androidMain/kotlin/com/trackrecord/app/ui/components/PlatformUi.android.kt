package com.trackrecord.app.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areNavigationBarsVisible
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/** An activity that hides its navigation bar while a game is on screen (feedback round 31). */
interface NavigationBarHost {
    var hideNavigationBar: Boolean
}

/**
 * Hides the navigation bar while this is in the composition: on the game screens only; the menus keep
 * it (feedback round 31; DECISIONS.md D-053). A swipe from the edge brings it back; while it shows, insets
 * move the UI above it, and it hides again after a few seconds (not while the keyboard is open).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun HideNavigationBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context as? Activity
        (activity as? NavigationBarHost)?.hideNavigationBar = true
        activity?.let(::hideNavigationBarNow)
        onDispose {
            (activity as? NavigationBarHost)?.hideNavigationBar = false
            activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.navigationBars()) }
        }
    }
    val navVisible = WindowInsets.areNavigationBarsVisible
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(navVisible, imeVisible) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        if (!navVisible || imeVisible) return@LaunchedEffect
        delay(REHIDE_DELAY_MS)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }
}

/** Hides the navigation bar right away, e.g. when the window regains focus after a dialog or app switch. */
fun hideNavigationBarNow(activity: Activity) {
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.navigationBars())
}

private const val REHIDE_DELAY_MS = 3_000L

@Composable
actual fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

@OptIn(ExperimentalLayoutApi::class)
actual val WindowInsets.Companion.statusBarsAlways: WindowInsets
    @Composable get() = WindowInsets.statusBarsIgnoringVisibility

@OptIn(ExperimentalLayoutApi::class)
actual val WindowInsets.Companion.navigationBarsAlways: WindowInsets
    @Composable get() = WindowInsets.navigationBarsIgnoringVisibility
