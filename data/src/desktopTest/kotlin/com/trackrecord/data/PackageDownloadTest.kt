package com.trackrecord.data

import com.sun.net.httpserver.HttpServer
import com.trackrecord.data.update.PackageDownload
import com.trackrecord.data.update.ReleasePackage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val file = local(folder).download(ReleasePackage("TrackRecord-0.4.200.msi", "$url/TrackRecord.msi", sha), progress::add)
        assertEquals(File(folder, "TrackRecord-0.4.200.msi"), file)
        assertContentEquals(body, file!!.readBytes())
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b }, "progress only grows")
        assertEquals(listOf("TrackRecord-0.4.200.msi"), folder.list()!!.toList())
    }

    @Test
    fun `a package that doesn't match GitHub's checksum is dropped`() = runBlocking {
        assertNull(local(folder).download(ReleasePackage("TrackRecord.msi", "$url/TrackRecord.msi", "00$sha".take(64)), {}))
        assertEquals(emptyList(), folder.list()!!.toList())
    }

    @Test
    fun `a failed download or an odd name leaves nothing`() = runBlocking {
        assertNull(local(folder).download(ReleasePackage("TrackRecord.msi", "$url/missing.msi", sha), {}))
        assertNull(local(folder).download(ReleasePackage("TrackRecord.msi", "$url/TrackRecord.msi", null), {}), "no hash to check")
        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }

    @Test
    fun `only the public repo's own release files are fetched`() = runBlocking {
        // REVIEW-3 S-17: the file name ends up in the update script, and the link decides what runs.
        val own = "https://github.com/track-record/track-record/releases/download/v0.4.200/TrackRecord-0.4.200.msi"
        assertTrue(PackageDownload.isOwnRelease(ReleasePackage("TrackRecord-0.4.200.msi", own, sha)))
        assertTrue(PackageDownload.isOwnRelease(ReleasePackage("TrackRecord-0.4.200.apk", own.replace(".msi", ".apk"), sha)))
        assertFalse(PackageDownload.isOwnRelease(ReleasePackage("TrackRecord-0.4.200.msi", "https://example.com/TrackRecord-0.4.200.msi", sha)))
        assertFalse(PackageDownload.isOwnRelease(ReleasePackage("TrackRecord-0.4.200.msi", own.replace("v0.4.200", "v0.4.199"), sha)))
        assertFalse(PackageDownload.isOwnRelease(ReleasePackage("a\"&calc&\".msi", own, sha)))
        assertFalse(PackageDownload.isOwnRelease(ReleasePackage("../TrackRecord-0.4.200.msi", own, sha)))
        assertNull(PackageDownload(folder).download(ReleasePackage("TrackRecord-0.4.200.msi", "$url/TrackRecord.msi", sha), {}), "a local address")
        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }

    @Test
    fun `cancelling stops a download under way at once and leaves nothing`() = runBlocking {
        // REVIEW-3 B-15: the download had no cancel hook.
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.createContext("/stalled.msi") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong() * 10)
            exchange.responseBody.write(body)
            exchange.responseBody.flush()
            started.complete(Unit)
            release.await(30, TimeUnit.SECONDS) // then the rest never comes
            runCatching { exchange.close() }
        }
        val job = launch(Dispatchers.Default) { local(folder).download(ReleasePackage("TrackRecord-0.4.200.msi", "$url/stalled.msi", sha), {}) }
        started.await()
        withTimeout(5_000) { while (folder.list().orEmpty().isEmpty()) delay(10) } // the .part file is being written
        val cancelled = System.currentTimeMillis()
        job.cancelAndJoin()
        assertTrue(System.currentTimeMillis() - cancelled < 2_000, "ended at once")
        withTimeout(5_000) { while (folder.list().orEmpty().isNotEmpty()) delay(10) }
        release.countDown()
    }

    private fun local(folder: File) = PackageDownload(folder, trusted = { true })
}
