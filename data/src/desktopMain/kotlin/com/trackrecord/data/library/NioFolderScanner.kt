package com.trackrecord.data.library

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Folders on the desktop (PLATFORMS.md phase 3): a folder is a `file:` URI, its songs are found by extension, and
 * the relative path, last modified time and size give the same "changed" check as on Android. Songs are `file:` URIs.
 */
class NioFolderScanner : FolderScanner {
    override fun scan(treeUri: String): List<FoundFile> {
        val root = folder(treeUri)?.takeIf { it.isDirectory() } ?: throw FolderUnavailableException()
        return try {
            Files.walk(root).use { paths ->
                paths.filter { it.name.let(AudioFiles::isAudio) && Files.isRegularFile(it) }.map { path ->
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                    FoundFile(
                        uri = path.toUri().toString(),
                        relativePath = root.relativize(path).joinToString("/"),
                        lastModified = attributes.lastModifiedTime().toMillis(),
                        sizeBytes = attributes.size(),
                    )
                }.toList()
            }
        } catch (e: Exception) {
            throw FolderUnavailableException(e)
        }
    }

    override fun displayName(treeUri: String): String? = folder(treeUri)?.takeIf { it.isDirectory() }?.name

    companion object {
        /** The URI the app stores for [folder]. */
        fun uriOf(folder: File): String = folder.toPath().toAbsolutePath().normalize().toUri().toString()

        private fun folder(uri: String): Path? = runCatching { Path.of(URI(uri)) }.getOrNull()
    }
}
