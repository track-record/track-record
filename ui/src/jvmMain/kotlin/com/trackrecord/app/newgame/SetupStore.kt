package com.trackrecord.app.newgame

import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.engine.GameSettings
import kotlinx.serialization.json.Json

/** Last used setup and default settings, backed by DataStore. */
class SetupStore(private val settings: SettingsRepository) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun lastSetup(): SetupMemory? =
        settings.lastSetupJson()?.let { runCatching { json.decodeFromString(SetupMemory.serializer(), it) }.getOrNull() }
            ?.let { it.copy(settings = it.settings.withCostsWithinMax()) }

    suspend fun saveLastSetup(setup: SetupMemory) = settings.saveLastSetup(json.encodeToString(SetupMemory.serializer(), setup))

    suspend fun defaultSettings(): GameSettings = settings.current().defaultGameSettings()
}
