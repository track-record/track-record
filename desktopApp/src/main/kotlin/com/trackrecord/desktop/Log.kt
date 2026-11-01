package com.trackrecord.desktop

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The desktop app's log (PLATFORMS.md, the Log seam): stderr and log.txt in the data folder, the last run only. */
object Log {
    private var file: File? = null
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun open(folder: File) {
        file = File(folder, "log.txt").apply { delete() }
    }

    fun i(text: String) = write("I", text)

    fun w(text: String) = write("W", text)

    @Synchronized
    private fun write(level: String, text: String) {
        val line = "${LocalDateTime.now().format(time)} $level $text"
        System.err.println(line)
        runCatching { file?.appendText(line + System.lineSeparator()) }
    }
}
