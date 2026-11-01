package com.trackrecord.app.ui

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Texts outside composables (the info sheet, the guests' texts, messages; PLATFORMS.md section 3), in the language of
 * the device. Compose Resources read their files once and keep them, so after the first text this doesn't wait.
 */
object Texts {
    /** Without [args], the text as written: templates stay templates (the guest page fills them; WebStrings). */
    fun get(text: StringResource, vararg args: Any): String = runBlocking { if (args.isEmpty()) getString(text) else getString(text, *args) }

    fun plural(text: PluralStringResource, quantity: Int, vararg args: Any): String =
        runBlocking { if (args.isEmpty()) getPluralString(text, quantity) else getPluralString(text, quantity, *args) }
}
