package com.trackrecord.app.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areNavigationBarsVisible
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.trackrecord.app.ui.theme.LocalPanels
import kotlinx.coroutines.delay

/**
 * Bottom action bar of a screen, in the solid panel shade like the guest page's (DESIGN-REVIEW TH-02);
 * its content stays above the navigation bar whenever that is visible.
 */
@Composable
fun BottomBarSurface(content: @Composable () -> Unit) {
    Surface(color = LocalPanels.current.solid) {
        Box(Modifier.navigationBarsPadding()) { content() }
    }
}

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
fun HideNavigationBar() {
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

/**
 * Centres [content] in the full height and ignores the navigation bar while it doesn't cover the
 * content, so showing the bar doesn't move everything; when it would cover it, the content moves up
 * just enough (feedback round 21).
 */
@Composable
fun CenteredAboveNavigationBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val navBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    Layout(content, modifier) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val centred = (constraints.maxHeight - placeable.height) / 2
            val y = minOf(centred, constraints.maxHeight - navBottom - placeable.height).coerceAtLeast(0)
            placeable.place((constraints.maxWidth - placeable.width) / 2, y)
        }
    }
}

/** Hides the navigation bar right away, e.g. when the window regains focus after a dialog or app switch. */
fun hideNavigationBarNow(activity: Activity) {
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.navigationBars())
}

private const val REHIDE_DELAY_MS = 3_000L
