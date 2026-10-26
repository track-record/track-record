package com.trackrecord.data.update

import com.trackrecord.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A published release: [versionCode] is the last part of its tag ("v0.4.112" → 112); [apkUrl] its APK, if attached. */
data class AppRelease(val versionCode: Int, val versionName: String, val pageUrl: String, val apkUrl: String?)

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
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String)

    companion object {
        const val REPO = "track-record/track-record"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): AppRelease? {
            val release = runCatching { json.decodeFromString(Release.serializer(), text) }.getOrNull() ?: return null
            if (release.draft || release.prerelease) return null
            val name = release.tag.removePrefix("v")
            val code = name.substringAfterLast('.').toIntOrNull() ?: return null
            return AppRelease(code, name, release.htmlUrl, release.assets.firstOrNull { it.name.endsWith(".apk") }?.url)
        }
    }
}
