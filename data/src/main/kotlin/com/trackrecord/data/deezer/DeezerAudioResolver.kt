package com.trackrecord.data.deezer

import com.trackrecord.data.musicbrainz.NetLog
import com.trackrecord.data.sources.AudioRef
import com.trackrecord.data.sources.AudioResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plays local files as they are and Deezer tracks as their 30-second preview. Preview URLs are signed
 * and expire (about 15 minutes, `exp=` in the URL), so they are fetched right before playback and
 * reused only while at least [marginMs] of their lifetime is left (SPEC v2, Playback). Nothing but the
 * URL is kept: the audio itself is only streamed.
 */
class DeezerAudioResolver(
    private val client: DeezerClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val marginMs: Long = 3 * 60_000,
) : AudioResolver {
    private data class Fresh(val url: String, val expiresAt: Long)

    private val mutex = Mutex()
    private val urls = HashMap<Long, Fresh>()

    override suspend fun playableUri(uri: String): String? = when (val ref = AudioRef.parse(uri)) {
        is AudioRef.Local -> ref.uri
        is AudioRef.Deezer -> preview(ref.trackId)
    }

    override suspend fun prefetch(uri: String) {
        (AudioRef.parse(uri) as? AudioRef.Deezer)?.let { preview(it.trackId) }
    }

    private suspend fun preview(trackId: Long): String? {
        mutex.withLock { urls[trackId]?.takeIf { it.expiresAt - marginMs > now() }?.let { return it.url } }
        val url = try {
            client.track(trackId)?.takeIf { it.playable }?.preview
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            NetLog.w("preview $trackId failed: $e")
            null
        } ?: return null
        mutex.withLock { urls[trackId] = Fresh(url, expiryOf(url) ?: (now() + DEFAULT_LIFETIME_MS)) }
        return url
    }

    companion object {
        private const val DEFAULT_LIFETIME_MS = 10 * 60_000L
        private val exp = Regex("""exp=(\d+)""")

        /** The expiry (epoch ms) written into a signed preview URL, if any. */
        fun expiryOf(url: String): Long? = exp.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
    }
}
