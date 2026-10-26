package com.trackrecord.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

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

/** Walks a Storage Access Framework tree with DocumentsContract queries (much faster than DocumentFile). */
class SafFolderScanner(private val context: Context) : FolderScanner {
    private val resolver get() = context.contentResolver

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_SIZE,
    )

    private fun hasPermission(treeUri: Uri) =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    override fun displayName(treeUri: String): String? = try {
        val tree = Uri.parse(treeUri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    override fun scan(treeUri: String): List<FoundFile> {
        val tree = Uri.parse(treeUri)
        if (!hasPermission(tree)) throw FolderUnavailableException()
        val result = mutableListOf<FoundFile>()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            // Root query fails if the folder was moved or deleted.
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            resolver.query(root, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
                if (!it.moveToFirst()) throw FolderUnavailableException()
            } ?: throw FolderUnavailableException()
            walk(tree, rootId, "", result)
        } catch (e: FolderUnavailableException) {
            throw e
        } catch (e: Exception) {
            // SecurityException, IllegalArgumentException, FileNotFoundException, ...
            throw FolderUnavailableException(e)
        }
        return result
    }

    private fun walk(tree: Uri, parentId: String, prefix: String, out: MutableList<FoundFile>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val subfolders = mutableListOf<Pair<String, String>>()
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == Document.MIME_TYPE_DIR) {
                    if (!name.startsWith(".")) subfolders += id to name
                } else if (AudioFiles.isAudio(name)) {
                    out += FoundFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                        relativePath = prefix + name,
                        lastModified = if (c.isNull(3)) 0 else c.getLong(3),
                        sizeBytes = if (c.isNull(4)) 0 else c.getLong(4),
                    )
                }
            }
        }
        for ((id, name) in subfolders) walk(tree, id, "$prefix$name/", out)
    }
}
