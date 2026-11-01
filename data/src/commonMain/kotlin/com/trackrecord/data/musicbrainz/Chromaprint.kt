package com.trackrecord.data.musicbrainz

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Chromaprint's fingerprint, algorithm 2 (the default, which AcoustID expects), ported from the C++ sources vendored in
 * :fingerprint (Chromaprint 1.6.1, LGPL 2.1) for the desktop, where no native library is built (feedback round 55).
 * Android keeps the native library.
 *
 * It takes audio already at [SAMPLE_RATE], mono: the rate Chromaprint works at, so the decoder resamples (the C++
 * code's own resampler isn't ported). The steps are Chromaprint's: frames of 4096 samples every 1365 with a Hamming
 * window, their power spectrum folded into 12 chroma bands, smoothed over 5 frames and normalised, and 16 classifiers
 * over that image giving 2 bits each per frame.
 */
object Chromaprint {
    const val SAMPLE_RATE = 11025

    /** Chromaprint's own frame and step. */
    private const val FRAME_SIZE = 4096
    private const val FRAME_STEP = FRAME_SIZE / 3
    private const val MIN_FREQ = 28
    private const val MAX_FREQ = 3520
    private const val BANDS = 12

    /** CHROMAPRINT_ALGORITHM_TEST2, in the compressed fingerprint's first byte. */
    private const val ALGORITHM = 1

