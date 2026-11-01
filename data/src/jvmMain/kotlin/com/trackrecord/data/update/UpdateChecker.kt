package com.trackrecord.data.update

import com.trackrecord.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A published release: [versionCode] is the last part of its tag ("v0.4.112" → 112); [packages] its attached files. */
data class AppRelease(val versionCode: Int, val versionName: String, val pageUrl: String, val packages: List<ReleasePackage> = emptyList()) {
    /** The attached package a platform installs, by its file extension (".apk", ".msi"), if there is one. */
    fun packageFor(extension: String): ReleasePackage? = packages.firstOrNull { it.name.endsWith(extension, ignoreCase = true) }
}

/** A file attached to a release; [sha256] (hex) as GitHub reports it, where it does. */
data class ReleasePackage(val name: String, val url: String, val sha256: String?)

/**
 * Asks GitHub for the newest release of the public repo (feedback round 47). Internet play needs the app and
 * the guest page, which always has the newest version, to speak the same protocol, so an outdated app is told.
 */
class UpdateChecker(private val repo: String = REPO) {
    private val client by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    /** The newest release, or null when GitHub can't be reached or has none. */
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { r -> if (r.isSuccessful) parse(r.body.string()) else null }
        }.getOrNull()
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val htmlUrl: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String, val digest: String? = null)

    companion object {
        const val REPO = "track-record/track-record"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): AppRelease? {
            val release = runCatching { json.decodeFromString(Release.serializer(), text) }.getOrNull() ?: return null
            if (release.draft || release.prerelease) return null
            val name = release.tag.removePrefix("v")
            val code = name.substringAfterLast('.').toIntOrNull() ?: return null
            val packages = release.assets.map { ReleasePackage(it.name, it.url, it.digest?.removePrefix("sha256:")?.takeIf { d -> d != it.digest }) }
            return AppRelease(code, name, release.htmlUrl, packages)
        }
    }
}
