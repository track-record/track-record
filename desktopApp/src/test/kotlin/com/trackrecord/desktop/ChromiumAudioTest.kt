package com.trackrecord.desktop

import com.trackrecord.app.audio.TrackRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which of the test library's formats the desktop's player (Chromium, muted) plays, half a second of each. */
class ChromiumAudioTest {
    @Test
    fun `the formats of the test library`() {
        val folder = Files.createTempDirectory("trackrecord-audio").toFile()
        val chromium = Chromium(File(folder, "chromium"), debugPort = 0, mute = true, verbose = true)
        val audio = ChromiumAudio(chromium, volume = 0.0)
        val testlib = File(System.getProperty("trackrecord.testlib"))
        val results = testlib.listFiles().orEmpty().filter { it.isFile }.groupBy { it.extension.lowercase() }.mapValues { (_, files) ->
            val file = files.first()
            runBlocking { audio.load(TrackRequest(file.toURI().toString(), "test", "", "", 0, null, 0, autoPlay = true)) }
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 8000 && audio.state.value.positionMs < 500 && !audio.state.value.error) Thread.sleep(100)
            // Played half a second of it.
            audio.state.value.positionMs >= 500
        }
        System.err.println("FORMATS $results")
        audio.stop()
        chromium.close()
        folder.deleteRecursively()
        // JBR's Chromium has no licensed codecs: AAC (m4a) plays as WAV, decoded by AacToWav (feedback round 55).
        assertEquals(mapOf("flac" to true, "m4a" to true, "mp3" to true, "ogg" to true, "opus" to true), results.toSortedMap())
    }
}
