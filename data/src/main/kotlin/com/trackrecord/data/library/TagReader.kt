package com.trackrecord.data.library

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.inspector.MetadataRetriever
import kotlinx.coroutines.guava.await

/** Raw tag fields of one file, keyed as described in [com.trackrecord.engine.year.TagYears]. */
data class RawTags(
    val fields: Map<String, String>,
    val durationMs: Long,
    val hasCover: Boolean,
) {
    private fun first(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
        fields.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
    }

    val title: String? get() = first("TIT2", "TITLE")
    val artist: String? get() = first("TPE1", "ARTIST", "TPE2", "ALBUMARTIST")
    val album: String? get() = first("TALB", "ALBUM")
}

interface TagReader {
    /** Reads the tags of one file; throws if the file can't be parsed. */
    suspend fun read(uri: String): RawTags
}

/**
 * Reads tags with Media3's extractors: ID3v2.3/2.4 (text, TXXX and UFID frames), Vorbis comments
 * (FLAC, Ogg Vorbis, Opus) and MP4 ilst atoms including freeform "----" atoms (DECISIONS.md D-024).
 */
@OptIn(UnstableApi::class)
class Media3TagReader(private val context: Context) : TagReader {
    override suspend fun read(uri: String): RawTags {
        MetadataRetriever.Builder(context, MediaItem.fromUri(Uri.parse(uri))).build().use { retriever ->
            val groups = retriever.retrieveTrackGroups().await()
            val durationUs = runCatching { retriever.retrieveDurationUs().await() }.getOrDefault(C.TIME_UNSET)
            val entries = buildList {
                for (g in 0 until groups.length) {
                    val group = groups[g]
                    for (t in 0 until group.length) {
                        val metadata: Metadata = group.getFormat(t).metadata ?: continue
                        for (i in 0 until metadata.length()) add(metadata[i])
                    }
                }
            }
            return toRawTags(entries, if (durationUs == C.TIME_UNSET) 0 else durationUs / 1000)
        }
    }

    companion object {
        fun toRawTags(entries: List<Metadata.Entry>, durationMs: Long): RawTags {
            val fields = LinkedHashMap<String, String>()
            var hasCover = false
            fun put(key: String, value: String?) {
                if (value.isNullOrBlank()) return
                fields.putIfAbsent(key.uppercase(), value.trim().trimEnd('\u0000'))
            }
            for (entry in entries) {
                when (entry) {
                    is TextInformationFrame ->
                        if (entry.id == "TXXX") put("TXXX:${entry.description}", entry.values.firstOrNull())
                        else put(entry.id, entry.values.joinToString("; "))
                    is VorbisComment -> put(entry.key, entry.value)
                    is InternalFrame -> put("----:${entry.domain}:${entry.description}", entry.text)
                    is BinaryFrame -> if (entry.id == "UFID") parseUfid(entry.data)?.let { (owner, id) -> put("UFID:$owner", id) }
                    is ApicFrame, is PictureFrame -> hasCover = true
                    else -> Unit
                }
            }
            return RawTags(fields, durationMs, hasCover)
        }

        /** UFID frame body: owner identifier (ISO-8859-1, null-terminated), then up to 64 bytes of id. */
        fun parseUfid(data: ByteArray): Pair<String, String>? {
            val nul = data.indexOf(0)
            if (nul <= 0) return null
            val owner = String(data, 0, nul, Charsets.ISO_8859_1)
            val id = String(data, nul + 1, data.size - nul - 1, Charsets.US_ASCII)
            return owner to id
        }
    }
}
