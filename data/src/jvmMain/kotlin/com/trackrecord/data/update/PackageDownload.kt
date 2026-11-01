package com.trackrecord.data.update

import com.trackrecord.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads a release's package into [folder] (the desktop's update, PLATFORMS.md phase 8): first as a `.part` file,
 * checked against GitHub's SHA-256 where it gives one, then under the package's name.
 */
class PackageDownload(private val folder: File) {
    private val client by lazy { Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    /** The downloaded file, or null if the download failed or doesn't match; [onProgress] goes from 0 to 1. */
    suspend fun download(pkg: ReleasePackage, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        // GitHub's names have no folders; a name that does isn't followed.
        val name = File(pkg.name).name.takeIf { it == pkg.name && it.isNotBlank() } ?: return@withContext null
        val target = File(folder, name)
        val part = File(folder, "$name.part")
        val ok = runCatching {
            folder.mkdirs()
            client.newCall(Request.Builder().url(pkg.url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching false
                val total = response.body.contentLength()
                val sha = MessageDigest.getInstance("SHA-256")
                var done = 0L
                response.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            sha.update(buffer, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
                val hex = sha.digest().joinToString("") { "%02x".format(it) }
                pkg.sha256 == null || hex.equals(pkg.sha256, ignoreCase = true)
            }
        }.getOrDefault(false)
        if (!ok) {
            part.delete()
            return@withContext null
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        onProgress(1f)
        target
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
