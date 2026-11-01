package com.trackrecord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.trackrecord.app.AppConfig
import com.trackrecord.app.AppContainer
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.platform.LocalPlatformUi
import com.trackrecord.app.ui.AppNavHost
import com.trackrecord.app.ui.components.ContentMaxWidth
import com.trackrecord.app.ui.components.KeyShortcuts
import com.trackrecord.app.ui.components.LocalKeyShortcuts
import com.trackrecord.app.ui.theme.TrackRecordTheme
import com.trackrecord.data.musicbrainz.NetLog
import com.trackrecord.data.settings.ThemeChoice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.Image
import java.awt.Component
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.io.File
import java.io.RandomAccessFile
import java.util.Properties
import kotlin.system.exitProcess

/**
 * The desktop app (PLATFORMS.md phase 6): the shared screens in one window. [args] are files opened with it (a
 * double-clicked library export), opened as when dropped onto the window.
 */
fun main(args: Array<String>) {
    val release = BuildConfig.RELEASE
    AppConfig.current = AppConfig(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        debug = !release,
        guestPage = BuildConfig.GUEST_PAGE,
        acoustIdKey = BuildConfig.ACOUSTID_KEY,
        musicBrainzContact = BuildConfig.MUSICBRAINZ_CONTACT,
        updateCheck = release,
    )
    val folder = DataFolder.of(release)
    Log.open(folder)
    val files = args.map(::File).filter { it.isFile }.map { it.toURI().toString() }
    val inbox = Inbox(File(folder, "inbox"))
    // One window per data folder: two would share one database (PLATFORMS.md phase 6). Another start hands its files
    // to the running app, which comes to the front (feedback round 55).
    val lock = runCatching { RandomAccessFile(File(folder, "app.lock"), "rw").channel.tryLock() }.getOrNull()
    if (lock == null) {
        inbox.send(files)
        exitProcess(0)
    }
    // Silent unless asked for sound in development runs (the silent-test rule); release builds always play.
    val mute = System.getProperty("trackrecord.mute") == "1"
    NetLog.enabled = !release
    Log.i("Track Record ${BuildConfig.VERSION_NAME}${if (release) "" else " (development)"}, data in $folder${if (mute) ", muted" else ""}")
    val chromium = Chromium(File(folder, "chromium"), debugPort = if (release) 0 else DEBUG_PORT, mute = mute, verbose = !release)
    // The update's installer closes the app as the window's close button does.
    val quit = MutableStateFlow(false)
    val container = AppContainer(DesktopPlatform(folder, chromium, mute, onQuit = { quit.value = true }))
    val placement = WindowPlacement(File(folder, "window.properties"))
    val keys = KeyShortcuts()
    val opened = Channel<List<String>>(Channel.UNLIMITED)
    if (files.isNotEmpty()) opened.trySend(files)
    inbox.watch { opened.trySend(it) }

    application {
        val windowState = rememberWindowState(size = placement.size, position = placement.position)
        val close = {
            placement.save(windowState.size, windowState.position)
            container.remote.stop()
            container.game.audio.release()
            chromium.close()
            exitApplication()
        }
        val quitting by quit.collectAsState()
        LaunchedEffect(quitting) { if (quitting) close() }
        Window(
            onCloseRequest = close,
            state = windowState,
            title = if (release) "Track Record" else "Track Record (development)",
            icon = remember { BitmapPainter(Image.makeFromEncoded(Chromium::class.java.getResourceAsStream("/desktop/icon.png")!!.readBytes()).toComposeImageBitmap()) },
            // The screens' shortcuts get the keys before what has the focus, unless a text field is typed in
            // (PLATFORMS.md phase 7; feedback round 55).
            onPreviewKeyEvent = { !typing() && keys.handle(it) },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT) }
            val platformUi = remember { DesktopPlatformUi(chromium, DesktopPhotos(container.platform.folders.photos)) }
            container.bringToFront = { window.toFront() }
            val settings by container.settings.appSettings.collectAsState(initial = null)
            CompositionLocalProvider(LocalAppContainer provides container, LocalPlatformUi provides platformUi, LocalKeyShortcuts provides keys) {
                val s = settings
                TrackRecordTheme(s?.theme ?: ThemeChoice.LOW_POLY, s?.cornerRadiusDp ?: 4, s?.buttonCornerRadiusDp ?: 4) {
                    // The screens are made for phones: a centred column as wide as the guest page's on a computer
                    // (app.css), with the theme's scene behind the whole window.
                    FileDrop(container, opened, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.fillMaxHeight().widthIn(max = ContentMaxWidth)) { AppNavHost() }
                        }
                    }
                }
            }
        }
    }
    exitProcess(0)
}

/**
 * Whether a text field is typed in: Compose gives the window's component input-method requests only while one has the
 * focus.
 */
private fun typing(): Boolean =
    generateSequence(KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as Component?) { it.parent }.any { it.inputMethodRequests != null }

private const val DEBUG_PORT = 9222

private const val MIN_WIDTH = 800
private const val MIN_HEIGHT = 600

/**
 * Where the desktop app keeps its data (PLATFORMS.md, AppPaths): %APPDATA%\TrackRecord on Windows, Application Support
 * on macOS, XDG's data folder on Linux. Development runs use their own folder, as Android's debug build sits beside the
 * release.
 */
object DataFolder {
    fun of(release: Boolean): File {
        val name = if (release) "TrackRecord" else "TrackRecord-dev"
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val base = when {
            os.startsWith("windows") -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
            os.startsWith("mac") -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
        }
        return File(base, name).apply { mkdirs() }
    }
}

/** The window's size and place, kept for the next start. */
private class WindowPlacement(private val file: File) {
    private val props = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

    val size: DpSize = DpSize(number("width", 1200f).dp, number("height", 850f).dp)
    val position: WindowPosition =
        if (props.containsKey("x")) WindowPosition(number("x", 0f).dp, number("y", 0f).dp) else WindowPosition.PlatformDefault

    private fun number(key: String, default: Float) = props.getProperty(key)?.toFloatOrNull() ?: default

    fun save(size: DpSize, position: WindowPosition) {
        props["width"] = size.width.value.toString()
        props["height"] = size.height.value.toString()
        if (position is WindowPosition.Absolute) {
            props["x"] = position.x.value.toString()
            props["y"] = position.y.value.toString()
        }
        runCatching { file.outputStream().use { props.store(it, "Track Record's window") } }
    }
}
