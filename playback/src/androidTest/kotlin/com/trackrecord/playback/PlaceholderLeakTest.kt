package com.trackrecord.playback

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hard requirement: nothing identifying before reveal. Plays a fully tagged file (title, artist,
 * album, cover, ISRC, MusicBrainz id) and checks what the session, the platform media session
 * and the notification expose.
 */
@RunWith(AndroidJUnit4::class)
class PlaceholderLeakTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var audio: GameAudio

    /** Strings from the file's tags and path that must never appear. */
    private val secrets = listOf("Bohemian", "Queen", "Opera", "queen.mp3", "GBUM71029604", "b1a9c0e9")

    private fun copyAsset(): Uri {
        val file = File(target.cacheDir, "queen.mp3")
        instrumentation.context.assets.open("queen.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun request(uri: Uri, offsetMs: Long = 0, clipMs: Long? = null) = TrackRequest(
        uri = uri.toString(),
        mediaId = "round-7",
        placeholderTitle = "Mystery song",
        placeholderSubtitle = "Round 7",
        startOffsetMs = offsetMs,
        clipLimitMs = clipMs,
        durationMs = 12_000,
        autoPlay = true,
    )

    private suspend fun awaitState(predicate: (AudioState) -> Boolean) = withTimeout(15_000) {
        while (!predicate(audio.state.value)) delay(100)
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.Main) {
        if (!::audio.isInitialized) return@runBlocking
        audio.stop()
        audio.release()
    }

    @Test
    fun session_notification_and_platform_session_show_only_placeholders() = runBlocking {
        val uri = copyAsset()
        withContext(Dispatchers.Main) {
            audio = GameAudio(target)
            audio.load(request(uri))
        }
        awaitState { it.isPlaying }

        val controller = withContext(Dispatchers.Main) { audio.connect() }
        withContext(Dispatchers.Main) {
            val md = controller.mediaMetadata
            assertEquals("Mystery song", md.title.toString())
            assertEquals("Round 7", md.artist.toString())
            assertNull(md.albumTitle)
            assertNull(md.artworkData)
            assertNull(md.artworkUri)
            assertNull(controller.currentMediaItem?.localConfiguration, "URI must not be exposed")
            assertEquals("round-7", controller.currentMediaItem?.mediaId)
            assertTrue(controller.currentTracks.groups.isEmpty(), "tracks carry tag metadata")
        }

        // The platform session feeds the lock screen, system media controls and Bluetooth.
        val sessions = shell("dumpsys media_session")
        assertTrue("Mystery song" in sessions, "placeholder missing from media_session dump")
        for (s in secrets) assertFalse(s in sessions, "media_session leaks '$s'")

        // The media notification (wait for it to be posted).
        var notifications = ""
        withTimeout(10_000) {
            while ("Mystery song" !in notifications) {
                delay(250)
                notifications = shell("dumpsys notification --noredact")
            }
        }
        for (s in secrets) assertFalse(s in notifications, "notification leaks '$s'")
    }

    /** Manual check helper: `-e holdMs 30000` keeps a song playing so the lock screen can be inspected. */
    @Test
    fun hold_for_manual_check() = runBlocking {
        val holdMs = InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull() ?: return@runBlocking
        val uri = copyAsset()
        withContext(Dispatchers.Main) {
            audio = GameAudio(target)
            audio.load(request(uri).copy(clipLimitMs = null))
        }
        awaitState { it.isPlaying }
        delay(holdMs)
    }

    @Test
    fun start_offset_and_clip_limit() = runBlocking {
        val uri = copyAsset()
        withContext(Dispatchers.Main) {
            audio = GameAudio(target)
            audio.load(request(uri, offsetMs = 5_000, clipMs = 3_000))
        }
        awaitState { it.isPlaying && it.clipDurationMs > 0 }
        assertTrue(audio.state.value.clipDurationMs in 2_800..3_200, "clip ${audio.state.value.clipDurationMs}")

        // The clip limit stops playback; replay starts again from the start offset.
        awaitState { it.ended }
        withContext(Dispatchers.Main) { audio.replay() }
        awaitState { it.isPlaying && it.positionMs < 1_500 }
    }
}
