package com.trackrecord.data.update

import com.trackrecord.data.net.Http
import com.trackrecord.data.net.await
import kotlinx.coroutines.CancellationException
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
 * checked against GitHub's SHA-256, then under the package's name. Only the public repo's own release files are
 * fetched ([trusted]), and only with a hash to check (REVIEW-3 S-17). Cancelling stops the download at once and
 * leaves nothing behind (REVIEW-3 B-15).
 */
class PackageDownload(private val folder: File, private val trusted: (ReleasePackage) -> Boolean = ::isOwnRelease) {
    private val client by lazy { Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    /** The downloaded file, or null if the download failed or doesn't match; [onProgress] goes from 0 to 1. */
    suspend fun download(pkg: ReleasePackage, onProgress: (Float) -> Unit): File? {
        val sha256 = pkg.sha256 ?: return null
        if (!trusted(pkg)) return null
        val target = File(folder, pkg.name)
        val part = File(folder, "${pkg.name}.part")
        val ok = try {
            withContext(Dispatchers.IO) { folder.mkdirs() }
            client.newCall(Request.Builder().url(pkg.url).build()).await { response ->
                // On OkHttp's thread: a cancel closes the socket, so the read below ends with an exception.
                try {
                    response.isSuccessful && sha256.equals(save(response.body.byteStream(), response.body.contentLength(), part, onProgress), ignoreCase = true)
                } catch (e: Exception) {
                    part.delete()
                    throw e
                }
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            false
        }
        return withContext(Dispatchers.IO) {
            if (!ok) {
                part.delete()
                return@withContext null
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            onProgress(1f)
            target
        }
    }

    /** Writes [input] to [part] with its progress against [total] bytes; returns the SHA-256 in hex. */
    private fun save(input: java.io.InputStream, total: Long, part: File, onProgress: (Float) -> Unit): String {
        val sha = MessageDigest.getInstance("SHA-256")
        var done = 0L
        input.use {
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
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BUFFER = 64 * 1024

        /** The app's packages are named like `TrackRecord-0.4.160.msi`: nothing a path or a script could misread. */
        private val PACKAGE_NAME = Regex("""TrackRecord-[0-9]+(\.[0-9]+)*\.(msi|apk)""")

        /** A file of the public repo's releases, named as the release tool names them (REVIEW-3 S-17). */
        fun isOwnRelease(pkg: ReleasePackage): Boolean =
            PACKAGE_NAME.matches(pkg.name) && pkg.url == "https://github.com/${UpdateChecker.REPO}/releases/download/v${pkg.name.removePrefix("TrackRecord-").substringBeforeLast('.')}/${pkg.name}"
    }
}
