package com.trackrecord.engine

import kotlinx.serialization.json.Json

/** JSON form of a game, used for the saved-game row. */
object GameJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(state: GameState): String = json.encodeToString(GameState.serializer(), state)

    fun decode(text: String): GameState = json.decodeFromString(GameState.serializer(), text)

    fun encodeSettings(settings: GameSettings): String = json.encodeToString(GameSettings.serializer(), settings)

    fun decodeSettings(text: String): GameSettings = json.decodeFromString(GameSettings.serializer(), text)
}
