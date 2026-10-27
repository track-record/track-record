package com.trackrecord.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R
import com.trackrecord.app.ui.theme.LocalCardFill
import com.trackrecord.app.ui.theme.LocalPanels

/**
 * Material's card in the theme's panel shade, the guest page's (DESIGN-REVIEW TH-02): a step higher on
 * sheets ([LocalCardFill]). Keeps the M3 name and parameters, like the button wrappers.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.Card(modifier, shape, CardDefaults.cardColors(containerColor = LocalCardFill.current), border = border, content = content)

/**
 * A bottom sheet as on the guest page (DESIGN-REVIEW LY-01): the drag handle, a [title], the content,
 * and Close at the bottom; solid, with cards on it a step lighter.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppSheet(
    title: String,
    onDismiss: () -> Unit,
    // Always fully open: a half-open tall sheet hid its Close button behind the navigation bar (feedback round 43).
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val panels = LocalPanels.current
    // Never up to the status bar: a sheet reaching it made room for it, grew and settled again without end
    // (feedback rounds 37 to 39 and 48). Its body takes what is left (sheetBody()). Measured here, in the
    // activity's window: inside the sheet's own window the status bar reads as 0.
    val window = LocalWindowInfo.current.containerSize.height
    val statusBar = WindowInsets.statusBarsIgnoringVisibility.getTop(LocalDensity.current)
    // The sheet's own space ends above the navigation bar, and its content is padded for it once more.
    val navigationBar = WindowInsets.navigationBarsIgnoringVisibility.getBottom(LocalDensity.current)
    val maxHeight = with(LocalDensity.current) { (window - statusBar - navigationBar).toDp() } - SHEET_TOP_GAP - SHEET_HANDLE
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = panels.solid) {
        CompositionLocalProvider(LocalCardFill provides panels.high) {
            // Clear of the navigation bar even while the game hides it: a sheet's window shows it again.
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                content()
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp).height(SHEET_CLOSE_HEIGHT)) {
                    Text(stringResource(R.string.action_close), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Space a sheet always leaves below the status bar. */
private val SHEET_TOP_GAP = 48.dp

/** The drag handle above a sheet's content, with its padding. */
private val SHEET_HANDLE = 48.dp

/** The height of a sheet's Close button, as on the guest page. */
private val SHEET_CLOSE_HEIGHT = 52.dp
