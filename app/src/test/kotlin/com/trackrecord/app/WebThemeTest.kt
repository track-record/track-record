package com.trackrecord.app

import com.trackrecord.app.remote.WebTheme
import com.trackrecord.remote.WebPalette
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The guest page gets its colours with every view (D-277); before the first one, app.css shows the same
 * values. Each theme's block in app.css must hold exactly the palette the app sends.
 */
class WebThemeTest {
    private val css = File("src/main/assets/remote/app.css").readText()

    @Test
    fun `app css holds the palettes the app sends`() {
        val wrong = WebTheme.palettes.filter { (theme, palette) ->
            val block = Regex("""html\[data-theme="$theme"]\s*\{([^}]*)}""").find(css)?.groupValues?.get(1).orEmpty()
            val found = Regex("""(--[a-z-]+):\s*([^;]+);""").findAll(block).associate { it.groupValues[1] to it.groupValues[2].trim() }
            found != expected(palette)
        }
        // On a mismatch, the blocks as they should read.
        val wanted = wrong.entries.joinToString("\n") { (theme, palette) ->
            "html[data-theme=\"$theme\"] {\n" + expected(palette).entries.joinToString("\n") { (k, v) -> "  $k: $v;" } + "\n}"
        }
        assertEquals("", wanted, "app.css's theme blocks")
    }

    private fun expected(palette: WebPalette): Map<String, String> =
        palette.colors + mapOf("--heading-weight" to palette.headingWeight.toString(), "--base-dim-alpha" to palette.dim.toString())
}
