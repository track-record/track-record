package com.trackrecord.data.library

/** An audio file found while walking a picked folder. */
data class FoundFile(
    val uri: String,
    val relativePath: String,
    val lastModified: Long,
    val sizeBytes: Long,
)

class FolderUnavailableException(cause: Throwable? = null) : Exception(cause)

interface FolderScanner {
    /** All audio files below [treeUri], recursively. Throws [FolderUnavailableException] if access is lost. */
    fun scan(treeUri: String): List<FoundFile>

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
