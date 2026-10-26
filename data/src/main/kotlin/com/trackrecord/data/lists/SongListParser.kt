package com.trackrecord.data.lists

/** One song of an imported list (SPEC v2, Library: "Import song list"). */
data class ListRow(
    /** The main artist (searched for). */
    val artist: String,
    val title: String,
    val isrc: String? = null,
    /** Length of the whole track, if the list says. */
    val durationMs: Long? = null,
    /** Everyone credited, as the list writes it ("Macklemore, Ryan Lewis"); matching accepts any of them. */
    val credit: String = artist,
    /** The album's release date as the list gives it ("1997-03-17" or "1997"), if any. */
    val releaseDate: String? = null,
)

/**
 * Reads song lists:
 * - Exportify CSVs (Spotify playlists), recognised by `spotify:` URIs in the first column, so the
 *   header language doesn't matter. Columns: 1 name, 3 artist names (", "-separated, commas inside a
 *   name escaped as "\,"), 8 album release date, 12 duration (ms), 16 ISRC — from Exportify's source
 *   (TracksBaseData.ts).
 * - Other CSVs (comma, semicolon or tab) with a header row naming title and artist columns.
 * - Text files with one "Artist - Title" per line.
 */
object SongListParser {
    /** Whether [text] is an Exportify CSV, recognised as [parse] does (REVIEW-2 B-13). */
    fun isExportify(text: String): Boolean {
        val clean = text.removePrefix("\uFEFF")
        val first = clean.lines().firstOrNull { it.isNotBlank() } ?: return false
        val delimiter = listOf(',', ';', '\t').maxBy { d -> splitCsv(first, d).size }
        return csvRecords(clean, delimiter).drop(1).firstOrNull()?.firstOrNull()?.startsWith("spotify:") == true
    }

    fun parse(text: String): List<ListRow> {
        val clean = text.removePrefix("\uFEFF")
        val lines = clean.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> splitCsv(lines.first(), d).size }
        val header = splitCsv(lines.first(), delimiter)
        if (header.size >= 2) {
            val records = csvRecords(clean, delimiter)
            val data = records.drop(1)
            if (data.isNotEmpty() && data.first().firstOrNull()?.startsWith("spotify:") == true) return exportify(data)
            byHeader(header, data)?.let { return it }
        }
        return lines.mapNotNull { textLine(it) }
    }

    private fun exportify(rows: List<List<String>>): List<ListRow> = rows.mapNotNull { r ->
        val title = r.getOrNull(1)?.trim().orEmpty()
        val names = r.getOrNull(3).orEmpty()
        val artist = firstArtist(names)
        if (title.isEmpty() || artist.isEmpty()) null
        else ListRow(
            artist, title, r.getOrNull(16)?.trim()?.ifEmpty { null }, r.getOrNull(12)?.trim()?.toLongOrNull(), names.replace("\\,", ",").trim(),
            releaseDate = r.getOrNull(8)?.trim()?.ifEmpty { null },
        )
    }

    /** "A, B" → "A"; "Earth\, Wind & Fire, X" → "Earth, Wind & Fire". */
    fun firstArtist(names: String): String =
        names.split(Regex("""(?<!\\),\s*""")).firstOrNull().orEmpty().replace("\\,", ",").trim()

    private val titleHeaders = setOf("title", "track", "track name", "trackname", "song", "song name", "name", "titel", "songtitel", "track-name", "trackname")
    private val artistHeaders = setOf("artist", "artists", "artist name", "artist name(s)", "artist names", "interpret", "künstler", "künstlername", "künstlername(n)")
    private val durationHeaders = setOf("duration", "duration (ms)", "duration_ms", "track duration (ms)", "length", "dauer", "länge", "track-dauer (ms)")

    private fun byHeader(header: List<String>, rows: List<List<String>>): List<ListRow>? {
        val h = header.map { it.trim().lowercase() }
        val title = h.indexOfFirst { it in titleHeaders }.takeIf { it >= 0 } ?: return null
        val artist = h.indexOfFirst { it in artistHeaders }.takeIf { it >= 0 } ?: return null
        val isrc = h.indexOf("isrc")
        val duration = h.indexOfFirst { it in durationHeaders }
        return rows.mapNotNull { r ->
            val t = r.getOrNull(title)?.trim().orEmpty()
            // Other tools don't escape commas, so a band like "Earth, Wind & Fire" stays whole here.
            val a = r.getOrNull(artist).orEmpty().trim()
            if (t.isEmpty() || a.isEmpty()) return@mapNotNull null
            val d = r.getOrNull(duration)?.trim()?.toLongOrNull()?.let { if (it < 10_000) it * 1000 else it } // seconds or ms
            ListRow(a, t, r.getOrNull(isrc)?.trim()?.ifEmpty { null }, d)
        }
    }

    private val separator = Regex("""\s+[-–—]\s+""")

    private fun textLine(line: String): ListRow? {
        val parts = line.trim().split(separator, limit = 2)
        if (parts.size != 2) return null
        val (artist, title) = parts.map { it.trim() }
        return if (artist.isEmpty() || title.isEmpty()) null else ListRow(artist, title)
    }

    /** CSV records (RFC 4180: quoted fields may hold delimiters, quotes and line breaks). */
    fun csvRecords(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var field = StringBuilder()
        var record = mutableListOf<String>()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' && (quoted || field.isEmpty()) -> quoted = !quoted
                !quoted && c == delimiter -> { record += field.toString(); field = StringBuilder() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    record += field.toString(); field = StringBuilder()
                    if (record.any { it.isNotBlank() }) records += record
                    record = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        record += field.toString()
        if (record.any { it.isNotBlank() }) records += record
        return records
    }

    private fun splitCsv(line: String, delimiter: Char): List<String> = csvRecords(line, delimiter).firstOrNull().orEmpty()
}
