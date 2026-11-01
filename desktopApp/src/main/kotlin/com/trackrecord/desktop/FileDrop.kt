package com.trackrecord.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trackrecord.app.AppContainer
import com.trackrecord.app.openFiles
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_close
import com.trackrecord.app.resources.drop_hint
import com.trackrecord.app.resources.list_failed_unreadable
import com.trackrecord.app.ui.components.TextButton
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Files dropped onto the window open as on Android when they are sent to the app (PLATFORMS.md phase 7): song lists
 * (CSV, text or ZIP) are imported, and a library export goes to Settings. While files are dragged over, a hint shows.
 * Files [opened] with the app (a double-clicked export; feedback round 55) open the same way, the window brought to the
 * front; an empty list only brings it to the front.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FileDrop(container: AppContainer, opened: ReceiveChannel<List<String>>, modifier: Modifier, content: @Composable () -> Unit) {
    var over by remember { mutableStateOf(false) }
    var unreadable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Only what the hint names: any other file would be read as a song list.
    suspend fun open(files: List<String>) {
        val known = files.filter { it.substringAfterLast('.').lowercase() in EXTENSIONS }
        unreadable = known.isEmpty() || !container.openFiles(known)
    }
    LaunchedEffect(opened) {
        for (files in opened) {
            container.bringToFront()
            if (files.isNotEmpty()) open(files)
        }
    }
    val target = remember(container) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                over = true
            }

            override fun onExited(event: DragAndDropEvent) {
                over = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                over = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                over = false
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                if (files.isEmpty()) return false
                scope.launch { open(files) }
                return true
            }
        }
    }
    Box(modifier.dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = target)) {
        content()
        if (over) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)), contentAlignment = Alignment.Center) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 420.dp)) {
                    Text(
                        stringResource(Res.string.drop_hint),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
    if (unreadable) {
        AlertDialog(
            onDismissRequest = { unreadable = false },
            text = { Text(stringResource(Res.string.list_failed_unreadable)) },
            confirmButton = { TextButton(onClick = { unreadable = false }) { Text(stringResource(Res.string.action_close)) } },
        )
    }
}

private const val SCRIM_ALPHA = 0.5f

/** Song lists (Exportify's CSV, plain text, a ZIP of CSVs) and library exports. */
private val EXTENSIONS = setOf("csv", "txt", "zip", "trackrecord")
