package com.trackrecord.engine.musicbrainz

import java.text.Normalizer

/** One recording returned by MusicBrainz for a song (by recording ID, ISRC or search). */
data class MbCandidate(
    val artist: String,
    val title: String,
    val disambiguation: String? = null,
    /** Year of the earliest release that includes the recording. */
    val year: Int?,
)

/** Decides which MusicBrainz candidates match a song and which year to take from them. */
object MatchAcceptance {
    val REJECT_WORDS = listOf("live", "demo", "karaoke", "instrumental", "acoustic", "remix")

    private val trailingBrackets = Regex("""\s*[(\[{][^()\[\]{}]*[)\]}]\s*$""")
    private val dashSuffix = Regex("""\s+[-–—]\s+.*$""")
    private val featuring = Regex("""\s*\b(feat\.?|ft\.?|featuring)(\s|$).*$""")
    private val diacritics = Regex("""\p{Mn}+""")
    private val whitespace = Regex("""\s+""")
    private val dashes = Regex("""[\u2010\u2011\u2012\u2013\u2014\u2212]""")

    /** Lowercase, strip diacritics, drop "feat."/"ft." parts and bracketed or dash suffixes. */
    fun normalize(text: String): String {
        var s = basic(text)
        while (true) {
            val before = s
            s = s.replace(trailingBrackets, "").replace(dashSuffix, "").replace(featuring, "").trim()
            if (s == before) break
        }
        return s.replace(whitespace, " ").trim()
    }

    /** Lowercase, diacritics stripped, quotes straightened; nothing removed. */
    private fun basic(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
            // MusicBrainz writes e.g. "a‐ha" with U+2010; tags use "-" (DECISIONS.md D-073).
            .replace(dashes, "-")
            .replace(whitespace, " ")
            .trim()

    private fun containsWord(text: String, word: String) =
        Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(text)

    fun accepts(songArtist: String, songTitle: String, candidate: MbCandidate): Boolean {
        if (normalize(candidate.artist) != normalize(songArtist)) return false
        if (normalize(candidate.title) != normalize(songTitle)) return false
        return !rejectedVersion(songTitle, candidate)
    }

    /**
     * Strict titles, but the artist only has to be one of the credited ones on either side: lists and
     * catalogues credit "Macklemore, Ryan Lewis" or "Macklemore & Ryan Lewis", and a band like
     * "Earth, Wind & Fire" must survive comma separators (song list import, D-090).
     */
    fun acceptsCredited(songCredit: String, songTitle: String, candidate: MbCandidate): Boolean {
        if (creditedArtists(candidate.artist).intersect(creditedArtists(songCredit)).isEmpty()) return false
        if (normalize(candidate.title) != normalize(songTitle)) return false
        return !rejectedVersion(songTitle, candidate)
    }

    /**
     * Acceptance for a recording that AcoustID linked to the song's audio (DECISIONS.md D-076). The
     * audio already matches, so the names only guard against wrongly linked fingerprints: one of the
     * credited artists must be the song's, and one title must be the other plus extra words
     * ("Terrordome" / "Terrordome #TiH", "Fick ihn doch Live" / "Fick Ihn Doch (Live)"). The live,
     * remix … rule applies as in [accepts].
     */
    fun acceptsAudioMatch(songArtist: String, songTitle: String, candidate: MbCandidate): Boolean {
        if (creditedArtists(candidate.artist).intersect(creditedArtists(songArtist)).isEmpty()) return false
        if (!titlesCompatible(songTitle, candidate.title)) return false
        return !rejectedVersion(songTitle, candidate)
    }

    private val creditSeparator = Regex("""\s*(?:,|&|\+|/|;|\bx\b|\band\b|\bund\b|\bvs\.?|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b)\s*""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /** The whole credit and each artist in it, normalized: "AniMe & DJ Mad Dog" → that, "anime", "dj mad dog". */
    fun creditedArtists(credit: String): Set<String> =
        (listOf(normalize(credit)) + basic(credit).split(creditSeparator).map { normalize(it) }).filter { it.isNotEmpty() }.toSet()

    /** After normalizing, the words of one title start the other's (whole words only). */
    fun titlesCompatible(a: String, b: String): Boolean {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val (short, long) = if (x.size <= y.size) x to y else y to x
        return long.subList(0, short.size) == short
    }

    private fun words(title: String) = normalize(title).split(nonWord).filter { it.isNotEmpty() }

    private fun rejectedVersion(songTitle: String, candidate: MbCandidate): Boolean {
        val songWords = basic(songTitle)
        val candidateText = basic(candidate.title) + " " + basic(candidate.disambiguation.orEmpty())
        return REJECT_WORDS.any { containsWord(candidateText, it) && !containsWord(songWords, it) }
    }

    /** The earliest year among accepted candidates, or `null` if none is accepted. */
    fun bestYear(songArtist: String, songTitle: String, candidates: List<MbCandidate>): Int? =
        candidates.filter { it.year != null && accepts(songArtist, songTitle, it) }.minOfOrNull { it.year!! }
}
