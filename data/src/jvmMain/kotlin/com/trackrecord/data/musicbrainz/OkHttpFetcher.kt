package com.trackrecord.data.musicbrainz

import com.trackrecord.data.net.Http
import com.trackrecord.data.net.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.FormBody
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Real HTTP with a User-Agent; MusicBrainz requires "TrackRecord/<version> ( <contact email> )".
 * [tag] prefixes the log lines (logcat tag TrackRecordNet).
 */
class OkHttpFetcher(private val tag: String = "mb", private val userAgent: suspend () -> String) : HttpFetcher {
    private val client = Http.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun get(url: String): HttpResponse =
        execute(url, Request.Builder().url(url))

    override suspend fun post(url: String, form: Map<String, String>): HttpResponse {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return execute(url, Request.Builder().url(url).post(body))
    }

    private suspend fun execute(url: String, builder: Request.Builder): HttpResponse {
        val ua = userAgent()
        // Combined searches show how many parts they join (the line is cut short).
        val terms = url.split("%20OR%20").size
        val label = "$tag ${url.substringAfter("/ws/2/").substringAfter("api.acoustid.org/").take(90)}" + if (terms > 1) " [$terms terms]" else ""
        val request = builder.header("User-Agent", ua).header("Accept", "application/json").build()
        val started = System.currentTimeMillis()
        // Cancelling ends the request under way at once, not after the answer (D-324; REVIEW-3 B-14).
        return try {
            client.newCall(request).await { HttpResponse(it.code, it.body.string()) }
                .also { NetLog.i("$label -> ${it.code} in ${System.currentTimeMillis() - started} ms") }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            NetLog.w("$label failed after ${System.currentTimeMillis() - started} ms: $e")
            throw e
        }
    }
}
