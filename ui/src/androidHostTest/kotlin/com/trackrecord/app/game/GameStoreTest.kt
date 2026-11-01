package com.trackrecord.app.game

import com.trackrecord.data.db.SavedGameDao
import com.trackrecord.data.db.SavedGameEntity
import com.trackrecord.engine.Card
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.Phase
import com.trackrecord.engine.ReduceResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Saving and restoring the running game (REVIEW.md P-01, T-03; DECISIONS.md D-269). */
class GameStoreTest {
    private class FakeDao : SavedGameDao {
        var row: SavedGameEntity? = null
        var fullSaves = 0
        var gameSaves = 0

        override suspend fun get() = row

        override suspend fun save(game: SavedGameEntity) {
            fullSaves++
            row = game
        }

        override suspend fun saveGame(json: String, updatedAt: Long): Int {
            val r = row ?: return 0
            gameSaves++
            row = r.copy(json = json, updatedAt = updatedAt)
            return 1
        }

        override suspend fun clear() {
            row = null
        }
    }

    private val dao = FakeDao()
    private val store = GameStore(dao, clock = { 1L })

    private fun song(id: Long) = SongInfo(id, "content://song/$id", "Title $id", "Artist", "Album", 200_000, false, 1980 + id.toInt(), null)

    private val session: GameSession = run {
        val start = GameEngine.reduce(
            null,
            GameAction.StartGame(GameMode.TIMELINE, listOf("a", "b"), (1L..6L).map { Card(it, 1980 + it.toInt()) }, GameSettings(), seed = 1, currentYear = 2026),
        ) as ReduceResult.Accepted
        val players = listOf(GamePlayer("a", "Anna", "AN", 0), GamePlayer("b", "Ben", "BE", 1))
        GameSession(players, start.state, (1L..6L).associateWith(::song), mapOf(7L to "Party"))
    }

    @Test
    fun `a game comes back as it was saved`() = runTest {
        store.write(session)
        assertEquals(session, store.read())
    }

    @Test
    fun `the song list is written only when it changes`() = runTest {
        store.write(session)
        val next = session.copy(state = (GameEngine.reduce(session.state, GameAction.Draw) as ReduceResult.Accepted).state)
        store.write(next)
        store.write(next.copy(state = next.state.copy(roundsPlayed = 5)))
        assertEquals(1, dao.fullSaves)
        assertEquals(2, dao.gameSaves)
        assertEquals(Phase.PLACING, store.read()!!.state.phase)
        assertEquals(session.songs, store.read()!!.songs)

        // A song found mid-game: a new list, written with the game.
        val found = next.copy(songs = next.songs + (9L to song(9)))
        store.write(found)
        assertEquals(2, dao.fullSaves)
        assertEquals(found.songs, store.read()!!.songs)
    }

    @Test
    fun `a game saved before the split is read with its songs`() = runTest {
        val json = Json { encodeDefaults = true }
        dao.row = SavedGameEntity(json = json.encodeToString(GameSession.serializer(), session), updatedAt = 1)
        assertEquals(session, store.read())
    }

    @Test
    fun `a finished game or one that can't be read is removed`() = runTest {
        store.write(session)
        store.write(null)
        assertNull(dao.row)
        dao.row = SavedGameEntity(json = "{not json", updatedAt = 1)
        assertNull(store.read())
        assertNull(dao.row)
        store.write(session)
        assertNotNull(dao.row)
        store.write(session.copy(state = session.state.copy(phase = Phase.GAME_OVER)))
        assertNull(dao.row)
    }
}
