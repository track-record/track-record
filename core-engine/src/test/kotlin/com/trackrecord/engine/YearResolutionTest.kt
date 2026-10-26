package com.trackrecord.engine

import com.trackrecord.engine.year.TagYears
import com.trackrecord.engine.year.YearInputs
import com.trackrecord.engine.year.YearPreference
import com.trackrecord.engine.year.YearResolver
import com.trackrecord.engine.year.YearSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YearResolutionTest {
    private fun resolve(manual: Int? = null, original: Int? = null, tag: Int? = null, mb: Int? = null, preference: YearPreference = YearPreference.MUSICBRAINZ) =
        YearResolver.resolve(YearInputs(manual, original, tag, mb), CURRENT_YEAR, preference)

    @Test
    fun `manual edit wins`() {
        val r = resolve(manual = 1999, original = 1975, tag = 2011, mb = 1970)
        assertEquals(1999, r.year)
        assertEquals(YearSource.MANUAL, r.source)
    }

    @Test
    fun `MusicBrainz wins over the tag years`() {
        val earlierMb = resolve(tag = 2011, mb = 1975)
        assertEquals(1975, earlierMb.year)
        assertEquals(YearSource.MUSICBRAINZ, earlierMb.source)
        assertTrue(earlierMb.verified)
        assertTrue(earlierMb.sourcesDisagree)

        val laterMb = resolve(original = 1975, tag = 2011, mb = 1980)
        assertEquals(1980, laterMb.year)
        assertEquals(YearSource.MUSICBRAINZ, laterMb.source)
        assertTrue(laterMb.sourcesDisagree)
        assertEquals(1975, laterMb.baseYear)

        val agree = resolve(tag = 1984, mb = 1984)
        assertEquals(1984, agree.year)
        assertEquals(YearSource.MUSICBRAINZ, agree.source)
        assertFalse(agree.sourcesDisagree)
    }

    @Test
    fun `when sources disagree the setting decides, MusicBrainz by default`() {
        val inputs = YearInputs(tagOriginalYear = 1975, tagYear = 2011, musicBrainzYear = 1980)
        assertEquals(1980, YearResolver.resolve(inputs, CURRENT_YEAR).year, "the default: MusicBrainz leads (feedback round 32)")
        val lowest = YearResolver.resolve(inputs, CURRENT_YEAR, YearPreference.LOWEST)
        assertEquals(1975, lowest.year)
        assertEquals(YearSource.TAG_ORIGINAL, lowest.source)
        assertTrue(lowest.verified, "a MusicBrainz match still exists")
        assertEquals(1970, resolve(tag = 2011, mb = 1970, preference = YearPreference.LOWEST).year)
        assertEquals(YearSource.MUSICBRAINZ, resolve(tag = 1984, mb = 1984, preference = YearPreference.LOWEST).source, "equal: MusicBrainz")

        val tags = resolve(tag = 2011, mb = 1970, preference = YearPreference.TAGS)
        assertEquals(2011, tags.year)
        assertEquals(YearSource.TAG, tags.source)
        assertEquals(1970, resolve(mb = 1970, preference = YearPreference.TAGS).year, "no tag year: MusicBrainz")
        assertEquals(1999, resolve(manual = 1999, tag = 2011, mb = 1970, preference = YearPreference.TAGS).year, "a manual year always wins")
    }

    @Test
    fun `without MusicBrainz the base year is used, unverified`() {
        val r = resolve(original = 1975, tag = 2011)
        assertEquals(1975, r.year)
        assertEquals(1975, r.baseYear)
        assertFalse(r.verified)
        assertEquals(YearSource.TAG, resolve(tag = 2011).source)
    }

    @Test
    fun `no usable year means not playable, MusicBrainz alone makes it playable`() {
        assertFalse(resolve().playable)
        assertFalse(resolve(tag = 1899).playable)
        assertFalse(resolve(tag = CURRENT_YEAR + 1).playable)
        assertEquals(1966, resolve(mb = 1966).year)
        assertEquals(1966, resolve(manual = 1966).year)
    }

    @Test
    fun `TDOR is preferred over TDRC`() {
        val t = TagYears.extract(mapOf("TDRC" to "2011-03-01", "TDOR" to "1975-10-31"), CURRENT_YEAR)
        assertEquals(1975, t.originalYear)
        assertEquals(2011, t.year)
        val r = YearResolver.resolve(YearInputs(tagOriginalYear = t.originalYear, tagYear = t.year), CURRENT_YEAR)
        assertEquals(1975, r.year)
    }

    @Test
    fun `ID3v2_3 and Vorbis fields`() {
        assertEquals(TagYears.Result(1969, 2009), TagYears.extract(mapOf("TYER" to "2009", "TORY" to "1969"), CURRENT_YEAR))
        assertEquals(TagYears.Result(1969, 2009), TagYears.extract(mapOf("date" to "2009-01-01", "originaldate" to "1969"), CURRENT_YEAR))
        assertEquals(TagYears.Result(null, 1984), TagYears.extract(mapOf("TDRC" to "1984"), CURRENT_YEAR))
        // An unusable original year falls back to the next field
        assertEquals(TagYears.Result(1969, null), TagYears.extract(mapOf("TDOR" to "0000", "TORY" to "1969"), CURRENT_YEAR))
    }

    @Test
    fun `MP4 freeform and ID3 TXXX original years`() {
        assertEquals(
            TagYears.Result(1971, 2015),
            TagYears.extract(mapOf("©day" to "2015-06-01", "----:com.apple.iTunes:originaldate" to "1971-11-08"), CURRENT_YEAR),
        )
        assertEquals(TagYears.Result(1971, 2015), TagYears.extract(mapOf("TYER" to "2015", "TXXX:originalyear" to "1971"), CURRENT_YEAR))
    }

    @Test
    fun `recording id and ISRC fields`() {
        val id = "B1A9C0E9-D987-4042-AE91-78D6A3267D69"
        assertEquals(id.lowercase(), TagYears.recordingId(mapOf("UFID:http://musicbrainz.org" to id)))
        assertEquals(id.lowercase(), TagYears.recordingId(mapOf("musicbrainz_trackid" to id)))
        assertEquals(id.lowercase(), TagYears.recordingId(mapOf("----:com.apple.iTunes:MusicBrainz Track Id" to id)))
        assertNull(TagYears.recordingId(mapOf("UFID:http://musicbrainz.org" to "not-an-id")))
        assertNull(TagYears.recordingId(mapOf("MUSICBRAINZ_RELEASETRACKID" to id)))

        assertEquals("GBUM71029604", TagYears.isrc(mapOf("TSRC" to "GB-UM7-10-29604")))
        assertEquals("USRC17607839", TagYears.isrc(mapOf("isrc" to "usrc17607839")))
        assertEquals("USRC17607839", TagYears.isrc(mapOf("----:com.apple.iTunes:ISRC" to "USRC17607839\u0000")))
        assertNull(TagYears.isrc(mapOf("TSRC" to "12345")))
    }

    @Test
    fun `year parsing`() {
        assertEquals(1984, TagYears.parseYear("1984", CURRENT_YEAR))
        assertEquals(1984, TagYears.parseYear(" 1984-05-12T00:00", CURRENT_YEAR))
        assertEquals(1984, TagYears.parseYear("05/1984", CURRENT_YEAR))
        assertNull(TagYears.parseYear("84", CURRENT_YEAR))
        assertNull(TagYears.parseYear("1899", CURRENT_YEAR))
        assertNull(TagYears.parseYear("", CURRENT_YEAR))
        assertNull(TagYears.parseYear(null, CURRENT_YEAR))
        assertNull(TagYears.parseYear("12345", CURRENT_YEAR))
    }
}
