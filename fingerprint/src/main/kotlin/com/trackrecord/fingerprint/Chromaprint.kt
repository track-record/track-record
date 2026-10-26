package com.trackrecord.fingerprint

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Thin binding to the bundled Chromaprint library (see src/main/cpp). */
internal object Chromaprint {
    init {
        System.loadLibrary("trackrecord_fingerprint")
    }

    @JvmStatic external fun nativeNew(): Long
    @JvmStatic external fun nativeStart(ctx: Long, sampleRate: Int, channels: Int): Boolean
    @JvmStatic external fun nativeFeed(ctx: Long, samples: ShortArray, count: Int): Boolean
    @JvmStatic external fun nativeFinish(ctx: Long): String?
    @JvmStatic external fun nativeFree(ctx: Long)
    @JvmStatic external fun nativeDecode(encoded: String): IntArray?
    @JvmStatic external fun nativeFingerprintMp3(fd: Int, maxSeconds: Int): String?

    /** The raw 32-bit values of a compressed fingerprint (for comparing fingerprints in tests). */
    fun decode(encoded: String): IntArray? = nativeDecode(encoded)
}

/**
 * Computes AcoustID fingerprints from the first [maxSeconds] of a file, as `fpcalc` does (120 s by
 * default). MP3s are decoded natively (minimp3); other formats with the platform decoder, whose PCM is
 * handed to Chromaprint. At most [parallel] files are decoded at once.
 */
class AudioFingerprinter(
    private val context: Context,
    private val maxSeconds: Int = 120,
    parallel: Int = 2,
) {
    private val permits = Semaphore(parallel)

    /** The compressed, base64-encoded fingerprint, or null if the file can't be decoded. */
    suspend fun fingerprint(uri: Uri): String? = permits.withPermit {
        withContext(Dispatchers.Default) {
            runCatching { decode(uri) }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        }
    }

    private suspend fun decode(uri: Uri): String? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val ctx = Chromaprint.nativeNew()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (mime == MediaFormat.MIMETYPE_AUDIO_MPEG) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { Chromaprint.nativeFingerprintMp3(it.fd, maxSeconds) }
                    ?.let { return it }
            }
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var started = false
            var limit = Long.MAX_VALUE // samples (all channels) still wanted
            var fed = 0L
            var float = false
            var buffer = ShortArray(0)
            var idle = 0
            while (fed < limit) {
                coroutineContext.ensureActive()
                // Fill every free input buffer without waiting, then take output; only wait when the
                // decoder has nothing ready (waiting per frame made decoding several times slower).
                var queued = false
                while (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index < 0) break
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    queued = true
                }
                val out = codec.dequeueOutputBuffer(info, if (queued) 0 else TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (!started) {
                            if (!Chromaprint.nativeStart(ctx, rate, channels)) return null
                            started = true
                            limit = maxSeconds.toLong() * rate * channels
                        }
                    }
                    out >= 0 -> {
                        idle = 0
                        val bytes = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        bytes.position(info.offset).limit(info.offset + info.size)
                        val count = if (float) info.size / 4 else info.size / 2
                        if (buffer.size < count) buffer = ShortArray(count)
                        if (float) {
                            val floats = bytes.asFloatBuffer()
                            for (i in 0 until count) buffer[i] = (floats.get(i).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                        } else {
                            bytes.asShortBuffer().get(buffer, 0, count)
                        }
                        val take = minOf(count.toLong(), limit - fed).toInt()
                        if (started && take > 0) {
                            if (!Chromaprint.nativeFeed(ctx, buffer, take)) return null
                            fed += take
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> if (++idle > MAX_IDLE) return null // decoder stuck
                }
            }
            return if (started && fed > 0) Chromaprint.nativeFinish(ctx) else null
        } finally {
            Chromaprint.nativeFree(ctx)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private companion object {
        const val TIMEOUT_US = 2_000L
        /** About 20 s without any decoded output (waits of [TIMEOUT_US]). */
        const val MAX_IDLE = 10_000
    }
}
