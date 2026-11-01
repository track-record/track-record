package com.trackrecord.data.library

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus files, which jaudiotagger can't read (RFC 7845): the comments of the "OpusTags" packet (Vorbis-style
 * KEY=value), whether one is a picture, and the duration from the last page's granule position.
 */
internal object OpusTags {
    private const val SAMPLE_RATE = 48_000
    private val CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)

    fun read(file: File, put: (String, String?) -> Unit): RawTags? {
        val packets = firstPackets(file, 2)
        if (packets.size < 2 || !packets[0].startsWith("OpusHead") || !packets[1].startsWith("OpusTags")) return null
        val preSkip = ByteBuffer.wrap(packets[0], 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        var hasCover = false
        val tags = ByteBuffer.wrap(packets[1]).order(ByteOrder.LITTLE_ENDIAN)
        tags.position(8)
        val vendor = tags.int
        tags.position(tags.position() + vendor)
        repeat(tags.int) {
            val bytes = ByteArray(tags.int).also(tags::get)
            val comment = String(bytes, Charsets.UTF_8)
            val key = comment.substringBefore('=')
            if (key.equals("METADATA_BLOCK_PICTURE", ignoreCase = true)) hasCover = true else put(key, comment.substringAfter('=', ""))
        }
        val samples = lastGranule(file)?.let { it - preSkip } ?: 0
        return RawTags(emptyMap(), samples.coerceAtLeast(0) * 1000 / SAMPLE_RATE, hasCover)
    }

    private fun ByteArray.startsWith(magic: String) = size >= magic.length && String(this, 0, magic.length, Charsets.US_ASCII) == magic

    /** The first [count] packets of the stream, put together from their pages' segments. */
    private fun firstPackets(file: File, count: Int): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        RandomAccessFile(file, "r").use { f ->
            val header = ByteArray(27)
            while (packets.size < count && f.filePointer + header.size <= f.length()) {
                f.readFully(header)
                if (!header.copyOf(4).contentEquals(CAPTURE)) break
                val segments = ByteArray(header[26].toInt() and 0xFF).also(f::readFully)
                for (length in segments.map { it.toInt() and 0xFF }) {
                    current.write(ByteArray(length).also(f::readFully))
                    if (length < 255) {
                        packets += current.toByteArray()
                        current = java.io.ByteArrayOutputStream()
                    }
                }
            }
        }
        return packets.take(count)
    }

    /** The granule position (samples at 48 kHz) of the last page. */
    private fun lastGranule(file: File): Long? = RandomAccessFile(file, "r").use { f ->
        val tail = minOf(f.length(), 65_536L).toInt()
        val bytes = ByteArray(tail)
        f.seek(f.length() - tail)
        f.readFully(bytes)
        for (i in tail - 27 downTo 0) {
            if (bytes[i] == CAPTURE[0] && bytes.copyOfRange(i, i + 4).contentEquals(CAPTURE)) {
                return@use ByteBuffer.wrap(bytes, i + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
            }
        }
        null
    }
}
