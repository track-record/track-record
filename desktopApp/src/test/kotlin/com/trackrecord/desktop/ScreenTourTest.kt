package com.trackrecord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.trackrecord.app.AppConfig
import com.trackrecord.app.AppContainer
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.platform.LocalPlatformUi
import com.trackrecord.app.platform.PlatformUi
import com.trackrecord.app.ui.AppNavHost
import com.trackrecord.app.ui.theme.TrackRecordTheme
import com.trackrecord.data.library.NioFolderScanner
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.Phase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * A tour of the desktop app's screens for design reviews: each screen as the window shows it (the centred column on
 * the theme's scene), saved as PNG to the folder in -Dtrackrecord.screens. Runs only when that is set:
 * `gradlew :desktopApp:test --tests "*ScreenTourTest" -Dtrackrecord.screens=<folder>`.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenTourTest {
    private val out: File? = System.getProperty("trackrecord.screens")?.takeIf { it.isNotBlank() }?.let(::File)
    private val folder: File = Files.createTempDirectory("trackrecord-tour").toFile()
    private val testlib = File(System.getProperty("trackrecord.testlib"))

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

    @Test
    fun `the screens`() {
        val target = out ?: return
        target.mkdirs()
        try {
            runSkikoComposeUiTest(size = Size(1100f, 1300f), density = Density(1f)) { tour(target) }
        } catch (e: IllegalStateException) {
            // Closing the scene trips a lifecycle check in Compose's test kit (see DesktopAppTest).
            if ("to be moved to 'DESTROYED'" !in e.message.orEmpty()) throw e
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun SkikoComposeUiTest.tour(target: File) {
        AppConfig.current = AppConfig("tour", 1, debug = true, guestPage = "https://track-record.github.io/dev/", acoustIdKey = "", musicBrainzContact = "", updateCheck = false)
        val chromium = Chromium(File(folder, "chromium"), debugPort = 0, mute = true, verbose = false)
        val container = AppContainer(DesktopPlatform(folder, chromium, mute = true))
        runBlocking {
            container.settings.update { it.copy(musicBrainzEnabled = false) }
            container.profiles.save(null, "Anna", "AN", 0, null)
            container.profiles.save(null, "Ben", "BE", 1, null)
        }
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalPlatformUi provides ui) {
                TrackRecordTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.fillMaxHeight().widthIn(max = 560.dp)) { AppNavHost() }
                    }
                }
            }
        }
        var n = 0
        fun shot(name: String) {
            waitForIdle()
            Thread.sleep(600)
            waitForIdle()
            n++
            ImageIO.write(captureToImage().toAwtImage(), "png", File(target, "%02d-%s.png".format(n, name)))
        }
        fun click(text: String) {
            val found = runCatching { waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
            if (found.isFailure) shot("missing-$text")
            runOnUiThread { onAllNodesWithText(text, useUnmergedTree = true).onFirst().performClick() }
            waitForIdle()
        }
        fun back() {
            runOnUiThread { onNodeWithContentDescription("Back").performClick() }
            waitForIdle()
        }
        fun scroll(steps: Int) {
            runOnUiThread { onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(center); scroll(steps.toFloat() * 3) } }
            waitForIdle()
        }

        shot("home")
        click("Library")
        shot("library-info")
        click("Close")
        click("Add folder")
        waitUntil(timeoutMillis = 30_000) { runBlocking { container.library.songs.first().size } == 12 }
        shot("library")
        scroll(10)
        shot("library-scrolled")
        scroll(40)
        shot("library-bottom")
        click("Imagine")
        shot("song")
        back()
        back()
        click("Players")
        shot("players")
        click("Anna")
        shot("player-stats")
        back()
        click("New player")
        shot("player-new")
        back()
        back()
        click("Settings")
        shot("settings")
        scroll(15)
        shot("settings-middle")
        scroll(40)
        shot("settings-bottom")
        back()
        click("New game")
        shot("new-game")
        scroll(40)
        shot("new-game-bottom")
        back()

        val players = listOf(GamePlayer("a", "Anna", "AN", 0), GamePlayer("b", "Ben", "BE", 1))
        runBlocking { container.game.start(GameMode.TIMELINE, players, GameSettings()) }
        click("Continue game")
        shot("timeline-round")
        runOnUiThread { onNodeWithContentDescription("Remote players").performClick() }
        waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithText("Tap to copy the link").assertExists() }.isSuccess }
        shot("remote-players")
        click("Close")
        container.game.dispatch(GameAction.SelectSlot(0))
        container.game.dispatch(GameAction.LockIn)
        container.game.dispatch(GameAction.Reveal)
        waitUntil(timeoutMillis = 5_000) { container.game.session.value?.state?.phase == Phase.REVEALED }
        shot("reveal")
        container.remote.stop()
        container.game.endGame()

        runBlocking { container.game.start(GameMode.CLOSEST_YEAR, players, GameSettings()) }
        waitUntil(timeoutMillis = 5_000) { container.game.session.value?.state?.phase == Phase.GUESSING }
        shot("closest-round")
        container.game.endGame()
        chromium.close()
    }
}
