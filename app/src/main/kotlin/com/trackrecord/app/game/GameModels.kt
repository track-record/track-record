package com.trackrecord.app.game

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.trackrecord.app.R
import com.trackrecord.data.library.LibrarySong
import com.trackrecord.data.sources.AudioRef
import com.trackrecord.engine.GameState
import kotlinx.serialization.Serializable

/** A player as the UI shows it. The engine only knows [id]. */
@Serializable
data class GamePlayer(
    val id: String,
    val name: String,
    val initials: String,
    val colorIndex: Int,
    val photoPath: String? = null,
    /** Saved profile id, or null for a guest. */
    val profileId: Long? = null,
)

/** What the game remembers about a pool song, frozen at game start (library edits apply next game). */
@Serializable
data class SongInfo(
    val songId: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val hasCover: Boolean,
    val baseYear: Int?,
    val musicBrainzYear: Int?,
    /** Library edit at game start; MusicBrainz results never override it. */
    val manualYear: Int? = null,
) {
    /** A Deezer track, played as its 30-second preview. */
    val streamed: Boolean get() = AudioRef.parse(uri) is AudioRef.Deezer

    /** The track's page on Deezer (opens the Deezer app when installed), for streamed songs. */
    val deezerUrl: String? get() = (AudioRef.parse(uri) as? AudioRef.Deezer)?.let { "https://www.deezer.com/track/${it.trackId}" }

    companion object {
        fun from(s: LibrarySong) = SongInfo(
            songId = s.id,
            uri = s.song.uri,
            title = s.song.title,
            artist = s.song.artist,
            album = s.song.album,
            durationMs = s.song.durationMs,
            hasCover = s.song.hasCover,
            baseYear = s.resolved.baseYear,
            musicBrainzYear = s.musicBrainzYear,
            manualYear = s.manualYear,
        )
    }
}

@Serializable
data class GameSession(
    val players: List<GamePlayer>,
    val state: GameState,
    val songs: Map<Long, SongInfo>,
    /** The names of the game's own playlists at game start, for the info sheet (feedback round 36). */
    val playlistNames: Map<Long, String> = emptyMap(),
) {
    fun player(id: String): GamePlayer = players.first { it.id == id }
    fun song(id: Long): SongInfo? = songs[id]

    /** Players still playing, in seat order; [players] also keeps those removed mid-game (feedback round 36). */
    val playing: List<GamePlayer> get() = state.players.map { player(it.id) }

    /** Players removed mid-game, in removal order. */
    val removed: List<GamePlayer> get() = state.removed.map { player(it.id) }
}

/** 12 colors picked for distinctness (Trubetskoy's list); always shown together with initials. */
object PlayerColors {
    val colors = listOf(
        Color(0xFFE6194B), Color(0xFF3CB44B), Color(0xFFFFE119), Color(0xFF4363D8),
        Color(0xFFF58231), Color(0xFF911EB4), Color(0xFF42D4F4), Color(0xFFF032E6),
        Color(0xFFBFEF45), Color(0xFFFABED4), Color(0xFF469990), Color(0xFF9A6324),
    )
    val names = listOf(
        R.string.color_red, R.string.color_green, R.string.color_yellow, R.string.color_blue,
        R.string.color_orange, R.string.color_purple, R.string.color_cyan, R.string.color_magenta,
        R.string.color_lime, R.string.color_pink, R.string.color_teal, R.string.color_brown,
    )

    fun color(index: Int): Color = colors[index.mod(colors.size)]

    fun onColor(index: Int): Color = if (color(index).luminance() > 0.4f) Color.Black else Color.White
}

object Initials {
    /** "Anna Maria" → "AM", "Felix" → "FE", "" → "?"; at most 3 characters. */
    fun from(name: String): String {
        val words = name.trim().split(Regex("""[\s\-_]+""")).filter { it.isNotEmpty() }
        val raw = when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2)
            else -> "${words.first().first()}${words.last().first()}"
        }
        return raw.uppercase().take(3)
    }
}
