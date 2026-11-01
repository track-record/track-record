package com.trackrecord.app

import com.trackrecord.app.library.readSongLists
import com.trackrecord.app.settings.LibraryTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Files opened with the app: on Android sent to it (Chrome's "Open" after an Exportify export, a file manager), on
 * the desktop dropped onto the window (PLATFORMS.md phase 7). A library export goes to Settings, which asks whether to
 * import it (PLATFORMS.md section 5); song lists are imported and the library shows the progress. False if none of
 * [files] could be read.
 */
suspend fun AppContainer.openFiles(files: List<String>): Boolean {
    val export = withContext(Dispatchers.IO) { files.firstOrNull { LibraryTransfer.isExport(platform.files, it) } }
    if (export != null) {
        libraryTransfer.offer(export)
        openSettings.trySend(Unit)
        return true
    }
    val read = withContext(Dispatchers.IO) { files.flatMap { readSongLists(platform.files, it) } }
    if (read.isEmpty()) return false
    // Already imported from the downloads folder by the watcher: not twice (feedback round 32).
    val lists = exportify?.notYetImported(read) ?: read
    if (lists.isNotEmpty()) deezerImports.importLists(lists)
    openLibrary.tryEmit(Unit)
    return true
}
