package com.trackrecord.data

import com.trackrecord.data.lists.ListRow
import com.trackrecord.data.lists.SongListParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongListParserTest {
    // Exportify's exact format: every field quoted, base columns first (TracksBaseData.ts), then
    // "Added By" / "Added At"; German header names, since Exportify translates them.
    private val exportifyDe = "﻿" + """
        "Track-URI","Track-Name","Künstler-URI(s)","Künstlername(n)","Album-URI","Albumname","Album-Künstler-URI(s)","Album-Künstlername(n)","Veröffentlichungsdatum des Albums","Album-Bild-URL","Disc-Nummer","Track-Nummer","Track-Dauer (ms)","Track-Vorschau-URL","Explizit?","Beliebtheit","ISRC","Hinzugefügt von","Hinzugefügt am"
        "spotify:track:7ouMYWpwJ422jRcDASZB7P","Wonderwall - Remastered","spotify:artist:2DaxqgrOhkeH0fpeiQq2f4","Oasis","spotify:album:x","(What's The Story) Morning Glory?","spotify:artist:2DaxqgrOhkeH0fpeiQq2f4","Oasis","1995-10-02","https://i","1","3","258773","","false","80","GBAAW9500189","spotify:user:felix","2026-10-24T10:00:00Z"
        "spotify:track:1","Can't Hold Us (feat. Ray Dalton)","spotify:artist:a, spotify:artist:b","Macklemore, Ryan Lewis","spotify:album:y","The Heist","","","2012-10-09","","1","6","258342","","false","75","USUYG1080153","",""
        "spotify:track:2","September","spotify:artist:c","Earth\, Wind & Fire","","","","","1978","","1","1","215000","","false","70","USSM17800845","",""
    """.trimIndent()

    @Test
    fun `exportify csv in any language`() {
        val rows = SongListParser.parse(exportifyDe)
        assertEquals(
            listOf(
                ListRow("Oasis", "Wonderwall - Remastered", "GBAAW9500189", 258773, releaseDate = "1995-10-02"),
                ListRow("Macklemore", "Can't Hold Us (feat. Ray Dalton)", "USUYG1080153", 258342, credit = "Macklemore, Ryan Lewis", releaseDate = "2012-10-09"),
                ListRow("Earth, Wind & Fire", "September", "USSM17800845", 215000, releaseDate = "1978"),
            ),
            rows,
        )
    }

    @Test
    fun `only Exportify's lists count as Exportify's (review 2 B-13)`() {
        assertTrue(SongListParser.isExportify(exportifyDe))
        assertFalse(SongListParser.isExportify("Datum;Betrag;Verwendungszweck\n01.10.2026;-12,50;Miete\n"))
        assertFalse(SongListParser.isExportify("Track name,Artist name\nSeptember,Earth Wind & Fire\n"))
        assertFalse(SongListParser.isExportify(""))
    }

    @Test
    fun `plain csv with header names, semicolons and quoted fields`() {
        val csv = "Titel;Interpret;Dauer\n\"Bohemian Rhapsody\";Queen;354\n\"Song; with semicolon\";\"Band \"\"X\"\"\";\n"
        assertEquals(
            listOf(ListRow("Queen", "Bohemian Rhapsody", null, 354_000), ListRow("Band \"X\"", "Song; with semicolon", null, null)),
            SongListParser.parse(csv),
        )
    }

    @Test
    fun `other csv files keep commas in artist names`() {
        assertEquals(listOf(ListRow("Earth, Wind & Fire", "September")), SongListParser.parse("Track name,Artist name\nSeptember,\"Earth, Wind & Fire\"\n"))
    }

    @Test
    fun `text lines artist - title`() {
        val text = "Queen - Bohemian Rhapsody\r\n\r\nOasis – Wonderwall\nno separator here\nA-ha - Take On Me\n"
        assertEquals(
            listOf(ListRow("Queen", "Bohemian Rhapsody"), ListRow("Oasis", "Wonderwall"), ListRow("A-ha", "Take On Me")),
            SongListParser.parse(text),
        )
    }

    @Test
    fun `first artist keeps escaped commas`() {
        assertEquals("Earth, Wind & Fire", SongListParser.firstArtist("Earth\\, Wind & Fire, Someone"))
        assertEquals("Macklemore", SongListParser.firstArtist("Macklemore, Ryan Lewis"))
    }
}
