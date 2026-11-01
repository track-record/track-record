package com.trackrecord.desktop

import com.trackrecord.app.platform.UpdateInstaller
import com.trackrecord.data.update.PackageDownload
import com.trackrecord.data.update.ReleasePackage
import java.io.File

/**
 * The desktop's update (PLATFORMS.md phase 8): the release's MSI is downloaded into [folder] and started, and the app
 * closes so Windows Installer can replace it. [start] runs the installer (a parameter for tests).
 */
class MsiInstaller(
    folder: File,
    private val quit: () -> Unit,
    private val start: (File) -> Unit = { ProcessBuilder("msiexec", "/i", it.path).start() },
) : UpdateInstaller {
    private val download = PackageDownload(folder)

    override suspend fun install(pkg: ReleasePackage, onProgress: (Float) -> Unit): Boolean {
        val file = download.download(pkg, onProgress) ?: return false
        Log.i("Update: starting ${file.name}")
        if (runCatching { start(file) }.onFailure { Log.w("Update: the installer didn't start: $it") }.isFailure) return false
        quit()
        return true
    }
}
