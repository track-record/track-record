package com.trackrecord.app

import com.trackrecord.app.remote.WebStrings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guest page shows texts by key; a key the app doesn't send renders as empty text with no error
 * (REVIEW.md T-02). Every key in app.js must be sent, and every key sent must be used.
 */
class WebStringsTest {
    /** All of the page's own scripts (app.js and its modules), not the vendored library. */
    private val js = File("src/main/assets/remote").listFiles().orEmpty().filter { it.extension == "js" }.sortedBy { it.name }.joinToString("\n") { it.readText() }

    /** Literal keys anywhere in the page, including both sides of `T(a ? "x" : "y")`. */
    private val used = Regex(""""((?:web|theme)_[a-zA-Z0-9_]+|app_name|list_separator)"""").findAll(js).map { it.groupValues[1] }.toSet()

    @Test
    fun `every text the guest page asks for is sent`() {
        val sent = WebStrings.keys
        val missing = used.filter { key -> key !in sent && key !in WebStrings.pluralKeys }
        assertEquals(emptyList(), missing.sorted())
        // Plurals are asked for with plural(key, n), never as a plain text.
        for (key in WebStrings.pluralKeys) assertTrue(!Regex("""\bT\("$key"""").containsMatchIn(js), "$key is a plural")
    }

    @Test
    fun `every text sent is used by the guest page`() {
        val themes = WebStrings.keys.filter { it.startsWith("theme_") }
        val unused = WebStrings.keys - themes.toSet() - WebStrings.pluralKeys.flatMap { listOf("${it}_one", "${it}_other") }.toSet() - used
        assertEquals(emptyList(), unused.sorted())
        assertTrue(WebStrings.pluralKeys.all { it in used }, "plural keys are used")
        // Theme names are asked for as `theme_${name}` for each of the page's themes.
        assertTrue(js.contains("T(`theme_\${"), "themes by name")
    }
}
