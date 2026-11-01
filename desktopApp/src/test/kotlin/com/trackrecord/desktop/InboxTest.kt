package com.trackrecord.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Files a second start hands to the running app (feedback round 55). */
class InboxTest {
    private val folder: File = Files.createTempDirectory("trackrecord-inbox").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun `hand-overs from before the app watched and while it watches arrive, each once`() {
        val inbox = Inbox(folder)
        inbox.send(listOf("file:/C:/a.trackrecord"))
        val received = LinkedBlockingQueue<List<String>>()
        inbox.watch { received.put(it) }
        assertEquals(listOf("file:/C:/a.trackrecord"), received.poll(5, TimeUnit.SECONDS))
        Inbox(folder).send(emptyList())
        assertEquals(emptyList(), received.poll(5, TimeUnit.SECONDS), "a start without files: to the front")
        Inbox(folder).send(listOf("file:/C:/b.csv", "file:/C:/c.zip"))
        assertEquals(listOf("file:/C:/b.csv", "file:/C:/c.zip"), received.poll(5, TimeUnit.SECONDS))
        assertEquals(null, received.poll(500, TimeUnit.MILLISECONDS), "nothing twice")
        assertEquals(emptyList(), folder.list()!!.filter { it.endsWith(".open") })
    }
}
