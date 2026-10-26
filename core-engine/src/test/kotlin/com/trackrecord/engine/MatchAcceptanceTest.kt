package com.trackrecord.engine

import com.trackrecord.engine.musicbrainz.MatchAcceptance
import com.trackrecord.engine.musicbrainz.MbCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchAcceptanceTest {
    private fun n(s: String) = MatchAcceptance.normalize(s)

    @Test
    fun `remaster suffixes normalize away`() {
        assertEquals("bohemian rhapsody", n("Bohemian Rhapsody (Remastered 2011)"))
        assertEquals("bohemian rhapsody", n("Bohemian Rhapsody - 2011 Remaster"))
        assertEquals("bohemian rhapsody", n("Bohemian Rhapsody [2011 Remaster] - Single Version"))
    }

    @Test
    fun `feat parts, diacritics and case`() {
        assertEquals("beyonce", n("Beyoncé feat. Jay-Z"))
        assertEquals("crazy in love", n("Crazy in Love (feat. Jay-Z)"))
        assertEquals("crazy in love", n("Crazy In Love ft. Jay-Z"))
        assertEquals("motorhead", n("Motörhead"))
        assertEquals("daft punk", n("Daft Punk"))
        assertEquals("(don't fear) the reaper", n("(Don’t Fear) The Reaper"))
    }

    @Test
    fun `typographic hyphens match plain ones`() {
        assertTrue(MatchAcceptance.accepts("a-ha", "Take On Me", MbCandidate("a\u2010ha", "Take On Me", year = 1985)))
        assertTrue(MatchAcceptance.accepts("Jay\u2011Z", "99 Problems", MbCandidate("Jay-Z", "99 Problems", year = 2004)))
    }

    @Test
    fun `accepts only equal normalized artist and title`() {
        val c = MbCandidate("Queen", "Bohemian Rhapsody", year = 1975)
        assertTrue(MatchAcceptance.accepts("Queen", "Bohemian Rhapsody (Remastered 2011)", c))
        assertFalse(MatchAcceptance.accepts("Queen", "Killer Queen", c))
        assertFalse(MatchAcceptance.accepts("Freddie Mercury", "Bohemian Rhapsody", c))
    }

    @Test
    fun `live and remix candidates are rejected`() {
        val live = MbCandidate("Queen", "Bohemian Rhapsody", disambiguation = "live, 1986-07-12: Wembley Stadium", year = 1986)
        val remix = MbCandidate("Queen", "Bohemian Rhapsody", disambiguation = "Remix", year = 1992)
        val liveTitle = MbCandidate("Queen", "Bohemian Rhapsody (Live)", year = 1979)
        assertFalse(MatchAcceptance.accepts("Queen", "Bohemian Rhapsody", live))
        assertFalse(MatchAcceptance.accepts("Queen", "Bohemian Rhapsody", remix))
        assertFalse(MatchAcceptance.accepts("Queen", "Bohemian Rhapsody", liveTitle))
        for (word in listOf("demo", "karaoke", "instrumental", "acoustic")) {
            assertFalse(MatchAcceptance.accepts("Queen", "Bohemian Rhapsody", MbCandidate("Queen", "Bohemian Rhapsody", word, 1990)))
        }
    }

    @Test
    fun `live candidates are allowed when the song's own title says live`() {
        val live = MbCandidate("Queen", "Bohemian Rhapsody", disambiguation = "live", year = 1986)
        assertTrue(MatchAcceptance.accepts("Queen", "Bohemian Rhapsody (Live at Wembley)", live))
        val title = MbCandidate("Wings", "Live and Let Die", year = 1973)
        assertTrue(MatchAcceptance.accepts("Wings", "Live and Let Die", title))
        // the word must match on its own, not as part of another word
        assertTrue(MatchAcceptance.accepts("Bon Jovi", "Livin' on a Prayer", MbCandidate("Bon Jovi", "Livin' on a Prayer", "album version", 1986)))
    }

    @Test
    fun `audio matches accept a credited artist and a title that extends the other`() {
        // Real AcoustID results for Felix's songs (D-076).
        assertTrue(MatchAcceptance.acceptsAudioMatch("AniMe", "Terrordome", MbCandidate("AniMe", "Terrordome #TiH", year = 2008)))
        assertTrue(MatchAcceptance.acceptsAudioMatch("AniMe", "Hardcore machine", MbCandidate("AniMe & DJ Mad Dog", "Hardcore Machine", year = 2010)))
        assertTrue(MatchAcceptance.acceptsAudioMatch("Alligatoah", "Fick ihn doch Live", MbCandidate("Alligatoah", "Fick Ihn Doch (Live)", year = 2014)))
        assertTrue(MatchAcceptance.acceptsAudioMatch("Queen feat. David Bowie", "Under Pressure", MbCandidate("Queen & David Bowie", "Under Pressure", year = 1981)))
        // The strict rule still says no to these.
        assertFalse(MatchAcceptance.accepts("AniMe", "Terrordome", MbCandidate("AniMe", "Terrordome #TiH", year = 2008)))
        assertFalse(MatchAcceptance.accepts("AniMe", "Hardcore machine", MbCandidate("AniMe & DJ Mad Dog", "Hardcore Machine", year = 2010)))
    }

    @Test
    fun `audio matches still need both names to agree and keep the live and remix rule`() {
        assertFalse(MatchAcceptance.acceptsAudioMatch("AniMe", "Terrordome", MbCandidate("Someone Else", "Terrordome", year = 2008)), "other artist")
        assertFalse(MatchAcceptance.acceptsAudioMatch("AniMe", "Terrordome", MbCandidate("AniMe", "Hardcore Machine", year = 2010)), "other title")
        assertFalse(MatchAcceptance.acceptsAudioMatch("AniMe", "Terror", MbCandidate("AniMe", "Terrordome", year = 2008)), "whole words only")
        assertFalse(MatchAcceptance.acceptsAudioMatch("Queen", "Bohemian Rhapsody", MbCandidate("Queen", "Bohemian Rhapsody", "live", 1986)))
        assertFalse(MatchAcceptance.acceptsAudioMatch("Queen", "Bohemian Rhapsody", MbCandidate("Queen", "Bohemian Rhapsody (Remix)", year = 1992)))
    }

    @Test
    fun `credited acceptance keeps strict titles but any credited artist`() {
        assertTrue(MatchAcceptance.acceptsCredited("Earth, Wind & Fire", "September", MbCandidate("Earth, Wind & Fire", "September", year = 1978)))
        assertTrue(MatchAcceptance.acceptsCredited("Macklemore, Ryan Lewis", "Can't Hold Us (feat. Ray Dalton)", MbCandidate("Macklemore & Ryan Lewis", "Can't Hold Us", year = 2011)))
        assertFalse(MatchAcceptance.acceptsCredited("Earth, Wind & Fire", "September", MbCandidate("Earth, Wind & Fire", "September Rain", year = 1978)))
        assertFalse(MatchAcceptance.acceptsCredited("Earth, Wind & Fire", "September", MbCandidate("Someone", "September", year = 1978)))
        assertFalse(MatchAcceptance.acceptsCredited("Earth, Wind & Fire", "September", MbCandidate("Earth, Wind & Fire", "September (Live)", year = 1980)))
    }

    @Test
    fun `credited artists are split on common separators`() {
        assertEquals(setOf("anime & dj mad dog", "anime", "dj mad dog"), MatchAcceptance.creditedArtists("AniMe & DJ Mad Dog"))
        assertTrue("david bowie" in MatchAcceptance.creditedArtists("Queen feat. David Bowie"))
        assertTrue("b" in MatchAcceptance.creditedArtists("A, B und C"))
    }

    @Test
    fun `best year is the earliest accepted candidate`() {
        val candidates = listOf(
            MbCandidate("Queen", "Bohemian Rhapsody", year = 1981),
            MbCandidate("Queen", "Bohemian Rhapsody", year = 1975),
            MbCandidate("Queen", "Bohemian Rhapsody", "live", 1974),
            MbCandidate("Queen", "Other Song", year = 1970),
            MbCandidate("Queen", "Bohemian Rhapsody", year = null),
        )
        assertEquals(1975, MatchAcceptance.bestYear("Queen", "Bohemian Rhapsody - 2011 Remaster", candidates))
        assertNull(MatchAcceptance.bestYear("Queen", "Nope", candidates))
    }
}
