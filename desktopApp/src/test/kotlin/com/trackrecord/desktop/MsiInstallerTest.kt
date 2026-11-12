package com.trackrecord.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The update without the installer's wizard (feedback round 63). */
class MsiInstallerTest {
    @Test
    fun `the script gets the package, the app's folder and process, and installs with a progress bar only`() {
        val folder = Files.createTempDirectory("updates").toFile()
        val exe = File(folder, "My Apps & Co/TrackRecord/TrackRecord.exe").apply { parentFile.mkdirs(); writeText("") }
        val msi = File(folder, "TrackRecord-9.9.9.msi")
        val run = MsiInstaller(folder, quit = {}, installedExe = exe).prepare(msi)
        assertEquals(msi.absolutePath, run.environment["TR_MSI"])
        assertEquals(exe.parentFile.absolutePath, run.environment["TR_DIR"])
        assertEquals(exe.absolutePath, run.environment["TR_EXE"])
        assertEquals(ProcessHandle.current().pid().toString(), run.environment["TR_PID"])
        val script = run.script.readText()
        assertTrue("""msiexec /i "%TR_MSI%" /passive /norestart INSTALLDIR="%TR_DIR%"""" in script, script)
        assertTrue("\r\n" in script, "a Windows script")
        // Run from Gradle: no installed app, so the default folder and no restart.
        val dev = MsiInstaller(folder, quit = {}, installedExe = null).prepare(msi)
        assertEquals(setOf("TR_MSI", "TR_PID"), dev.environment.keys)
        folder.deleteRecursively()
    }
}
