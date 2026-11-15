package com.trackrecord.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hidden pages: one whose renderer dies opens again (REVIEW-3 B-19), and their policies let them work (S-20). */
class ChromiumPagesTest {
    private val folder = Files.createTempDirectory("trackrecord-pages").toFile()
    private val chromium = Chromium(File(folder, "chromium"), debugPort = 0, mute = true, verbose = true)
    private val testlib = File(System.getProperty("trackrecord.testlib"))

    @AfterTest
    fun cleanUp() {
        chromium.close()
        folder.deleteRecursively()
    }

    /** Has the decoder page decode a second of [file]; whether its samples came back. */
    private fun decodes(port: PagePort, answers: LinkedBlockingQueue<JsonObject>, id: Int, file: File): Boolean {
        val address = chromium.publish(file)
        port.send(
            buildJsonObject {
                put("t", "decode")
                put("id", id)
                put("src", address)
                put("rate", 11025)
                put("seconds", 1)
            }.toString(),
        )
        while (true) {
            val m = answers.poll(30, TimeUnit.SECONDS) ?: return false
            if (m["id"]?.jsonPrimitive?.content != id.toString()) continue
            if (m["t"]?.jsonPrimitive?.content == "failed") return false
            if (m["t"]?.jsonPrimitive?.content == "pcm" && m.getValue("last").jsonPrimitive.boolean) return true
        }
    }

    /** One test: Chromium starts once per test process. */
    @Test
    fun `the hidden pages`() {
        roomScriptsLoad()
        pageOpensAgainAfterCrash()
    }

    /** A page whose renderer dies opens again behind the same port. */
    private fun pageOpensAgainAfterCrash() {
        val answers = LinkedBlockingQueue<JsonObject>()
        val port = PagePort("decoder-test") { answers += Json.parseToJsonElement(it) as JsonObject }
        val restarted = CountDownLatch(1)
        port.onRestart = { restarted.countDown() }
        chromium.open("desktop/decoder.html", port)
        val song = testlib.listFiles()!!.first { it.extension == "mp3" }
        assertTrue(decodes(port, answers, 1, song), "decodes under its policy")
        chromium.crashForTest(port)
        assertTrue(restarted.await(20, TimeUnit.SECONDS), "opened again")
        assertTrue(decodes(port, answers, 2, song), "and works again")
        chromium.close(port)
    }

    /** The room page's scripts load under its policy. */
    private fun roomScriptsLoad() {
        // Not joined: nothing goes out to the relays. A script the policy refused would never be fetched.
        val port = PagePort("room-test") { }
        chromium.open("desktop/room.html", port)
        val wanted = listOf("bridge.js", "room.js", "trystero-0.26.0.mjs", "p2p-common.js")
        var loaded = ""
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && !wanted.all { it in loaded }) {
            Thread.sleep(200)
            loaded = chromium.evaluateForTest(port, "performance.getEntriesByType('resource').map(e => e.name.split('/').pop()).join(',')").orEmpty()
        }
        assertEquals(wanted, wanted.filter { it in loaded }, "loaded: $loaded")
        chromium.close(port)
    }
}
