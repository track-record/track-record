package com.trackrecord.engine.year

/**
 * Picks years and lookup IDs from raw tag fields. Keys are matched case-insensitively and earlier
 * keys in each list are preferred. ID3 text frames use their frame id ("TDOR"), ID3 TXXX frames
 * "TXXX:<description>", ID3 UFID frames "UFID:<owner>", Vorbis comments their name, MP4 atoms their
 * atom name ("©day") and MP4 freeform atoms "----:<mean>:<name>".
 * Names follow Picard's format mappings (picard/formats/id3.py, vorbis.py, mp4.py; DECISIONS.md D-010).
 */
object TagYears {
    val ORIGINAL_KEYS = listOf(
        "TDOR", // ID3v2.4 original release date
        "TORY", // ID3v2.3 original release year
        "TXXX:ORIGINALYEAR", // ID3 (Picard writes unmapped tags as TXXX)
        "ORIGINALDATE", // Vorbis
        "ORIGINALYEAR", // Vorbis
        "----:COM.APPLE.ITUNES:ORIGINALDATE", // MP4 freeform (Picard)
        "----:COM.APPLE.ITUNES:ORIGINALYEAR",
    )
    val YEAR_KEYS = listOf(
        "TDRC", // ID3v2.4 recording/release date
        "TYER", // ID3v2.3 year
        "DATE", // Vorbis
        "YEAR",
        "©DAY", // MP4
    )
    val RECORDING_ID_KEYS = listOf(
        "UFID:HTTP://MUSICBRAINZ.ORG", // ID3
        "MUSICBRAINZ_TRACKID", // Vorbis: Picard stores the recording id here
        "----:COM.APPLE.ITUNES:MUSICBRAINZ TRACK ID", // MP4: also the recording id
    )
    val ISRC_KEYS = listOf(
        "TSRC", // ID3
        "ISRC", // Vorbis
        "----:COM.APPLE.ITUNES:ISRC", // MP4
    )

    data class Result(val originalYear: Int?, val year: Int?)

    fun extract(fields: Map<String, String?>, currentYear: Int): Result {
        val upper = upperKeys(fields)
        fun first(keys: List<String>) = keys.firstNotNullOfOrNull { parseYear(upper[it], currentYear) }
        return Result(first(ORIGINAL_KEYS), first(YEAR_KEYS))
    }

    private val mbid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val isrc = Regex("""^[A-Z]{2}[A-Z0-9]{3}\d{7}$""")

    /** The MusicBrainz recording id (a UUID, lowercased), if any field holds one. */
    fun recordingId(fields: Map<String, String?>): String? {
        val upper = upperKeys(fields)
        return RECORDING_ID_KEYS.firstNotNullOfOrNull { key -> upper[key]?.let { mbid.find(it)?.value?.lowercase() } }
    }

    /** The ISRC (12 characters, dashes and spaces removed, uppercased), if any field holds a valid one. */
    fun isrc(fields: Map<String, String?>): String? {
        val upper = upperKeys(fields)
        return ISRC_KEYS.firstNotNullOfOrNull { key ->
            upper[key]?.replace("-", "")?.replace(" ", "")?.uppercase()?.takeIf { isrc.matches(it) }
        }
    }

    private fun upperKeys(fields: Map<String, String?>) =
        fields.entries.associate { it.key.uppercase() to it.value?.trim()?.trimEnd('\u0000') }

    private val leadingYear = Regex("""^\s*(\d{4})(?!\d)""")
    private val anyYear = Regex("""(?<!\d)(\d{4})(?!\d)""")

    /** A 4-digit year between 1900 and [currentYear] from values like "1984", "1984-05-12" or "05/1984". */
    fun parseYear(raw: String?, currentYear: Int): Int? {
        if (raw.isNullOrBlank()) return null
        val match = leadingYear.find(raw) ?: anyYear.find(raw) ?: return null
        return match.groupValues[1].toInt().takeIf { YearResolver.isValidYear(it, currentYear) }
    }
}
