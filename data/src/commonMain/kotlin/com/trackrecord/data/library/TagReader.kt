package com.trackrecord.data.library

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
