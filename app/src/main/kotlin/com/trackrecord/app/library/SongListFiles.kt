package com.trackrecord.app.library

import android.content.Context
import android.net.Uri

/**
 * Song lists in the picked file as (name, text): the file itself, or every CSV in a ZIP (Exportify's
 * "Export All"). Empty if it can't be read.
 */
fun readSongLists(context: Context, uri: Uri): List<Pair<String, String>> = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "Imported.csv"
    // 20 MB is far more than any export; larger files are not song lists.
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(MAX_BYTES) } ?: return emptyList()
    songLists(name, bytes)
}.getOrDefault(emptyList())

/** The same for a file in the Download folder (Exportify's automatic import, feedback round 32). */
fun readSongLists(file: java.io.File): List<Pair<String, String>> = runCatching {
    songLists(file.name, file.inputStream().use { it.readNBytesCompat(MAX_BYTES) })
}.getOrDefault(emptyList())

private fun songLists(name: String, bytes: ByteArray): List<Pair<String, String>> =
    if (name.endsWith(".zip", ignoreCase = true) || bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
        val files = mutableListOf<Pair<String, String>>()
        // Unpacked sizes are capped too, so a small crafted ZIP can't fill the memory (DECISIONS.md D-168).
        var unpacked = 0
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            repeat(MAX_ZIP_ENTRIES) {
                val entry = zip.nextEntry ?: return@use
                if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                    val text = zip.readNBytesCompat(MAX_BYTES - unpacked)
                    unpacked += text.size
                    files += entry.name.substringAfterLast('/').substringBeforeLast('.') to text.toString(Charsets.UTF_8)
                    if (unpacked >= MAX_BYTES) return@use
                }
            }
        }
        files
    } else {
        listOf(name.substringBeforeLast('.') to bytes.toString(Charsets.UTF_8))
    }

/** The largest song list file read, packed or unpacked (all of a ZIP's lists together). */
private const val MAX_BYTES = 20_000_000

/** Exportify's "Export All" has one CSV per playlist; nobody has this many. */
private const val MAX_ZIP_ENTRIES = 2_000

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
