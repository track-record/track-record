package com.trackrecord.app.ui.components

/** Years always print as plain digits: 1984, never 1,984 or 1.984. */
fun formatYear(year: Int): String = year.toString()

/** m:ss, digits only (no locale grouping). */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(java.util.Locale.ROOT, total / 60, total % 60)
}
