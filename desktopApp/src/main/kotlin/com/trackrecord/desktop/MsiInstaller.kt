package com.trackrecord.desktop

import com.trackrecord.app.platform.UpdateInstaller
import com.trackrecord.data.update.PackageDownload
import com.trackrecord.data.update.ReleasePackage
import java.io.File

/**
 * The desktop's update (PLATFORMS.md phase 8): the release's MSI is downloaded into [folder], and the app closes so
 * Windows Installer can replace it. No wizard (feedback round 63): a small script waits for the app to close, installs
 * with a progress bar only into the folder the app is in ([installedExe]'s), and starts the new version. [start] runs
 * the script (a parameter for tests).
 */
class MsiInstaller(
    private val folder: File,
    private val quit: () -> Unit,
    private val installedExe: File? = installedExe(),
    private val start: (UpdateRun) -> Unit = { run ->
        // Java starts it without a console window, so only the installer's progress shows.
        ProcessBuilder("cmd", "/c", run.script.path).apply { environment().putAll(run.environment) }.start()
    },
) : UpdateInstaller {
    private val download = PackageDownload(folder)

    override suspend fun install(pkg: ReleasePackage, onProgress: (Float) -> Unit): Boolean {
        val file = download.download(pkg, onProgress) ?: return false
        Log.i("Update: installing ${file.name} into ${installedExe?.parent ?: "the default folder"}")
        val started = runCatching { start(prepare(file)) }
        if (started.isFailure) {
            Log.w("Update: the installer didn't start: ${started.exceptionOrNull()}")
            return false
        }
        quit()
        return true
    }

    /** The script and what it needs to know, as environment variables (any path then works, with spaces or `&`). */
    fun prepare(msi: File): UpdateRun {
        val script = File(folder, "update.cmd").apply { writeText(SCRIPT) }
        val environment = buildMap {
            put("TR_MSI", msi.absolutePath)
            put("TR_PID", ProcessHandle.current().pid().toString())
            installedExe?.let {
                put("TR_DIR", it.parentFile.absolutePath)
                put("TR_EXE", it.absolutePath)
            }
        }
        return UpdateRun(script, environment)
    }

    companion object {
        /**
         * Waits until the app (TR_PID) has closed, as its files are replaced; runs the installer with a progress bar
         * only (`/passive`), into the app's folder when known (`INSTALLDIR`, else the default one), without a restart
         * of Windows, and waits for it (a script waits for a windowed program by itself); then starts the app again
         * when it went well (0, or 3010: done, Windows wants a restart).
         */
        internal val SCRIPT = """
            @echo off
            :wait
            tasklist /fi "PID eq %TR_PID%" /nh | find "%TR_PID%" >nul && (ping -n 2 127.0.0.1 >nul & goto wait)
            if defined TR_DIR (
                msiexec /i "%TR_MSI%" /passive /norestart INSTALLDIR="%TR_DIR%"
            ) else (
                msiexec /i "%TR_MSI%" /passive /norestart
            )
            set result=%errorlevel%
            if not defined TR_EXE exit /b %result%
            if %result% equ 0 start "" "%TR_EXE%"
            if %result% equ 3010 start "" "%TR_EXE%"
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        /** The running app's TrackRecord.exe, when it runs installed (not from Gradle). */
        fun installedExe(): File? {
            val path = System.getProperty("jpackage.app-path") ?: ProcessHandle.current().info().command().orElse(null) ?: return null
            return File(path).takeIf { it.name.equals("TrackRecord.exe", ignoreCase = true) && it.isFile }
        }
    }
}

/** The update script to run, with its environment. */
data class UpdateRun(val script: File, val environment: Map<String, String>)
