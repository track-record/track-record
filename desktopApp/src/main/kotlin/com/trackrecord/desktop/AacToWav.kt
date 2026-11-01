package com.trackrecord.desktop

import net.sourceforge.jaad.SampleBuffer
import net.sourceforge.jaad.aac.Decoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * AAC in MP4 (m4a), which the JBR's Chromium can't decode (no licensed codecs; feedback round 55): decoded with JAAD
 * (its maintained fork: the original decodes inaccurately, 8% of the fingerprint's bits off against FFmpeg's 0.07%)
 * into a WAV in [folder], which Chromium plays, the room streams and the fingerprints read. The newest few are kept, so
 * a song played again (the reveal's full song, its preview) is ready at once.
 */
class AacToWav(private val folder: File) {
    /** Whether Chromium needs [file] decoded first. */
    fun needed(file: File): Boolean = file.extension.lowercase() in EXTENSIONS

    /** [file] as WAV, decoded once; null if JAAD can't read it (ALAC in an m4a, say). */
    @Synchronized
    fun wavOf(file: File): File? {
        folder.mkdirs()
        val key = MessageDigest.getInstance("SHA-1").digest("${file.absolutePath}|${file.length()}|${file.lastModified()}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        val wav = File(folder, "$key.wav")
        if (wav.isFile) {
            wav.setLastModified(System.currentTimeMillis())
            return wav
        }
        val part = File(folder, "$key.part")
        val ok = runCatching { decode(file, part) }.onFailure { Log.w("AAC: ${file.name} not decoded: $it") }.getOrDefault(false)
        if (!ok || !part.renameTo(wav)) {
            part.delete()
            return null
        }
        folder.listFiles { f -> f.extension == "wav" }.orEmpty().sortedByDescending { it.lastModified() }.drop(KEPT).forEach { it.delete() }
        return wav
    }

    private fun decode(file: File, out: File): Boolean = RandomAccessFile(file, "r").use { input ->
        val track = Mp4AudioTrack.read(input) ?: return false
        val decoder = Decoder.create(track.config)
        val buffer = SampleBuffer().apply { isBigEndian = false }
        RandomAccessFile(out, "rw").use { wav ->
            wav.setLength(0)
            wav.write(ByteArray(HEADER))
            var rate = 0
            var channels = 0
            var bytes = 0L
            for (i in track.offsets.indices) {
                val frame = ByteArray(track.sizes[i])
                input.seek(track.offsets[i])
                input.readFully(frame)
                // A damaged frame is left out rather than ending the song there.
                if (runCatching { decoder.decodeFrame(frame, buffer) }.isFailure) continue
                rate = buffer.sampleRate
                channels = buffer.channels
                wav.write(buffer.data)
                bytes += buffer.data.size
            }
            if (bytes == 0L) return false
            wav.seek(0)
            wav.write(header(rate, channels, bytes))
        }
        true
    }

    private companion object {
        val EXTENSIONS = setOf("m4a", "mp4", "aac", "m4b")

        /** Songs kept decoded: the round's, the one before, previews. */
        const val KEPT = 4
        const val HEADER = 44

        /** A WAV header for 16-bit PCM. */
        fun header(rate: Int, channels: Int, bytes: Long): ByteArray = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + bytes).toInt())
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(rate)
            putInt(rate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(bytes.toInt())
        }.array()
    }
}
