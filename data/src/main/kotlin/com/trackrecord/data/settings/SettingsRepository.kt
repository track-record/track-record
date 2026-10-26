package com.trackrecord.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trackrecord.engine.ClosestYearPreset
import com.trackrecord.engine.ClosestYearSettings
import com.trackrecord.engine.CommonSettings
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.TimelinePreset
import com.trackrecord.engine.TimelineSettings
import com.trackrecord.engine.reveal.AutoReveal
import com.trackrecord.engine.year.YearPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** App-wide defaults from the Settings screen. */
@Serializable
data class AppSettings(
    /** Preset a fresh setup starts from: Classic unless changed in Settings (feedback round 46). */
    val defaultPreset: TimelinePreset = TimelinePreset.CLASSIC,
    /** Closest Year preset a fresh setup starts from (feedback round 13); a removed preset reads as Closest (round 36). */
    val defaultClosestPreset: ClosestYearPreset = ClosestYearPreset.CLOSEST,
    val musicBrainzEnabled: Boolean = true,
    /** Contact email for the MusicBrainz User-Agent; lookups stay off while it is blank. */
    val contactEmail: String = "",
    /** AcoustID application key for fingerprint matching; off while blank (DECISIONS.md D-075). */
    val acoustIdKey: String = "",
    val autoPlay: Boolean = true,
    val startOffsetSeconds: Int = 0,
    val clipLimitSeconds: Int? = null,
    /** Download the first release's cover from the Cover Art Archive for MusicBrainz matches. */
    val loadCovers: Boolean = true,
    /** Show covers at all (reveal, timeline cards, song details); off = plain colours (feedback round 6). */
    val showCovers: Boolean = true,
    /** Language tag; only "en" in v1. */
    val language: String = "en",
    /** App look (feedback round 6). */
    val theme: ThemeChoice = ThemeChoice.LOW_POLY,
    /** Corner radius of buttons, cards, dialogs, fields (internal setting); 4 dp like the search box. */
    val cornerRadiusDp: Int = 4,
    /** Corner radius of buttons (internal setting, separate so pills can come back); [FULL_RADIUS] = pill. */
    val buttonCornerRadiusDp: Int = 4,
    /** Reveal by itself when nobody (or only remote players who declined) can veto (SPEC v3; D-105). */
    val autoReveal: AutoReveal = AutoReveal.INSTANT,
    /**
     * The minimum time undecided remote players keep Reveal locked after Lock in, 0–20 s (SPEC v3; D-104).
     * A new name in feedback round 32, so the new default of 10 s applies.
     */
    val minVetoTimeSeconds: Int = 10,
    /** Auto-reveal despite outstanding remote vetoes, after [minVetoTimeSeconds] (feedback round 32). */
    val autoRevealDespiteRemote: Boolean = false,
    /**
     * Tapping the chosen gap again locks in or places the veto (feedback round 11). The host's choice
     * is the guests' default; each guest phone can change it.
     */
    val confirmByTappingTwice: Boolean = false,
    /**
     * Which year counts when the tags and MusicBrainz disagree (feedback round 28). A new name in
     * feedback round 32, so the default goes back to MusicBrainz.
     */
    val sourcesDisagree: YearPreference = YearPreference.MUSICBRAINZ,
    /** Rules the user saved under a name in New game (feedback round 27). */
    val customPresets: List<CustomPreset> = emptyList(),
    /** The library's explanation was shown once; it opens by itself on the first visit (feedback round 45). */
    val libraryInfoSeen: Boolean = false,
    /** Ask GitHub for a newer release at start (feedback round 47). */
    val checkForUpdates: Boolean = true,
) {
    /** Game settings a fresh setup starts with. */
    fun defaultGameSettings(): GameSettings = GameSettings(
        timeline = defaultPreset.settings ?: TimelinePreset.CLASSIC.settings!!,
        closestYear = ClosestYearSettings().withPreset(defaultClosestPreset),
        common = CommonSettings(
            autoPlay = autoPlay,
            startOffsetSeconds = startOffsetSeconds,
            clipLimitSeconds = clipLimitSeconds,
            musicBrainzLookups = musicBrainzEnabled,
        ),
    )
}

/** Rules saved under a name (feedback round 27): Timeline rules or Closest Year rules, never both. */
@Serializable
data class CustomPreset(
    val name: String,
    val timeline: TimelineSettings? = null,
    val closestYear: ClosestYearSettings? = null,
) {
    val mode: GameMode get() = if (timeline != null) GameMode.TIMELINE else GameMode.CLOSEST_YEAR
}

/** Button radius value meaning "fully rounded" (pill). */
const val FULL_RADIUS = 25

@Serializable
/** The app's look (DECISIONS.md D-172): the illustrated Low Poly (the default, listed first), Light or Dark. */
enum class ThemeChoice { LOW_POLY, LIGHT, DARK }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Stores app settings and the last used game setup (as opaque JSON owned by the app layer). */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    // Unknown values (e.g. a removed theme) fall back to the default instead of losing every setting.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    val appSettings: Flow<AppSettings> = store.data.map { prefs ->
        prefs[APP]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
    }

    suspend fun current(): AppSettings = appSettings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val now = prefs[APP]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
            prefs[APP] = json.encodeToString(AppSettings.serializer(), transform(now))
        }
    }

    suspend fun lastSetupJson(): String? = store.data.first()[LAST_SETUP]

    suspend fun saveLastSetup(setupJson: String) {
        store.edit { it[LAST_SETUP] = setupJson }
    }

    private companion object {
        val APP = stringPreferencesKey("app_settings")
        val LAST_SETUP = stringPreferencesKey("last_setup")
    }
}
