package com.trackrecord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.trackrecord.app.AppConfig
import com.trackrecord.app.AppContainer
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.game.StartResult
import com.trackrecord.app.openFiles
import com.trackrecord.app.platform.LocalPlatformUi
import com.trackrecord.app.platform.PlatformUi
import com.trackrecord.app.settings.TransferState
import com.trackrecord.app.ui.AppNavHost
import com.trackrecord.app.ui.components.KeyShortcuts
import com.trackrecord.app.ui.components.LocalKeyShortcuts
import com.trackrecord.app.ui.theme.TrackRecordTheme
import com.trackrecord.data.library.NioFolderScanner
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.Phase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop app end to end, headless (PLATFORMS.md phases 6 and 7): the shared screens on the desktop's platform,
 * with Chromium from the JBR playing muted. A folder is added through the screens, a game plays a local song and is
 * played with the keyboard, the guest server answers, and files open as when dropped onto the window.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopAppTest {
    private val folder: File = Files.createTempDirectory("trackrecord-desktop").toFile()
    /** The test library, every format (AAC plays as WAV since feedback round 55): the game draws at random. */
    private val testlib: File = File(System.getProperty("trackrecord.testlib"))

    /** Picks the test library as the music folder; nothing else is asked for. */
    private val ui = object : PlatformUi {
        @Composable
        override fun rememberFolderPicker(onPicked: (folder: String) -> Unit): () -> Unit = { onPicked(NioFolderScanner.uriOf(testlib)) }

        @Composable
        override fun rememberListPicker(onPicked: (file: String) -> Unit): () -> Unit = {}

        @Composable
        override fun rememberFileOpener(onPicked: (file: String) -> Unit): () -> Unit = {}

        @Composable
        override fun rememberFileSaver(onPicked: (file: String) -> Unit): (name: String) -> Unit = {}

        @Composable
        override fun rememberDownloadsAccess(after: () -> Unit): (() -> Unit)? = null

        override fun openExportify() = Unit

        override val share: ((String) -> Unit)? = null
        override val photoButtons: (@Composable ((String) -> Unit) -> Unit)? = null
    }

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun `a folder is added, a game plays a local song, and guests get the page`() {
        var done = false
        try {
            checkApp { done = true }
        } catch (e: IllegalStateException) {
            // Closing the test's scene with the navigation's entries trips a lifecycle check in Compose's test kit
            // (not the app's code); only after everything above passed is that ignored.
            if (!done || "to be moved to 'DESTROYED'" !in e.message.orEmpty()) throw e
        }
        assertTrue(done)
    }

    private fun checkApp(onDone: () -> Unit) = runComposeUiTest {
        try {
            body(onDone)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.body(onDone: () -> Unit) {
        AppConfig.current = AppConfig("test", 1, debug = true, guestPage = "https://track-record.github.io/dev/", acoustIdKey = "", musicBrainzContact = "", updateCheck = false)
        val chromium = Chromium(File(folder, "chromium"), debugPort = 0, mute = true, verbose = true)
        val container = AppContainer(DesktopPlatform(folder, chromium, mute = true))
        // No network lookups in a test.
        runBlocking { container.settings.update { it.copy(musicBrainzEnabled = false, libraryInfoSeen = true) } }
        val keys = KeyShortcuts()
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalPlatformUi provides ui, LocalKeyShortcuts provides keys) {
                TrackRecordTheme { AppNavHost() }
            }
        }

        // The library: a folder through the screens, scanned with the desktop's scanner and tag reader.
        // Clicks on the UI thread: the navigation insists on it.
        runOnUiThread { onNodeWithText("Library").performClick() }
        waitForIdle()
        runOnUiThread { onNodeWithText("Add folder").performClick() }
        waitUntil(timeoutMillis = 30_000) { runBlocking { container.library.songs.first().size } == 12 }
        val songs = runBlocking { container.library.songs.first() }
        assertTrue(songs.count { it.playable } >= 8, "songs with a year can be played")

        // A game: its first song plays through Chromium (muted), its position moving on.
        val players = listOf(GamePlayer("a", "Anna", "AN", 0), GamePlayer("b", "Ben", "BE", 1))
        val started = runBlocking { container.game.start(GameMode.TIMELINE, players, GameSettings()) }
        assertTrue(started is StartResult.Started, "started: $started")
        waitUntil(timeoutMillis = 20_000) { container.game.audio.state.value.isPlaying }
        val at = container.game.audio.state.value.positionMs
        Thread.sleep(1500)
        assertTrue(container.game.audio.state.value.positionMs > at, "the song plays on")

        // The keyboard on the game screen, as the window hands it the keys: Space pauses, Down picks the first gap,
        // Enter locks in.
        runOnUiThread { onNodeWithContentDescription("Back").performClick() }
        waitForIdle()
        runOnUiThread { onNodeWithText("Continue game").performClick() }
        waitForIdle()
        runOnUiThread { assertTrue(keys.press(Key.Spacebar), "Space is used") }
        waitUntil(timeoutMillis = 10_000) { !container.game.audio.state.value.isPlaying }
        // Held down, Space doesn't toggle again: its repeats are swallowed until it is let go.
        runOnUiThread { assertTrue(keys.press(Key.Spacebar)) }
        Thread.sleep(500)
        assertTrue(!container.game.audio.state.value.isPlaying, "a repeat doesn't play again")
        runOnUiThread { keys.release(Key.Spacebar) }
        runOnUiThread { assertTrue(keys.press(Key.DirectionDown), "Down is used") }
        waitUntil(timeoutMillis = 5_000) { container.game.session.value?.state?.round?.selectedSlot == 0 }
        // The screen shows the chosen gap before the next key, as it does long before a person presses one.
        waitForIdle()
        runOnUiThread { assertTrue(keys.press(Key.Enter), "Enter is used") }
        waitUntil(timeoutMillis = 5_000) { container.game.session.value?.state?.phase != Phase.PLACING }
        runOnUiThread { assertTrue(!keys.press(Key.A), "other keys stay unused") }
        // A veto with the keyboard (feedback round 56): Ben picks, Down marks the first free gap, Enter places it.
        runOnUiThread { onNodeWithText("Ben", substring = true).performClick() }
        waitForIdle()
        keys.release(Key.DirectionDown)
        keys.release(Key.Enter)
        runOnUiThread { assertTrue(keys.press(Key.DirectionDown), "Down marks a gap") }
        waitForIdle()
        runOnUiThread { assertTrue(keys.press(Key.Enter), "Enter places the veto") }
        waitUntil(timeoutMillis = 5_000) { container.game.session.value?.state?.round?.vetoes.orEmpty().isNotEmpty() }
        val round = container.game.session.value!!.state.round!!
        assertTrue(round.vetoes.single().slot != round.lockedSlot, "a free gap, not the one locked in")

        // Files dropped onto the window: a library export goes to Settings to be offered; a file gone meanwhile can't be read.
        val export = File(folder, "TrackRecord.trackrecord")
        runOnUiThread { container.libraryTransfer.export(export.path) }
        waitUntil(timeoutMillis = 10_000) { container.libraryTransfer.state.value == TransferState.Exported }
        // On the UI thread, as the window hands dropped files over: the app then navigates to Settings.
        assertTrue(runOnUiThread { runBlocking { container.openFiles(listOf(export.toURI().toString())) } })
        waitUntil(timeoutMillis = 5_000) { container.libraryTransfer.state.value is TransferState.Offered }
        container.libraryTransfer.dismiss()
        assertTrue(!runOnUiThread { runBlocking { container.openFiles(listOf(File(folder, "gone.csv").toURI().toString())) } }, "a missing file can't be read")

        // Wi-Fi guests: the server on the LAN address serves the page and its texts.
        val link = container.remote.start()
        assertTrue(link != null, "a LAN address")
        val base = link.substringBefore("/?")
        val strings = get("$base/api/strings", link)
        assertEquals(200, strings.first)
        assertTrue("\"web_connecting\"" in strings.second)
        assertEquals(200, get("$base/app.js", link).first)
        // A Wi-Fi guest's sound (feedback round 59): the song playing, under its token, from a position.
        val uri = assertNotNull(container.game.audio.state.value.uri, "a song is loaded")
        val key = URI(link).query.removePrefix("k=")
        var token: String? = null
        waitUntil(timeoutMillis = 10_000) {
            token = songToken("$base/api/events?c=test-client-1&k=$key", link)
            token != null
        }
        val file = File(URI(uri))
        val from = URI("$base/api/song?k=$key&f=$token").toURL().openConnection() as HttpURLConnection
        from.setRequestProperty("Host", URI(link).authority)
        from.setRequestProperty("Range", "bytes=1000-")
        assertEquals(206, from.responseCode)
        assertTrue(from.contentType.startsWith("audio/"), from.contentType)
        assertTrue(file.readBytes().copyOfRange(1000, file.length().toInt()).contentEquals(from.inputStream.readBytes()), "the file from that position")

        // Popups for guests' devices (feedback round 60), on the game (the dropped export opened Settings): Ben's phone
        // connects, then its connection is lost.
        runOnUiThread { onNodeWithContentDescription("Back").performClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Round", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val claim = URI("$base/api/claim?k=$key").toURL().openConnection() as HttpURLConnection
        claim.requestMethod = "POST"
        claim.doOutput = true
        claim.setRequestProperty("Host", URI(link).authority)
        claim.setRequestProperty("Content-Type", "application/json")
        claim.outputStream.use { it.write("""{"client":"test-client-2","player":"b"}""".toByteArray()) }
        assertEquals(200, claim.responseCode)
        val phone = URI("$base/api/events?c=test-client-2&k=$key").toURL().openConnection() as HttpURLConnection
        phone.setRequestProperty("Host", URI(link).authority)
        thread(isDaemon = true) { runCatching { phone.inputStream.bufferedReader().forEachLine { } } }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Ben connected").fetchSemanticsNodes().isNotEmpty() }
        phone.disconnect()
        // Noticed at the next ping (15 s), told after the grace time (5 s).
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Ben lost the connection").fetchSemanticsNodes().isNotEmpty() }
        container.remote.stop()
        container.game.endGame()
        chromium.close()
        onDone()
    }

    /** The song's token from the first view a guest's event stream sends, once it names one. */
    private fun songToken(events: String, link: String): String? {
        val c = URI(events).toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Host", URI(link).authority)
        c.readTimeout = 3000
        return try {
            c.inputStream.bufferedReader().useLines { lines ->
                lines.first { it.startsWith("data: ") }.let { Regex(""""file":"([^"]+)"""").find(it)?.groupValues?.get(1) }
            }
        } finally {
            c.disconnect()
        }
    }

    /** A GET as a guest's browser sends it (the Host header names the server's own address). */
    private fun get(url: String, link: String): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Host", URI(link).authority)
        val code = c.responseCode
        val body = runCatching { c.inputStream.use { String(it.readBytes()) } }.getOrDefault("")
        return code to body
    }
}
