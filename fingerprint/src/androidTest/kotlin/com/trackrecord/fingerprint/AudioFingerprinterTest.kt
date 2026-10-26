package com.trackrecord.fingerprint

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The on-device fingerprint must be what AcoustID expects: compared with `fpcalc` 1.6.1 (the official
 * tool, FFmpeg decoder) on the same file. Decoders differ slightly, so the raw values are compared
 * bit by bit rather than as strings.
 */
@RunWith(AndroidJUnit4::class)
class AudioFingerprinterTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** MP3: decoded natively with minimp3. */
    @Test
    fun mp3MatchesFpcalc() = check("fp_test.mp3", "fp_test_fpcalc_raw.txt")

    /** Other formats: decoded with the platform decoder. */
    @Test
    fun m4aMatchesFpcalc() = check("fp_test.m4a", "fp_test_m4a_fpcalc_raw.txt")

    private fun check(asset: String, expectedAsset: String) = runBlocking {
        val file = File(targetContext.cacheDir, asset)
        testContext.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        val expected = testContext.assets.open(expectedAsset).bufferedReader().readText()
            .trim().split(",").map { it.toLong().toInt() }

        val started = System.nanoTime()
        val fingerprint = AudioFingerprinter(targetContext).fingerprint(Uri.fromFile(file))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertNotNull(fingerprint, "fingerprint computed")
        val raw = assertNotNull(Chromaprint.decode(fingerprint))

        // Best alignment within a few frames (decoder delay), then the share of differing bits.
        val best = (-3..3).minOf { shift -> bitErrorRate(raw, expected, shift) }
        android.util.Log.i("FingerprintTest", "$asset: frames ${raw.size} vs ${expected.size}, bit error rate $best, $ms ms")
        assertTrue(raw.size in expected.size - 5..expected.size + 5, "frames ${raw.size} vs ${expected.size}")
        assertTrue(best < 0.10, "bit error rate $best")
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
