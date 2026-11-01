package com.trackrecord.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.platform.PlatformImages
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.musicbrainz.CoverArtService
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Cover for a song: the MusicBrainz first-release cover if downloaded, else the embedded cover, else
 * none (DECISIONS.md D-068). Decoded, downsampled bitmaps are kept in a small memory cache.
 */
class CoverLoader(
    private val images: PlatformImages,
    private val library: LibraryRepository,
    private val musicBrainz: MusicBrainzLookup,
    private val coverArt: CoverArtService,
) {
    private class Holder(val bitmap: ImageBitmap?)

    private val cache = object : LinkedHashMap<Long, Holder>(CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Holder>?) = size > CACHE
    }
    private val _version = MutableStateFlow(0)

    /** Bumped when a new cover arrives, so visible cards reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun invalidate(songId: Long) {
        synchronized(cache) { cache.remove(songId) }
        _version.update { it + 1 }
    }

    suspend fun load(songId: Long): ImageBitmap? {
        synchronized(cache) { cache[songId] }?.let { return it.bitmap }
        val bitmap = withContext(Dispatchers.IO) {
            val song = library.songEntity(songId) ?: return@withContext null
            val bytes = coverArt.coverFile(musicBrainz.cached(song))?.let { runCatching { it.readBytes() }.getOrNull() }
                ?: if (song.hasCover) images.embeddedCover(song.uri) else null
            bytes?.let { images.decode(it, TARGET_PX) }
        }
        synchronized(cache) { cache[songId] = Holder(bitmap) }
        return bitmap
    }

    private companion object {
        const val TARGET_PX = 400
        const val CACHE = 80
    }
}

/** The song's cover, reloading when a new cover was downloaded; null while loading or if none. */
@Composable
fun rememberSongCover(songId: Long?): ImageBitmap? {
    val container = LocalAppContainer.current
    val loader = container.covers
    val version by loader.version.collectAsState()
    // "Show cover art" off in Settings: no covers anywhere (feedback round 6).
    val show by remember { container.settings.appSettings.map { it.showCovers } }.collectAsState(initial = true)
    val cover by produceState<ImageBitmap?>(null, songId, version, show) {
        value = if (show) songId?.let { loader.load(it) } else null
    }
    return cover
}
