package com.trackrecord.app.settings

import com.trackrecord.app.AppConfig
import com.trackrecord.app.platform.UpdateInstaller
import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.data.update.AppRelease
import com.trackrecord.data.update.ReleasePackage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The check at start and "Update" on each platform (feedback round 47; PLATFORMS.md phase 8). */
class AppUpdatesTest {
    private val folder = Files.createTempDirectory("trackrecord-updates").toFile()
    private val settings = SettingsRepository(folder)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val apk = ReleasePackage("TrackRecord-0.4.200.apk", "https://a/t.apk", null)
    private val msi = ReleasePackage("TrackRecord-0.4.200.msi", "https://a/t.msi", "ab")
    private val newer = AppRelease(200, "0.4.200", "https://github.com/r/releases/tag/v0.4.200", listOf(apk, msi))

    init {
        AppConfig.current = AppConfig("0.4.100", 100, debug = false, guestPage = "", acoustIdKey = "", musicBrainzContact = "", updateCheck = true)
    }

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        folder.deleteRecursively()
    }

    private fun updates(extension: String, installer: UpdateInstaller?, release: AppRelease? = newer) =
        AppUpdates(scope, settings, extension, installer, latest = { release })

    private fun AppUpdates.available() = runBlocking { withTimeout(5_000) { status.first { it is UpdateStatus.Available } as UpdateStatus.Available } }

    @Test
    fun `a newer release is found by itself at start`() {
        assertEquals(newer, updates(".msi", null).available().release)
    }

    @Test
    fun `not when it is turned off, nor for the same version`() = runBlocking {
        settings.update { it.copy(checkForUpdates = false) }
        val off = updates(".msi", null)
        Thread.sleep(300)
        assertEquals(UpdateStatus.Unknown, off.status.value)
        off.check()
        withTimeout(5_000) { off.status.first { it is UpdateStatus.Available } }
        val same = updates(".msi", null, newer.copy(versionCode = 100))
        same.check()
        assertEquals(UpdateStatus.UpToDate, withTimeout(5_000) { same.status.first { it != UpdateStatus.Checking && it != UpdateStatus.Unknown } })
    }

    @Test
    fun `the desktop downloads its package and installs it, with the progress shown`() = runBlocking {
        val installed = CompletableDeferred<ReleasePackage>()
        val updates = updates(".msi", { pkg, onProgress ->
            onProgress(0.5f)
            installed.complete(pkg)
            true
        })
        assertTrue(updates.installsItself(updates.available().release))
        var opened: String? = null
        updates.update { opened = it }
        assertEquals(msi, withTimeout(5_000) { installed.await() })
        assertEquals(null, opened, "nothing for the browser")
        assertEquals(0.5f, (updates.status.value as UpdateStatus.Available).progress)
    }

    @Test
    fun `a failed download says so and may be tried again`() = runBlocking {
        var tries = 0
        val updates = updates(".msi", { _, _ -> tries++ == 1 })
        updates.available()
        updates.update {}
        val failed = withTimeout(5_000) { updates.status.first { (it as? UpdateStatus.Available)?.failed == true } } as UpdateStatus.Available
        assertEquals(null, failed.progress)
        updates.update {}
        withTimeout(5_000) { updates.status.first { (it as? UpdateStatus.Available)?.let { a -> !a.failed && a.progress != null } == true } }
        assertEquals(2, tries)
    }

    @Test
    fun `Android hands the APK's link to the browser, and without a package the release page`() {
        val android = updates(".apk", null)
        assertFalse(android.installsItself(android.available().release))
        var opened: String? = null
        android.update { opened = it }
        assertEquals(apk.url, opened)

        val noMsi = updates(".msi", { _, _ -> error("not called") }, newer.copy(packages = listOf(apk)))
        assertFalse(noMsi.installsItself(noMsi.available().release))
        noMsi.update { opened = it }
        assertEquals(newer.pageUrl, opened)
    }
}
