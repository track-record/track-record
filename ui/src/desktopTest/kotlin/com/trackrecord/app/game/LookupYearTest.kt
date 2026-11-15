package com.trackrecord.app.game

import com.trackrecord.engine.year.YearPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A year MusicBrainz finds during a game follows "When sources disagree", as in the library (REVIEW-3 B-17). */
class LookupYearTest {
    private val song = SongInfo(
        songId = 1, uri = "u", title = "T", artist = "A", album = null, durationMs = 0, hasCover = false,
        baseYear = 1975, musicBrainzYear = null,
    )

    @Test
    fun `the setting decides between the tags and MusicBrainz`() {
        assertEquals(2011, song.yearWithLookup(2011, 2026, YearPreference.MUSICBRAINZ))
        assertEquals(1975, song.yearWithLookup(2011, 2026, YearPreference.TAGS))
        assertEquals(1975, song.yearWithLookup(2011, 2026, YearPreference.LOWEST), "a remaster's later year")
        assertEquals(1969, song.yearWithLookup(1969, 2026, YearPreference.LOWEST))
    }

    @Test
    fun `a year edited in the library stays, and a song without tags has MusicBrainz's already`() {
        assertNull(song.copy(manualYear = 1980).yearWithLookup(2011, 2026, YearPreference.MUSICBRAINZ))
        assertNull(song.copy(baseYear = null).yearWithLookup(2011, 2026, YearPreference.MUSICBRAINZ))
    }
}
