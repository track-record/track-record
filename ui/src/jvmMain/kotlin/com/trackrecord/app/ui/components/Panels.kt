package com.trackrecord.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_close
import com.trackrecord.app.resources.section_collapse
import com.trackrecord.app.resources.section_expand
import com.trackrecord.app.ui.theme.LocalCardFill
import com.trackrecord.app.ui.theme.LocalPanels
import org.jetbrains.compose.resources.stringResource

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
 * A titled panel: the title close to the top edge with a little room below it (feedback round 21). With [onToggle] the
 * title row opens and closes the card's [content]. Every screen's content sits in these (feedback round 58).
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        // The header's tap area reaches the card's edges, so a closed card is one big button (feedback round 43).
        val header = Modifier.fillMaxWidth().then(
            if (onToggle != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) Res.string.section_collapse else Res.string.section_expand), onClick = onToggle) else Modifier,
        )
        Row(header.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onToggle != null) Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

/**
 * Material's list row on whatever panel holds it: transparent, where Material fills it with the surface colour, which
 * covered a translucent panel and left its title looking fainter than its rows (feedback round 58). Keeps the M3 name
 * and parameters, like the button wrappers.
 */
@Composable
fun ListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) = androidx.compose.material3.ListItem(
    headlineContent = headlineContent,
    modifier = modifier,
    overlineContent = overlineContent,
    supportingContent = supportingContent,
    leadingContent = leadingContent,
    trailingContent = trailingContent,
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
)

/**
 * The background of one row of a panel made of a lazy list's items: the panel's corners on its [first] and [last] row,
 * so the rows read as one panel, as a [Card] would show them.
 */
@Composable
fun Modifier.panelRow(first: Boolean, last: Boolean): Modifier {
    val shape = CardDefaults.shape as? CornerBasedShape ?: return background(LocalCardFill.current)
    val corners = when {
        first && last -> shape
        first -> shape.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
        last -> shape.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)
        else -> RectangleShape
    }
    return background(LocalCardFill.current, corners)
}

/** The space between a screen's panels. */
val PanelGap = 12.dp

/** How wide the content gets: a phone's screen, and the centred column of the desktop's window (feedback round 54). */
val ContentMaxWidth = 560.dp

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
    val statusBar = WindowInsets.statusBarsAlways.getTop(LocalDensity.current)
    // The sheet's own space ends above the navigation bar, and its content is padded for it once more.
    val navigationBar = WindowInsets.navigationBarsAlways.getBottom(LocalDensity.current)
    val maxHeight = with(LocalDensity.current) { (window - statusBar - navigationBar).toDp() } - SHEET_TOP_GAP - SHEET_HANDLE
    // No wider than the content beneath it (feedback round 58); Material's own limit is wider.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = ContentMaxWidth,
        containerColor = panels.solid,
        contentWindowInsets = { SheetContentInsets },
    ) {
        CompositionLocalProvider(LocalCardFill provides panels.high) {
            // Clear of the navigation bar even while the game hides it: a sheet's window shows it again.
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight).windowInsetsPadding(WindowInsets.navigationBarsAlways).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                content()
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp).height(SHEET_CLOSE_HEIGHT)) {
                    Text(stringResource(Res.string.action_close), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/**
 * What a sheet's content is padded by: the bottom only. Material's default also pads the top by the status
 * bar less the sheet's distance from the top, so a sheet near the status bar grew as it rose, moved and
 * settled again without end; on Felix's phones even with the height cap (feedback rounds 37 to 39, 48, 50).
 */
private val SheetContentInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

/** Space a sheet always leaves below the status bar. */
private val SHEET_TOP_GAP = 48.dp

/** The drag handle above a sheet's content, with its padding. */
private val SHEET_HANDLE = 48.dp

/** The height of a sheet's Close button, as on the guest page. */
private val SHEET_CLOSE_HEIGHT = 52.dp
