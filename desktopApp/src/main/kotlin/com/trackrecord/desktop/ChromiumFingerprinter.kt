package com.trackrecord.desktop

import com.trackrecord.data.db.SongEntity
import com.trackrecord.data.musicbrainz.Chromaprint
import com.trackrecord.data.musicbrainz.Fingerprinter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * AcoustID fingerprints on the desktop (feedback round 55): Chromium decodes the song (desktop/decoder.js; AAC through
 * [AacToWav]) to mono at Chromaprint's rate, and the Kotlin [Chromaprint] fingerprints the first [seconds], as `fpcalc`
 * and the Android app do. One song at a time: the decoded audio is large.
 */
class ChromiumFingerprinter(private val chromium: Chromium, private val seconds: Int = 120) : Fingerprinter {
    private val pending = ConcurrentHashMap<Int, Decoding>()
    private val ids = AtomicInteger()
    private val one = Mutex()

    /** The decoder page, opened the first time a song is fingerprinted. */
    private val port by lazy { PagePort("decoder", ::received).also { chromium.open("desktop/decoder.html", it) } }

    private class Decoding {
        val parts = StringBuilder()
        val done = CompletableDeferred<ShortArray?>()
    }

    override suspend fun fingerprint(song: SongEntity): String? {
        val file = runCatching { File(URI(song.uri)) }.getOrNull()?.takeIf { it.isFile } ?: return null
        val samples = one.withLock { decode(file) } ?: return null
        return withContext(Dispatchers.Default) { Chromaprint.fingerprint(samples).takeIf { samples.size >= Chromaprint.SAMPLE_RATE } }
    }

    /** The first [seconds] of [file], mono at Chromaprint's rate; null if Chromium can't decode it. */
    suspend fun decode(file: File): ShortArray? {
        val id = ids.incrementAndGet()
        val decoding = Decoding()
        pending[id] = decoding
        val address = chromium.publish(file)
        try {
            port.send(
                buildJsonObject {
                    put("t", "decode")
                    put("id", id)
                    put("src", address)
                    put("rate", Chromaprint.SAMPLE_RATE)
                    put("seconds", seconds)
                }.toString(),
            )
            return withTimeoutOrNull(TIMEOUT_MS) { decoding.done.await() }
        } finally {
            pending.remove(id)
            chromium.unpublish(address)
        }
    }

    private fun received(text: String) {
        val m = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        val decoding = m["id"]?.jsonPrimitive?.int?.let(pending::get) ?: return
        when (m["t"]?.jsonPrimitive?.content) {
            "failed" -> decoding.done.complete(null)
            "pcm" -> {
                decoding.parts.append(m.getValue("data").jsonPrimitive.content)
                if (m.getValue("last").jsonPrimitive.boolean) {
                    val bytes = Base64.getDecoder().decode(decoding.parts.toString())
                    val samples = ShortArray(bytes.size / 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                    decoding.done.complete(samples)
                }
            }
        }
    }

    private companion object {
        /** A long song decodes in a few seconds; a stuck one isn't waited for. */
        const val TIMEOUT_MS = 60_000L
    }
}
