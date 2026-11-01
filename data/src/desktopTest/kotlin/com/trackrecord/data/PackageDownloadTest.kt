package com.trackrecord.data

import com.sun.net.httpserver.HttpServer
import com.trackrecord.data.update.PackageDownload
import com.trackrecord.data.update.ReleasePackage
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop's update download (PLATFORMS.md phase 8), from a local server. */
class PackageDownloadTest {
    private val body = ByteArray(300_000) { (it % 251).toByte() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/TrackRecord.msi") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/missing.msi") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        start()
    }
    private val folder: File = Files.createTempDirectory("trackrecord-update").toFile()
    private val url = "http://127.0.0.1:${server.address.port}"
    private val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    @AfterTest
    fun cleanUp() {
        server.stop(0)
        folder.deleteRecursively()
    }

    @Test
    fun `the package arrives under its name, checked, with its progress`() = runBlocking {
        val progress = mutableListOf<Float>()
        val file = PackageDownload(folder).download(ReleasePackage("TrackRecord-0.4.200.msi", "$url/TrackRecord.msi", sha), progress::add)
        assertEquals(File(folder, "TrackRecord-0.4.200.msi"), file)
        assertContentEquals(body, file!!.readBytes())
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b }, "progress only grows")
        assertEquals(listOf("TrackRecord-0.4.200.msi"), folder.list()!!.toList())
    }

    @Test
    fun `a package that doesn't match GitHub's checksum is dropped`() = runBlocking {
        assertNull(PackageDownload(folder).download(ReleasePackage("TrackRecord.msi", "$url/TrackRecord.msi", "00$sha".take(64)), {}))
        assertEquals(emptyList(), folder.list()!!.toList())
    }

    @Test
    fun `a failed download or an odd name leaves nothing`() = runBlocking {
        assertNull(PackageDownload(folder).download(ReleasePackage("TrackRecord.msi", "$url/missing.msi", null), {}))
        assertNull(PackageDownload(folder).download(ReleasePackage("../TrackRecord.msi", "$url/TrackRecord.msi", null), {}))
        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }
}
