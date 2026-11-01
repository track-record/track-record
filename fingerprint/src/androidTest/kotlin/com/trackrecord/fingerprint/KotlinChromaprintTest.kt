package com.trackrecord.fingerprint

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.trackrecord.data.musicbrainz.Chromaprint as KotlinChromaprint

/**
 * The desktop's Kotlin port of Chromaprint (data, feedback round 55) against this native library on the same audio:
 * the same raw values but for the odd bit (the FFTs round differently), and the same compressed fingerprint, character
 * for character, from the same raw values.
 */
@RunWith(AndroidJUnit4::class)
class KotlinChromaprintTest {
    /** 40 s of chords changing twice a second, with a little noise, at Chromaprint's rate. */
    private val samples: ShortArray = run {
        val rate = KotlinChromaprint.SAMPLE_RATE
        val random = Random(55)
        var notes = IntArray(3)
        ShortArray(rate * 40) { i ->
            if (i % (rate / 2) == 0) notes = IntArray(3) { 40 + random.nextInt(36) }
            val t = i.toDouble() / rate
            val tone = notes.sumOf { n -> sin(2 * PI * 440.0 * 2.0.pow((n - 69) / 12.0) * t) }
            (tone * 6000 + random.nextInt(-300, 300)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun native(): Pair<String, IntArray> {
        val ctx = Chromaprint.nativeNew()
        try {
            assertTrue(Chromaprint.nativeStart(ctx, KotlinChromaprint.SAMPLE_RATE, 1))
            assertTrue(Chromaprint.nativeFeed(ctx, samples, samples.size))
            val encoded = assertNotNull(Chromaprint.nativeFinish(ctx))
            return encoded to assertNotNull(Chromaprint.decode(encoded))
        } finally {
            Chromaprint.nativeFree(ctx)
        }
    }

    @Test
    fun rawValuesMatchTheNativeLibrary() {
        val (_, expected) = native()
        val raw = KotlinChromaprint.raw(samples)
        assertEquals(expected.size, raw.size, "frames")
        val differing = raw.indices.sumOf { Integer.bitCount(raw[it] xor expected[it]) }
        val rate = differing.toDouble() / (raw.size * 32)
        android.util.Log.i("FingerprintTest", "Kotlin port: ${raw.size} frames, bit error rate $rate")
        assertTrue(rate < 0.005, "bit error rate $rate")
    }

    @Test
    fun compressionIsTheNativeLibrarys() {
        val (encoded, raw) = native()
        assertEquals(encoded, KotlinChromaprint.encode(raw))
        val port = KotlinChromaprint.raw(samples)
        assertContentEquals(port, Chromaprint.decode(KotlinChromaprint.encode(port)))
    }
}