    private val FILTER_COEFFICIENTS = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)

    /** Algorithm 2's classifiers: filter (type, y, height, width) and quantizer thresholds. */
    private val CLASSIFIERS = listOf(
        Classifier(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
        Classifier(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
        Classifier(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
        Classifier(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
        Classifier(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
        Classifier(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
        Classifier(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
        Classifier(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
        Classifier(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
        Classifier(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
        Classifier(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
        Classifier(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
        Classifier(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
        Classifier(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
        Classifier(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
        Classifier(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339),
    )
    private val MAX_FILTER_WIDTH = CLASSIFIERS.maxOf { it.width }

    /** The compressed, base64-encoded fingerprint of [samples] (mono, [SAMPLE_RATE]), as chromaprint_get_fingerprint gives it. */
    fun fingerprint(samples: ShortArray): String = encode(raw(samples))

    /** The raw 32-bit values, one per frame after the first few, as chromaprint_get_raw_fingerprint gives them. */
    fun raw(samples: ShortArray): IntArray {
        val image = chroma(samples)
        val integral = IntegralImage(image, BANDS)
        val count = image.size / BANDS - MAX_FILTER_WIDTH + 1
        return IntArray(max(0, count)) { offset ->
            var bits = 0
            for (c in CLASSIFIERS) bits = (bits shl 2) or GRAY_CODE[c.classify(integral, offset)]
            bits
        }
    }

    /** The chroma image: 12 values per frame, smoothed over 5 frames and normalised, rows one after the other. */
    private fun chroma(samples: ShortArray): DoubleArray {
        val window = DoubleArray(FRAME_SIZE) { 0.54 - 0.46 * cos(it * 2.0 * PI / (FRAME_SIZE - 1)) }
        for (i in window.indices) window[i] /= Short.MAX_VALUE
        val notes = IntArray(FRAME_SIZE)
        val minIndex = max(1, (FRAME_SIZE.toDouble() * MIN_FREQ / SAMPLE_RATE).roundToInt())
        val maxIndex = min(FRAME_SIZE / 2, (FRAME_SIZE.toDouble() * MAX_FREQ / SAMPLE_RATE).roundToInt())
        for (i in minIndex until maxIndex) {
            val octave = ln(i.toDouble() * SAMPLE_RATE / FRAME_SIZE / (440.0 / 16.0)) / ln(2.0)
            notes[i] = (BANDS * (octave - floor(octave))).toInt()
        }
        val fft = Fft(FRAME_SIZE)
        val re = DoubleArray(FRAME_SIZE)
        val im = DoubleArray(FRAME_SIZE)
        val frames = ArrayList<DoubleArray>()
        var start = 0
        while (start + FRAME_SIZE <= samples.size) {
            for (i in 0 until FRAME_SIZE) re[i] = samples[start + i] * window[i]
            im.fill(0.0)
            fft.transform(re, im)
            val features = DoubleArray(BANDS)
            for (i in minIndex until maxIndex) features[notes[i]] += re[i] * re[i] + im[i] * im[i]
            frames += features
            start += FRAME_STEP
        }
        // The 5-frame filter, then each row scaled to length 1 (or zero when almost silent).
        val taps = FILTER_COEFFICIENTS.size
        val rows = max(0, frames.size - taps + 1)
        val out = DoubleArray(rows * BANDS)
        for (r in 0 until rows) {
            for (b in 0 until BANDS) {
                var sum = 0.0
                for (j in 0 until taps) sum += frames[r + j][b] * FILTER_COEFFICIENTS[j]
                out[r * BANDS + b] = sum
            }
            var squares = 0.0
            for (b in 0 until BANDS) squares += out[r * BANDS + b] * out[r * BANDS + b]
            val norm = sqrt(squares)
            for (b in 0 until BANDS) out[r * BANDS + b] = if (norm < 0.01) 0.0 else out[r * BANDS + b] / norm
        }
        return out
    }

    /** Chromaprint's compression of [raw] (fingerprint_compressor.cpp), base64-encoded URL-safe without padding. */
    fun encode(raw: IntArray): String {
        val normal = ArrayList<Int>(raw.size * 8)
        val exceptional = ArrayList<Int>()
        for (i in raw.indices) {
            var x = if (i == 0) raw[0] else raw[i] xor raw[i - 1]
            var bit = 1
            var lastBit = 0
            while (x != 0) {
                if (x and 1 != 0) {
                    val value = bit - lastBit
                    if (value >= MAX_NORMAL) {
                        normal += MAX_NORMAL
                        exceptional += value - MAX_NORMAL
                    } else {
                        normal += value
                    }
                    lastBit = bit
                }
                x = x ushr 1
                bit++
            }
            normal += 0
        }
        val header = byteArrayOf(ALGORITHM.toByte(), (raw.size shr 16).toByte(), (raw.size shr 8).toByte(), raw.size.toByte())
        return base64(header + pack(normal, 3) + pack(exceptional, 5))
    }

    /** [values] of [bits] bits each, packed one after the other, lowest bit first (pack_int3_array.h, pack_int5_array.h). */
    private fun pack(values: List<Int>, bits: Int): ByteArray {
        val out = ByteArray((values.size * bits + 7) / 8)
        var position = 0
        for (v in values) {
            for (b in 0 until bits) {
                if ((v shr b) and 1 != 0) out[position / 8] = (out[position / 8].toInt() or (1 shl (position % 8))).toByte()
                position++
            }
        }
        return out
    }

    private fun base64(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val s0 = bytes[i].toInt() and 0xff
            val s1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val s2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            val left = bytes.size - i
            out.append(BASE64[s0 shr 2])
            out.append(BASE64[((s0 shl 4) or (s1 shr 4)) and 63])
            if (left > 1) out.append(BASE64[((s1 shl 2) or (s2 shr 6)) and 63])
            if (left > 2) out.append(BASE64[s2 and 63])
            i += 3
        }
        return out.toString()
    }

    private const val MAX_NORMAL = 7
    private val GRAY_CODE = intArrayOf(0, 1, 3, 2)
    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Sums over rectangles of the chroma image: rows are frames, columns the 12 bands. */
    private class IntegralImage(image: DoubleArray, private val columns: Int) {
        private val sums = DoubleArray(image.size)

        init {
            val rows = image.size / columns
            for (r in 0 until rows) {
                var row = 0.0
                for (c in 0 until columns) {
                    row += image[r * columns + c]
                    sums[r * columns + c] = row + if (r > 0) sums[(r - 1) * columns + c] else 0.0
                }
            }
        }

        private fun at(r: Int, c: Int) = if (r < 0 || c < 0) 0.0 else sums[r * columns + c]

        /** Rows r1 until r2, columns c1 until c2. */
        fun area(r1: Int, c1: Int, r2: Int, c2: Int): Double {
            if (r1 == r2 || c1 == c2) return 0.0
            return at(r2 - 1, c2 - 1) - at(r1 - 1, c2 - 1) - at(r2 - 1, c1 - 1) + at(r1 - 1, c1 - 1)
        }
    }

    private class Classifier(val type: Int, val y: Int, val height: Int, val width: Int, val t0: Double, val t1: Double, val t2: Double) {
        fun classify(image: IntegralImage, x: Int): Int {
            val value = filter(image, x)
            return when {
                value < t1 -> if (value < t0) 0 else 1
                value < t2 -> 2
                else -> 3
            }
        }

        /** filter_utils.h: rows (time) x..x+width, columns (bands) y..y+height, compared by log ratio. */
        private fun filter(image: IntegralImage, x: Int): Double {
            val w = width
            val h = height
            val a: Double
            val b: Double
            when (type) {
                0 -> {
                    a = image.area(x, y, x + w, y + h)
                    b = 0.0
                }
                1 -> {
                    a = image.area(x, y + h / 2, x + w, y + h)
                    b = image.area(x, y, x + w, y + h / 2)
                }
                2 -> {
                    a = image.area(x + w / 2, y, x + w, y + h)
                    b = image.area(x, y, x + w / 2, y + h)
                }
                3 -> {
                    a = image.area(x, y + h / 2, x + w / 2, y + h) + image.area(x + w / 2, y, x + w, y + h / 2)
                    b = image.area(x, y, x + w / 2, y + h / 2) + image.area(x + w / 2, y + h / 2, x + w, y + h)
                }
                4 -> {
                    a = image.area(x, y + h / 3, x + w, y + 2 * (h / 3))
                    b = image.area(x, y, x + w, y + h / 3) + image.area(x, y + 2 * (h / 3), x + w, y + h)
                }
                else -> {
                    a = image.area(x + w / 3, y, x + 2 * (w / 3), y + h)
                    b = image.area(x, y, x + w / 3, y + h) + image.area(x + 2 * (w / 3), y, x + w, y + h)
                }
            }
            return ln((1.0 + a) / (1.0 + b))
        }
    }

    /** An in-place radix-2 FFT of a fixed power-of-two size. */
    private class Fft(private val n: Int) {
        private val cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
        private val sinTable = DoubleArray(n / 2) { -sin(2.0 * PI * it / n) }

        fun transform(re: DoubleArray, im: DoubleArray) {
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var size = 2
            while (size <= n) {
                val half = size / 2
                val step = n / size
                for (start in 0 until n step size) {
                    for (k in 0 until half) {
                        val wr = cosTable[k * step]
                        val wi = sinTable[k * step]
                        val a = start + k
                        val b = a + half
                        val tr = re[b] * wr - im[b] * wi
                        val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                    }
                }
                size *= 2
            }
        }
    }
}
