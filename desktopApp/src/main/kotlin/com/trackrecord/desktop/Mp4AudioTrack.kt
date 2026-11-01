package com.trackrecord.desktop

import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * The first AAC track of an MP4 file (m4a), read from the few boxes a decoder needs: JAAD's own reader gives up on the
 * iTunes tags that FFmpeg and others write (feedback round 55). [config] is the decoder's AudioSpecificConfig (from
 * esds); [offsets] and [sizes] place each frame in the file (stco or co64, stsc, stsz). The decoder leaves out the
 * encoder's priming itself.
 */
class Mp4AudioTrack(val config: ByteArray, val offsets: LongArray, val sizes: IntArray) {
    companion object {
        /** The track, or null if [file] has no AAC track this reads. */
        fun read(file: RandomAccessFile): Mp4AudioTrack? {
            val moov = topLevel(file, "moov") ?: return null
            for (trak in children(moov, "trak")) {
                val stbl = child(trak, "mdia")?.let { child(it, "minf") }?.let { child(it, "stbl") } ?: continue
                val config = child(stbl, "stsd")?.let(::audioConfig) ?: continue
                val sizes = child(stbl, "stsz")?.let(::sampleSizes) ?: continue
                val chunks = child(stbl, "stco")?.let { chunkOffsets(it, wide = false) } ?: child(stbl, "co64")?.let { chunkOffsets(it, wide = true) } ?: continue
                val offsets = child(stbl, "stsc")?.let { sampleOffsets(it, chunks, sizes) } ?: continue
                return Mp4AudioTrack(config, offsets, sizes)
            }
            return null
        }

        /** The body of the top-level box [type], read whole (moov is small; mdat is only skipped). */
        private fun topLevel(file: RandomAccessFile, type: String): ByteBuffer? {
            var at = 0L
            while (at + 8 <= file.length()) {
                file.seek(at)
                var size = file.readInt().toLong() and 0xffffffffL
                val name = ByteArray(4).also { file.readFully(it) }.toString(Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) {
                    size = file.readLong()
                    header = 16
                } else if (size == 0L) {
                    size = file.length() - at
                }
                if (size < header) return null
                if (name == type) {
                    if (size > MAX_MOOV) return null
                    return ByteBuffer.wrap(ByteArray((size - header).toInt()).also { file.readFully(it) })
                }
                at += size
            }
            return null
        }

        /** The child boxes of [parent] named [type], each as its body; the children start after [skip] bytes. */
        private fun children(parent: ByteBuffer, type: String, skip: Int = 0): List<ByteBuffer> {
            val found = ArrayList<ByteBuffer>()
            var at = skip
            while (at + 8 <= parent.limit()) {
                val size = parent.getInt(at)
                val name = String(ByteArray(4) { parent.get(at + 4 + it) }, Charsets.ISO_8859_1)
                if (size < 8 || at + size > parent.limit()) break
                if (name == type) found += parent.duplicate().position(at + 8).limit(at + size).slice()
                at += size
            }
            return found
        }

        private fun child(parent: ByteBuffer, type: String, skip: Int = 0) = children(parent, type, skip).firstOrNull()

        /** The AudioSpecificConfig of the mp4a entry in stsd: its esds holds it in nested descriptors. */
        private fun audioConfig(stsd: ByteBuffer): ByteArray? {
            val mp4a = child(stsd, "mp4a", skip = 8) ?: return null
            // The entry's own fields come before its boxes: 28 bytes, 16 or 36 more in sound versions 1 and 2.
            val extra = when (mp4a.getShort(8).toInt()) {
                1 -> 16
                2 -> 36
                else -> 0
            }
            val esds = child(mp4a, "esds", skip = 28 + extra) ?: return null
            esds.position(4)
            if (descriptor(esds) == null) return null
            esds.position(esds.position() + 2)
            val flags = esds.get().toInt()
            if (flags and 0x80 != 0) esds.position(esds.position() + 2)
            if (flags and 0x40 != 0) esds.position(esds.position() + 1 + (esds.get(esds.position()).toInt() and 0xff))
            if (flags and 0x20 != 0) esds.position(esds.position() + 2)
            if (descriptor(esds)?.first != DECODER_CONFIG) return null
            esds.position(esds.position() + 13)
            val (tag, length) = descriptor(esds) ?: return null
            if (tag != DECODER_SPECIFIC || length > esds.remaining()) return null
            return ByteArray(length).also { esds.get(it) }
        }

        /** A descriptor's tag and length (1 to 4 bytes of 7 bits each), the buffer then at its content. */
        private fun descriptor(buffer: ByteBuffer): Pair<Int, Int>? {
            if (!buffer.hasRemaining()) return null
            val tag = buffer.get().toInt() and 0xff
            var length = 0
            repeat(4) {
                if (!buffer.hasRemaining()) return null
                val b = buffer.get().toInt() and 0xff
                length = (length shl 7) or (b and 0x7f)
                if (b and 0x80 == 0) return tag to length
            }
            return tag to length
        }

        private fun sampleSizes(stsz: ByteBuffer): IntArray {
            val fixed = stsz.getInt(4)
            val count = stsz.getInt(8)
            return IntArray(count) { if (fixed != 0) fixed else stsz.getInt(12 + 4 * it) }
        }

        private fun chunkOffsets(box: ByteBuffer, wide: Boolean): LongArray =
            LongArray(box.getInt(4)) { if (wide) box.getLong(8 + 8 * it) else box.getInt(8 + 4 * it).toLong() and 0xffffffffL }

        /** Each frame's place: stsc says how many frames each run of chunks holds, back to back from the chunk's offset. */
        private fun sampleOffsets(stsc: ByteBuffer, chunks: LongArray, sizes: IntArray): LongArray? {
            val runs = stsc.getInt(4)
            val offsets = LongArray(sizes.size)
            var sample = 0
            for (r in 0 until runs) {
                val first = stsc.getInt(8 + 12 * r) - 1
                val perChunk = stsc.getInt(12 + 12 * r)
                val end = if (r + 1 < runs) stsc.getInt(8 + 12 * (r + 1)) - 1 else chunks.size
                for (chunk in first until end) {
                    var at = chunks.getOrNull(chunk) ?: return null
                    repeat(perChunk) {
                        if (sample == sizes.size) return offsets
                        offsets[sample] = at
                        at += sizes[sample]
                        sample++
                    }
                }
            }
            return offsets.takeIf { sample == sizes.size }
        }

        private const val DECODER_CONFIG = 4
        private const val DECODER_SPECIFIC = 5

        /** Larger isn't an audio file's moov. */
        private const val MAX_MOOV = 64L shl 20
    }
}
