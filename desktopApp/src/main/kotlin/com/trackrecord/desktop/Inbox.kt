package com.trackrecord.desktop

import java.io.File
import java.nio.file.StandardWatchEventKinds
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Files opened with the app while it runs (feedback round 55): Windows starts another Track Record for a
 * double-clicked library export, which hands the files to the running one through [folder] and ends. An empty
 * hand-over just brings the running window to the front.
 */
class Inbox(private val folder: File) {
    /** From the second start: [files] (file URIs) for the running app. */
    fun send(files: List<String>) {
        folder.mkdirs()
        val name = UUID.randomUUID().toString()
        val part = File(folder, "$name.part").apply { writeText(files.joinToString("\n")) }
        part.renameTo(File(folder, "$name$EXTENSION"))
    }

    /** In the running app: [onOpen] gets each hand-over, the ones waiting from before too; on a thread of its own. */
    fun watch(onOpen: (files: List<String>) -> Unit) {
        folder.mkdirs()
        val watcher = folder.toPath().fileSystem.newWatchService()
        folder.toPath().register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
        thread(isDaemon = true, name = "inbox") {
            while (true) {
                folder.listFiles { f -> f.name.endsWith(EXTENSION) }.orEmpty().sortedBy { it.lastModified() }.forEach { file ->
                    val files = runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
                    file.delete()
                    onOpen(files)
                }
                val key = runCatching { watcher.take() }.getOrNull() ?: return@thread
                key.pollEvents()
                key.reset()
            }
        }
    }

    private companion object {
        const val EXTENSION = ".open"
    }
}
