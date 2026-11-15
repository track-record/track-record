package com.trackrecord.data.deezer

import com.trackrecord.data.musicbrainz.NetLog
import com.trackrecord.data.net.Http
import com.trackrecord.data.net.await
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Follows redirects with OkHttp (share links such as link.deezer.com/s/… lead to the playlist URL). */
class OkHttpLinkResolver : LinkResolver {
    private val client = Http.client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun finalUrl(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", "TrackRecord").build()).await { it.request.url.toString() }
            .also { NetLog.i("link $url -> $it") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NetLog.w("link $url failed: $e")
        null
    }
}
