package com.trackrecord.data.deezer

import com.trackrecord.data.musicbrainz.NetLog
import com.trackrecord.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Follows redirects with OkHttp (share links such as link.deezer.com/s/… lead to the playlist URL). */
class OkHttpLinkResolver : LinkResolver {
    private val client = Http.client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun finalUrl(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "TrackRecord").build()).execute().use { it.request.url.toString() }
        }.onFailure { NetLog.w("link $url failed: $it") }.getOrNull()?.also { NetLog.i("link $url -> $it") }
    }
}
