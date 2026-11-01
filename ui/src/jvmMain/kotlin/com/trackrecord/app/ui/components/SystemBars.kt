package com.trackrecord.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.trackrecord.app.ui.theme.LocalPanels

/**
 * Bottom action bar of a screen, in the solid panel shade like the guest page's (DESIGN-REVIEW TH-02);
 * its content stays above the navigation bar whenever that is visible. The padding is the same on every
 * screen, and its buttons are [BottomBarButtonHeight] tall, so all bars match (feedback round 50).
 */
@Composable
fun BottomBarSurface(content: @Composable () -> Unit) {
    Surface(color = LocalPanels.current.solid) {
        Box(Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp)) { content() }
    }
}

/** Height of the main buttons in a [BottomBarSurface]. */
val BottomBarButtonHeight = 60.dp

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
