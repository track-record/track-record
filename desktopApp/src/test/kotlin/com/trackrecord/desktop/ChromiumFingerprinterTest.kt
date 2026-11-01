package com.trackrecord.desktop

import com.trackrecord.data.musicbrainz.Chromaprint
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop's fingerprints (feedback round 55) must be what AcoustID expects: Chromium's decoding and the Kotlin
 * Chromaprint, compared with `fpcalc` 1.6.1 on the same files, as the Android test does (:fingerprint,
 * AudioFingerprinterTest). Decoders and resamplers differ slightly, so the raw values are compared bit by bit: both
 * come within 0.1% of fpcalc's (MP3 0.01%, AAC 0.07%), so more than 2% means something broke.
 */
class ChromiumFingerprinterTest {
    private val folder: File = Files.createTempDirectory("trackrecord-fp").toFile()
    private val assets = File(System.getProperty("trackrecord.fptest"))
    private val chromium = Chromium(File(folder, "chromium"), debugPort = 0, mute = true, verbose = true)
    private val fingerprinter = ChromiumFingerprinter(chromium)

    @AfterTest
    fun cleanUp() {
        chromium.close()
        folder.deleteRecursively()
    }

    @Test
    fun `MP3 and AAC match fpcalc`() = runBlocking {
        for ((song, expected) in listOf("fp_test.mp3" to "fp_test_fpcalc_raw.txt", "fp_test.m4a" to "fp_test_m4a_fpcalc_raw.txt")) {
            val reference = File(assets, expected).readText().trim().split(",").map { it.toLong().toInt() }
            val started = System.nanoTime()
            val samples = assertNotNull(fingerprinter.decode(File(assets, song)), "$song decoded")
            val raw = Chromaprint.raw(samples)
            val ms = (System.nanoTime() - started) / 1_000_000
            // Best alignment within a few frames (decoder delay), then the share of differing bits.
            val best = (-3..3).minOf { shift -> bitErrorRate(raw, reference, shift) }
            System.err.println("FINGERPRINT $song: frames ${raw.size} vs ${reference.size}, bit error rate $best, $ms ms")
            assertTrue(raw.size in reference.size - 5..reference.size + 5, "$song: frames ${raw.size} vs ${reference.size}")
            assertTrue(best < 0.02, "$song: bit error rate $best")
        }
    }

    private fun bitErrorRate(a: IntArray, b: List<Int>, shift: Int): Double {
        var bits = 0
        var total = 0
        for (i in a.indices) {
            val j = i + shift
            if (j !in b.indices) continue
            bits += Integer.bitCount(a[i] xor b[j])
            total += 32
        }
        return if (total == 0) 1.0 else bits.toDouble() / total
    }
}
