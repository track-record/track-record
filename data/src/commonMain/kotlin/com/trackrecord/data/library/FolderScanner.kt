package com.trackrecord.data.library

/** An audio file found while walking a picked folder. */
data class FoundFile(
    val uri: String,
    val relativePath: String,
    val lastModified: Long,
    val sizeBytes: Long,
)

class FolderUnavailableException(cause: Throwable? = null) : Exception(cause)

/**
 * What a scan found: the audio [files], and the URIs (ending in "/") of subfolders that couldn't be read. Songs below
 * those are kept as they are, not taken as deleted (REVIEW-3 B-18).
 */
data class FolderContents(val files: List<FoundFile>, val unreadable: List<String> = emptyList())

interface FolderScanner {
    /** All audio files below [treeUri], recursively. Throws [FolderUnavailableException] if access is lost. */
    fun scan(treeUri: String): List<FoundFile>

    /** [scan] with the subfolders it couldn't read; a scanner that reads all or nothing has none. */
    fun scanContents(treeUri: String): FolderContents = FolderContents(scan(treeUri))

    /** The folder's display name, or null if it can't be read. */
    fun displayName(treeUri: String): String?
}

object AudioFiles {
    val EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus")

    fun isAudio(fileName: String): Boolean {
        if (fileName.startsWith("._")) return false // macOS resource forks
        return fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS
    }
}
