package com.trackrecord.app.ui

import com.trackrecord.app.remote.WebStrings
import kotlin.test.Test
import kotlin.test.assertEquals

/** The guests' texts build from Compose Resources outside composition, as the app serves them at /api/strings. */
class WebStringsBuildTest {
    @Test
    fun `every key gets its text`() {
        val texts = WebStrings.build()
        assertEquals(WebStrings.keys, texts.keys)
        assertEquals(", ", texts["list_separator"])
    }
}
