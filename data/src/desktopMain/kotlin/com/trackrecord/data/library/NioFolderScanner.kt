package com.trackrecord.data.library

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Folders on the desktop (PLATFORMS.md phase 3): a folder is a `file:` URI, its songs are found by extension, and
 * the relative path, last modified time and size give the same "changed" check as on Android. Songs are `file:` URIs.
 * A subfolder that can't be read (a drive's "System Volume Information", a protected folder) is skipped, and only a
 * folder that can't be read itself is unavailable (REVIEW-3 B-18).
 */
class NioFolderScanner : FolderScanner {
    override fun scan(treeUri: String): List<FoundFile> = scanContents(treeUri).files

    override fun scanContents(treeUri: String): FolderContents {
        val root = folder(treeUri)?.takeIf { it.isDirectory() } ?: throw FolderUnavailableException()
        val files = mutableListOf<FoundFile>()
        val unreadable = mutableListOf<String>()
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (attributes.isRegularFile && AudioFiles.isAudio(path.name)) {
                            files += FoundFile(
                                uri = path.toUri().toString(),
                                relativePath = root.relativize(path).joinToString("/"),
                                lastModified = attributes.lastModifiedTime().toMillis(),
                                sizeBytes = attributes.size(),
                            )
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(path: Path, e: IOException): FileVisitResult {
                        if (path == root) throw e
                        unreadable += folderUri(path)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, e: IOException?): FileVisitResult {
                        // Listing it broke off part way: what it held is kept, as for one that couldn't be opened.
                        if (e != null) {
                            if (dir == root) throw e
                            unreadable += folderUri(dir)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (e: Exception) {
            throw FolderUnavailableException(e)
        }
        return FolderContents(files, unreadable)
    }

    override fun displayName(treeUri: String): String? = folder(treeUri)?.takeIf { it.isDirectory() }?.name

    companion object {
        /** The URI the app stores for [folder]. */
        fun uriOf(folder: File): String = folder.toPath().toAbsolutePath().normalize().toUri().toString()

        private fun folder(uri: String): Path? = runCatching { Path.of(URI(uri)) }.getOrNull()

        /** A skipped entry's URI as a folder's ("/" at the end), so it is a prefix of the songs below it only. */
        private fun folderUri(path: Path): String = path.toUri().toString().let { if (it.endsWith("/")) it else "$it/" }
    }
}
