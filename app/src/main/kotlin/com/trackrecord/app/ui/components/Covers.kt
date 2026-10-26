package com.trackrecord.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.trackrecord.app.appContainer
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
import java.io.File

/**
 * Cover for a song: the MusicBrainz first-release cover if downloaded, else the embedded cover, else
 * none (DECISIONS.md D-068). Decoded, downsampled bitmaps are kept in a small memory cache.
 */
class CoverLoader(
    private val context: Context,
    private val library: LibraryRepository,
    private val musicBrainz: MusicBrainzLookup,
    private val coverArt: CoverArtService,
) {
    private class Holder(val bitmap: ImageBitmap?)

    private val cache = LruCache<Long, Holder>(80)
    private val _version = MutableStateFlow(0)
    /** Bumped when a new cover arrives, so visible cards reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun invalidate(songId: Long) {
        cache.remove(songId)
        _version.update { it + 1 }
    }

    suspend fun load(songId: Long): ImageBitmap? {
        cache.get(songId)?.let { return it.bitmap }
        val bitmap = withContext(Dispatchers.IO) {
            val song = library.songEntity(songId) ?: return@withContext null
            coverArt.coverFile(musicBrainz.cached(song))?.let { decode(it) }
                ?: if (song.hasCover) embedded(song.uri) else null
        }
        cache.put(songId, Holder(bitmap))
        return bitmap
    }

    private fun decode(file: File): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth) })?.asImageBitmap()
    }.getOrNull()

    private fun embedded(uri: String): ImageBitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(uri))
            r.embeddedPicture?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth) })?.asImageBitmap()
            }
        } finally {
            r.release()
        }
    }.getOrNull()

    private fun sample(width: Int): Int {
        var s = 1
        while (width / (s * 2) >= TARGET_PX) s *= 2
        return s
    }

    private companion object {
        const val TARGET_PX = 400
    }
}

/** The song's cover, reloading when a new cover was downloaded; null while loading or if none. */
@Composable
fun rememberSongCover(songId: Long?): ImageBitmap? {
    val container = LocalContext.current.appContainer
    val loader = container.covers
    val version by loader.version.collectAsState()
    // "Show cover art" off in Settings: no covers anywhere (feedback round 6).
    val show by remember { container.settings.appSettings.map { it.showCovers } }.collectAsState(initial = true)
    val cover by produceState<ImageBitmap?>(null, songId, version, show) {
        value = if (show) songId?.let { loader.load(it) } else null
    }
    return cover
}
