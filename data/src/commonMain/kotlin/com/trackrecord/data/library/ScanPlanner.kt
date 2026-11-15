package com.trackrecord.data.library

import com.trackrecord.data.db.SongScanState

/** Decides which files of a folder need their tags read and which songs disappeared. */
object ScanPlanner {
    data class Plan(
        /** New or changed files, with the existing row id for changed ones (0 for new). */
        val toRead: List<Pair<FoundFile, Long>>,
        val unchanged: Int,
        /** Ids of songs whose file is gone. */
        val deletions: List<Long>,
    )

    /** [unreadable]: subfolders' URIs (ending in "/") the scan couldn't read; their songs aren't taken as gone. */
    fun plan(existing: List<SongScanState>, found: List<FoundFile>, unreadable: List<String> = emptyList()): Plan {
        val byUri = existing.associateBy { it.uri }
        val foundUris = found.mapTo(HashSet()) { it.uri }
        var unchanged = 0
        val toRead = mutableListOf<Pair<FoundFile, Long>>()
        for (file in found) {
            val row = byUri[file.uri]
            if (row != null && row.lastModified == file.lastModified && row.sizeBytes == file.sizeBytes) {
                unchanged++
            } else {
                toRead += file to (row?.id ?: 0L)
            }
        }
        val deletions = existing.filter { s -> s.uri !in foundUris && unreadable.none { s.uri.startsWith(it) } }.map { it.id }
        return Plan(toRead, unchanged, deletions)
    }
}
