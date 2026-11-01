package com.trackrecord.data.musicbrainz

import com.trackrecord.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
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
        // Combined searches show how many parts they join (feedback round 48: the line is cut short).
        val terms = url.split("%20OR%20").size
        val label = "$tag ${url.substringAfter("/ws/2/").substringAfter("api.acoustid.org/").take(90)}" + if (terms > 1) " [$terms terms]" else ""
        return withContext(Dispatchers.IO) {
            val request = builder.header("User-Agent", ua).header("Accept", "application/json").build()
            val started = System.currentTimeMillis()
            val call = client.newCall(request)
            // A cancelled lookup ends at once instead of after the answer (feedback round 48: Cancel took seconds).
            val cancelling = coroutineContext.job.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { HttpResponse(it.code, it.body.string()) }
                    .also { NetLog.i("$label -> ${it.code} in ${System.currentTimeMillis() - started} ms") }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                NetLog.w("$label failed after ${System.currentTimeMillis() - started} ms: $e")
                throw e
            } finally {
                cancelling.dispose()
            }
        }
    }
}
