package com.trackrecord.data.library

import com.trackrecord.engine.musicbrainz.MatchAcceptance

/** Numbers for a set of songs, e.g. a playlist with the library filters applied (feedback round 5). */
data class LibraryStats(
    val songs: Int,
    /** Distinct artists by normalized name ("Queen" and "QUEEN feat. X" count once); songs without an artist are left out. */
    val artists: Int,
    val withYear: Int,
    val minYear: Int?,
    val maxYear: Int?,
    /** Songs per year, every year from [minYear] to [maxYear] included (zero where there are none). */
    val perYear: List<Pair<Int, Int>>,
) {
    /** The year with the most songs (the earliest of equals), with its count. */
    val busiestYear: Pair<Int, Int>? get() = perYear.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenByDescending { it.first })?.takeIf { it.second > 0 }

    companion object {
        fun of(songs: List<LibrarySong>): LibraryStats {
            val years = songs.mapNotNull { it.resolved.year }
            val counts = years.groupingBy { it }.eachCount()
            val min = years.minOrNull()
            val max = years.maxOrNull()
            return LibraryStats(
                songs = songs.size,
                artists = songs.mapNotNull { s -> s.song.artist?.let(MatchAcceptance::normalize)?.takeIf { it.isNotEmpty() } }.toSet().size,
                withYear = years.size,
                minYear = min,
                maxYear = max,
                perYear = if (min == null || max == null) emptyList() else (min..max).map { it to (counts[it] ?: 0) },
            )
        }
    }
}
