package com.trackrecord.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.trackrecord.engine.ClosestYearPreset
import com.trackrecord.engine.ClosestYearSettings
import com.trackrecord.engine.CommonSettings
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.TimelinePreset
import com.trackrecord.engine.TimelineSettings
import com.trackrecord.engine.YearBalance
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
    /** Preset a fresh setup starts from: Classic unless changed in Settings. */
    val defaultPreset: TimelinePreset = TimelinePreset.CLASSIC,
    /** Closest Year preset a fresh setup starts from; a removed preset reads as Closest (round 36). */
    val defaultClosestPreset: ClosestYearPreset = ClosestYearPreset.CLOSEST,
    val musicBrainzEnabled: Boolean = true,
    /** How much each game's shuffle evens out the years; Gentle unless changed (YEAR_BALANCE.md). Only set here. */
    val yearBalance: YearBalance = YearBalance.GENTLE,
    /** Contact email for the MusicBrainz User-Agent; lookups stay off while it is blank. */
    val contactEmail: String = "",
    /** AcoustID application key for fingerprint matching; off while blank (DECISIONS.md D-075). */
    val acoustIdKey: String = "",
    val autoPlay: Boolean = true,
    val startOffsetSeconds: Int = 0,
    /** 20 s unless changed. */
    val clipLimitSeconds: Int? = DEFAULT_CLIP_LIMIT_S,
    /** Download the first release's cover from the Cover Art Archive for MusicBrainz matches. */
    val loadCovers: Boolean = true,
    /** Show covers at all (reveal, timeline cards, song details); off = plain colours. */
    val showCovers: Boolean = true,
    /** Language tag; only "en" in v1. */
    val language: String = "en",
    /** App look. */
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
    /** Auto-reveal despite outstanding remote vetoes, after [minVetoTimeSeconds]. */
    val autoRevealDespiteRemote: Boolean = false,
    /** Auto-reveal also in Song Order games where everyone may call out song and artist. */
    val autoRevealWhenAllName: Boolean = false,
    /** Internet players keep one link for every game, also after a restart. */
    val keepInternetLink: Boolean = false,
    /**
     * Tapping the chosen gap again locks in or places the veto. The host's choice
     * is the guests' default; each guest phone can change it.
     */
    val confirmByTappingTwice: Boolean = false,
    /**
     * Which year counts when the tags and MusicBrainz disagree. A new name in
     * feedback round 32, so the default goes back to MusicBrainz.
     */
    val sourcesDisagree: YearPreference = YearPreference.MUSICBRAINZ,
    /** Rules the user saved under a name in New game. */
    val customPresets: List<CustomPreset> = emptyList(),
    /** The library's explanation was shown once; it opens by itself on the first visit. */
    val libraryInfoSeen: Boolean = false,
    /** Ask GitHub for a newer release at start. */
    val checkForUpdates: Boolean = true,
    /** The invite sheet explained Windows' firewall prompt once (PLATFORMS.md phase 7). */
    val networkAccessHintSeen: Boolean = false,
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
            yearBalance = yearBalance,
        ),
    )

    /**
     * Within what Settings and New game allow, for settings from elsewhere (a library import; REVIEW-3 S-19): numbers
     * are moved into their ranges, and saved rules that New game would refuse are left out.
     */
    fun sanitized(): AppSettings = copy(
        startOffsetSeconds = startOffsetSeconds.coerceIn(0, MAX_START_OFFSET_S),
        clipLimitSeconds = clipLimitSeconds?.coerceIn(MIN_CLIP_LIMIT_S, MAX_CLIP_LIMIT_S),
        minVetoTimeSeconds = minVetoTimeSeconds.coerceIn(0, MAX_VETO_TIME_S),
        cornerRadiusDp = cornerRadiusDp.coerceIn(0, MAX_CORNER_RADIUS),
        buttonCornerRadiusDp = buttonCornerRadiusDp.coerceIn(0, FULL_RADIUS),
        customPresets = customPresets.filter { p ->
            (p.timeline == null) != (p.closestYear == null) && p.timeline?.validate().isNullOrEmpty() && p.closestYear?.validate().isNullOrEmpty()
        },
    )
}

/** Rules saved under a name: Timeline rules or Closest Year rules, never both. */
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

/** The largest corner radius the Settings slider offers. */
const val MAX_CORNER_RADIUS = 24

/** The longest minimum veto time the Settings slider offers. */
const val MAX_VETO_TIME_S = 20

/** The ranges `CommonSettings.validate` allows. */
const val MAX_START_OFFSET_S = 60
const val MIN_CLIP_LIMIT_S = 10
const val MAX_CLIP_LIMIT_S = 120

/** The clip limit of a fresh install, in seconds. */
const val DEFAULT_CLIP_LIMIT_S = 20

@Serializable
/** The app's look (DECISIONS.md D-172): the illustrated Low Poly (the default, listed first), Light or Dark. */
enum class ThemeChoice { LOW_POLY, LIGHT, DARK }

/**
 * Stores app settings and the last used game setup (as opaque JSON owned by the app layer) in [store], the
 * platform's preferences file (PLATFORMS.md phase 3).
 */
class SettingsRepository(private val store: DataStore<Preferences>) {
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

    companion object {
        /** The preferences file's name; on Android in the app's datastore folder, as always. */
        const val FILE_NAME = "settings"

        private val APP = stringPreferencesKey("app_settings")
        private val LAST_SETUP = stringPreferencesKey("last_setup")
    }
}
