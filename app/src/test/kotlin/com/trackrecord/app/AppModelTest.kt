package com.trackrecord.app

import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.game.GameSession
import com.trackrecord.app.game.Initials
import com.trackrecord.app.game.SongInfo
import com.trackrecord.app.newgame.SetupMemory
import com.trackrecord.engine.Card
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.ReduceResult
import com.trackrecord.engine.TimelineSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AppModelTest {
    @Test
    fun `initials from names`() {
        assertEquals("BM", Initials.from("Ben Miller"))
        assertEquals("AN", Initials.from("Anna"))
        assertEquals("AS", Initials.from("Anna-Lena Schmidt"))
        assertEquals("FE", Initials.from("  felix  "))
        assertEquals("?", Initials.from(""))
        assertEquals("ÖZ", Initials.from("Özil"))
    }

    @Test
    fun `saved game round trip includes players and song info`() {
        val players = listOf(GamePlayer("g-1", "Anna", "AN", 0), GamePlayer("p-7", "Kathy", "KA", 3, "/data/photo.png", 7))
        val songs = (1L..6L).map { Card(it, 1960 + it.toInt()) }
        val state = (GameEngine.reduce(null, GameAction.StartGame(GameMode.TIMELINE, players.map { it.id }, songs, GameSettings(), 5, 2026)) as ReduceResult.Accepted).state
        val drawn = (GameEngine.reduce(state, GameAction.Draw) as ReduceResult.Accepted).state
        val session = GameSession(players, drawn, songs.associate { it.songId to SongInfo(it.songId, "content://x/${it.songId}", "T${it.songId}", "A", null, 1000, false, it.year, null) })
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertEquals(session, json.decodeFromString(GameSession.serializer(), json.encodeToString(GameSession.serializer(), session)))
    }

    @Test
    fun `setup memory round trip`() {
        val setup = SetupMemory(GameMode.CLOSEST_YEAR, listOf(GamePlayer("g-1", "Anna", "AN", 0)), GameSettings(timeline = TimelineSettings.CLASSIC))
        val json = Json { encodeDefaults = true }
        assertEquals(setup, json.decodeFromString(SetupMemory.serializer(), json.encodeToString(SetupMemory.serializer(), setup)))
    }
}
