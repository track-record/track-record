package com.trackrecord.app.ui

import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.allPluralStringResources
import com.trackrecord.app.resources.allStringResources
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The texts as Compose Resources read them. The move from Android's files (PLATFORMS.md phase 5) was checked against
 * every text then; what stays true is checked here: no Android escapes (Compose shows them as they are) and only
 * numbered placeholders, which the guest page and both apps fill alike.
 */
class ComposeTextsTest {
    @Test
    fun `no Android escapes or unnumbered placeholders`() = runBlocking {
        val texts = Res.allStringResources.mapValues { getString(it.value) } +
            Res.allPluralStringResources.flatMap { (key, plural) -> listOf(1, 2).map { "$key/$it" to getPluralString(plural, it) } }
        val wrong = texts.filterValues { text -> ESCAPED.containsMatchIn(text) || UNNUMBERED.containsMatchIn(text) }
        assertEquals(emptyMap(), wrong)
    }

    @Test
    fun `placeholders are filled`() = runBlocking {
        assertEquals("3 cards", getPluralString(Res.allPluralStringResources.getValue("cards"), 3, 3))
        assertEquals("Anna, 2 vetoes", getString(Res.allStringResources.getValue("a11y_with_detail"), "Anna", "2 vetoes"))
        assertEquals(", ", Texts.get(Res.allStringResources.getValue("list_separator")))
    }

    private companion object {
        val ESCAPED = Regex("""\['"@?n]""")
        val UNNUMBERED = Regex("""%[sd]""")
    }
}
