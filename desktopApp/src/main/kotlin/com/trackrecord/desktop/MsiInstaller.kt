package com.trackrecord.desktop

import com.trackrecord.app.platform.UpdateInstaller
import com.trackrecord.data.update.PackageDownload
import com.trackrecord.data.update.ReleasePackage
import java.io.File

/**
 * The desktop's update (PLATFORMS.md phase 8): the release's MSI is downloaded into [folder] (the app's own, not
 * Downloads; REVIEW-3 B-20), and the app closes so Windows Installer can replace it. No wizard: a
 * small script waits for the app to close, installs with a progress bar only into the folder the app is in
 * ([installedExe]'s), and starts the new version, or the old one again with [UPDATE_FAILED] when the install failed.
 * [start] runs the script (a parameter for tests).
 */
class MsiInstaller(
    private val folder: File,
    private val quit: () -> Unit,
    private val installedExe: File? = installedExe(),
    private val download: PackageDownload = PackageDownload(folder),
    private val start: (UpdateRun) -> Unit = { run ->
        // Java starts it without a console window, so only the installer's progress shows.
        ProcessBuilder("cmd", "/c", run.script.path).apply { environment().putAll(run.environment) }.start()
    },
) : UpdateInstaller {
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

    /** Deletes the packages of earlier updates: the version they installed (or failed to) is the one running now. */
    fun removeOldPackages() {
        folder.listFiles { f -> f.name.endsWith(".msi", ignoreCase = true) || f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /** The script and what it needs to know, as environment variables (any path then works, with spaces or `&`). */
    fun prepare(msi: File): UpdateRun {
        folder.mkdirs()
        val script = File(folder, "update.cmd").apply { writeText(SCRIPT) }
        val environment = buildMap {
            put("TR_MSI", msi.absolutePath)
            // By its full path: a bare "msiexec" would be looked for in the current folder first.
            put("TR_MSIEXEC", File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\msiexec.exe").path)
            put("TR_PID", ProcessHandle.current().pid().toString())
            installedExe?.let {
                put("TR_DIR", it.parentFile.absolutePath)
                put("TR_EXE", it.absolutePath)
            }
        }
        return UpdateRun(script, environment)
    }

    companion object {
        /** The argument the update script starts the old version with when the install failed (REVIEW-3 B-20). */
        const val UPDATE_FAILED = "--update-failed"

        /**
         * Waits until the app (TR_PID) has closed, as its files are replaced, for about a minute at most (a ping takes
         * a second; Windows' own tools by their full paths, as another `find` may come first on the PATH); runs the installer with a progress bar only (`/passive`), into the app's folder when known
         * (`INSTALLDIR`, else the default one), without a restart of Windows, and waits for it (a script waits for a
         * windowed program by itself). Then it starts the app again: the new version when it went well (0, or 3010:
         * done, Windows wants a restart), else the old one with [UPDATE_FAILED], which says so (REVIEW-3 B-20).
         */
        internal val SCRIPT = """
            @echo off
            set sys=%SystemRoot%\System32
            set waited=0
            :wait
            "%sys%\tasklist.exe" /fi "PID eq %TR_PID%" /nh | "%sys%\find.exe" "%TR_PID%" >nul || goto install
            set /a waited+=1
            if %waited% geq 60 exit /b 1
            "%sys%\PING.EXE" -n 2 127.0.0.1 >nul
            goto wait
            :install
            if defined TR_DIR (
                "%TR_MSIEXEC%" /i "%TR_MSI%" /passive /norestart INSTALLDIR="%TR_DIR%"
            ) else (
                "%TR_MSIEXEC%" /i "%TR_MSI%" /passive /norestart
            )
            set result=%errorlevel%
            if not defined TR_EXE exit /b %result%
            if %result% equ 0 goto started
            if %result% equ 3010 goto started
            start "" /b "%TR_EXE%" $UPDATE_FAILED
            exit /b %result%
            :started
            start "" /b "%TR_EXE%"
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
