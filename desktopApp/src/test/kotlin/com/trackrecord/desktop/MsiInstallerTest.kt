package com.trackrecord.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The update without the installer's wizard. */
class MsiInstallerTest {
    private val folder = Files.createTempDirectory("updates").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun `the script gets the package, the app's folder and process, and installs with a progress bar only`() {
        val exe = File(folder, "My Apps & Co/TrackRecord/TrackRecord.exe").apply { parentFile.mkdirs(); writeText("") }
        val msi = File(folder, "TrackRecord-9.9.9.msi")
        val run = MsiInstaller(folder, quit = {}, installedExe = exe).prepare(msi)
        assertEquals(msi.absolutePath, run.environment["TR_MSI"])
        assertEquals(exe.parentFile.absolutePath, run.environment["TR_DIR"])
        assertEquals(exe.absolutePath, run.environment["TR_EXE"])
        assertEquals(ProcessHandle.current().pid().toString(), run.environment["TR_PID"])
        assertTrue(run.environment.getValue("TR_MSIEXEC").endsWith("\\System32\\msiexec.exe"), "by its full path")
        val script = run.script.readText()
        assertTrue(""""%TR_MSIEXEC%" /i "%TR_MSI%" /passive /norestart INSTALLDIR="%TR_DIR%"""" in script, script)
        assertTrue("\r\n" in script, "a Windows script")
        // Run from Gradle: no installed app, so the default folder and no restart.
        val dev = MsiInstaller(folder, quit = {}, installedExe = null).prepare(msi)
        assertEquals(setOf("TR_MSI", "TR_MSIEXEC", "TR_PID"), dev.environment.keys)
    }

    @Test
    fun `packages of earlier updates are deleted, the script stays`() {
        File(folder, "TrackRecord-0.4.160.msi").writeText("")
        File(folder, "TrackRecord-0.4.170.msi.part").writeText("")
        File(folder, "update.cmd").writeText("")
        MsiInstaller(folder, quit = {}, installedExe = null).removeOldPackages()
        assertEquals(listOf("update.cmd"), folder.list()!!.toList())
    }

    // ---- The script's outcomes (REVIEW-3 T-11), with stand-ins: cmd.exe with nothing to read ends with 0, where.exe
    // with the installer's arguments with 2. The app is a script that writes down how it was started. ----

    private val system32 = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
    private val app = File(folder, "TrackRecord.cmd")
    private val started = File(folder, "started.txt")

    /** Runs the update script for a package with [installer] as msiexec; its exit code. [waitFor]: the app's process. */
    private fun runScript(installer: String, withApp: Boolean = true, waitFor: Long? = null): Int {
        app.writeText("@echo off\r\necho started %*> \"%~dp0started.txt\"\r\n")
        val run = MsiInstaller(folder, quit = {}, installedExe = null).prepare(File(folder, "TrackRecord-9.9.9.msi"))
        val environment = run.environment + mapOf("TR_MSIEXEC" to File(system32, installer).path, "TR_PID" to (waitFor ?: endedPid()).toString()) +
            if (withApp) mapOf("TR_DIR" to folder.path, "TR_EXE" to app.path) else emptyMap()
        val empty = File(folder, "empty.txt").apply { writeText("") }
        val process = ProcessBuilder("cmd", "/c", run.script.path).redirectInput(empty).redirectErrorStream(true)
            .redirectOutput(File(folder, "out.txt")).apply { environment().putAll(environment) }.start()
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "the script ended")
        return process.exitValue()
    }

    /** A process id that is no longer running. */
    private fun endedPid(): Long = ProcessBuilder("cmd", "/c", "exit").start().apply { waitFor() }.pid()

    private fun startedWith(): String? {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            if (started.isFile) runCatching { started.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    /** The script is Windows'; elsewhere these tests have nothing to run. */
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

    @Test
    fun `after a good install the new version starts`() {
        if (!windows) return
        assertEquals(0, runScript("cmd.exe"))
        assertEquals("started", startedWith())
    }

    @Test
    fun `after a failed install the old version starts and says so`() {
        if (!windows) return
        assertEquals(2, runScript("where.exe"))
        assertEquals("started ${MsiInstaller.UPDATE_FAILED}", startedWith())
    }

    @Test
    fun `without a known app the script ends with the installer's code and starts nothing`() {
        if (!windows) return
        assertEquals(2, runScript("where.exe", withApp = false))
        Thread.sleep(500)
        assertFalse(started.exists())
    }

    @Test
    fun `the installer waits until the app has closed`() {
        if (!windows) return
        val running = ProcessBuilder("ping", "-n", "4", "127.0.0.1").redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assertEquals(0, runScript("cmd.exe", waitFor = running.pid()))
        assertFalse(running.isAlive, "it ran after the app's process ended")
        assertEquals("started", startedWith())
    }
}
