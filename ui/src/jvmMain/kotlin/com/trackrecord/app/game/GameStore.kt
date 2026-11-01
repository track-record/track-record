package com.trackrecord.app.game

import com.trackrecord.data.db.SavedGameDao
import com.trackrecord.data.db.SavedGameEntity
import com.trackrecord.engine.Phase
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The running game in the database (DECISIONS.md D-167, D-269). The game is written after every action;
 * its song list (title, artist and album of every pool song, most of a save's size) only when it
 * changed. Call [write] from one thread at a time.
 */
class GameStore(private val dao: SavedGameDao, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The song list last written, by identity: the session keeps the same map until songs change. */
    @Volatile private var savedSongs: Map<Long, SongInfo>? = null

    /** The saved game, or null; one that can't be read any more is removed. */
    suspend fun read(): GameSession? {
        val saved = dao.get() ?: return null
        val session = runCatching {
            val game = json.decodeFromString(GameSession.serializer(), saved.json)
            saved.songsJson?.let { game.copy(songs = json.decodeFromString(SONGS, it)) } ?: game
        }.getOrNull()
        if (session == null) dao.clear()
        return session
    }

    /** Saves [s]; a finished or ended game (null) is removed, so "Continue" disappears. */
    suspend fun write(s: GameSession?) {
        if (s == null || s.state.phase == Phase.GAME_OVER) {
            dao.clear()
            savedSongs = null
            return
        }
        val game = json.encodeToString(GameSession.serializer(), s.copy(songs = emptyMap()))
        val now = clock()
        if (s.songs === savedSongs && dao.saveGame(game, now) == 1) return
        dao.save(SavedGameEntity(json = game, songsJson = json.encodeToString(SONGS, s.songs), updatedAt = now))
        savedSongs = s.songs
    }

    private companion object {
        val SONGS = MapSerializer(Long.serializer(), SongInfo.serializer())
    }
}
