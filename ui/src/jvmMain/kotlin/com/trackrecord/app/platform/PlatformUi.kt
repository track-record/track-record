package com.trackrecord.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens ask the platform for (PLATFORMS.md, FilePicker and PlatformActions): pickers, permissions,
 * sharing and photos. Each `remember…` returns the function that opens the picker; its result comes to the callback.
 */
interface PlatformUi {
    /** Picks a music folder; it stays readable until [releaseFolder]. */
    @Composable
    fun rememberFolderPicker(onPicked: (folder: String) -> Unit): () -> Unit

    /** Gives up reading a folder the host removed. */
    fun releaseFolder(folder: String) {}

    /** Picks a song list (CSV) to import; Android opens it in Downloads, where the browser saves Exportify's files. */
    @Composable
    fun rememberListPicker(onPicked: (file: String) -> Unit): () -> Unit

    /** Picks any file to open (a library export). */
    @Composable
    fun rememberFileOpener(onPicked: (file: String) -> Unit): () -> Unit

    /** Picks where to save a file, suggesting the name it is called with. */
    @Composable
    fun rememberFileSaver(onPicked: (file: String) -> Unit): (name: String) -> Unit

    /**
     * Asks for access to the downloads folder (Android's file permission, for Exportify's lists); [after] runs once
     * the host answered. Null where nothing needs asking.
     */
    @Composable
    fun rememberDownloadsAccess(after: () -> Unit): (() -> Unit)?

    /** Opens Exportify (Android: a Custom Tab, so the Spotify login of the browser carries over; D-092). */
    fun openExportify()

    /** Shares [text] with another app; null where there is no share sheet (the link is copied instead). */
    val share: ((text: String) -> Unit)?

    /**
     * The buttons that take or choose a player's photo, with the crop dialog (feedback round 16); [onPhoto] gets the
     * saved photo's path. Null where photos can't be taken (the desktop for now): imported photos still show.
     */
    val photoButtons: (@Composable (onPhoto: (path: String) -> Unit) -> Unit)?
}

/** The platform's [PlatformUi], set by the app around everything it shows. */
val LocalPlatformUi = staticCompositionLocalOf<PlatformUi> { error("no PlatformUi") }

/** The library's preview of one song (song details): a plain player, nothing in the notification shade. */
interface PreviewPlayer {
    val playing: StateFlow<Boolean>

    /** Plays [uri] (a local file or a preview URL) from the start. */
    fun play(uri: String)

    fun pause()

    fun release()
}
