package com.trackrecord.app.newgame

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackrecord.app.game.GameController
import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.game.PoolInfo
import com.trackrecord.app.game.StartResult
import com.trackrecord.app.players.asGamePlayer
import com.trackrecord.data.db.PlaylistWithCount
import com.trackrecord.data.db.ProfileEntity
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.playlists.PlaylistRepository
import com.trackrecord.data.profiles.ProfileRepository
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameSettings
import com.trackrecord.engine.RejectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

/** New game's steps in order: players, songs, mode, rules (feedback round 32). */
enum class SetupStep { PLAYERS, POOL, MODE, SETTINGS }

data class NewGameState(
    val step: SetupStep = SetupStep.PLAYERS,
    val mode: GameMode = GameMode.TIMELINE,
    val players: List<GamePlayer> = emptyList(),
    val settings: GameSettings = GameSettings(),
    val starting: Boolean = false,
    val error: RejectReason? = null,
) {
    val canAddPlayer: Boolean get() = players.size < GameEngine.MAX_PLAYERS
    val enoughPlayers: Boolean get() = players.size >= GameEngine.MIN_PLAYERS

    /** Songs a game needs to start (DECISIONS.md D-005). */
    val songsNeeded: Int
        get() = when (mode) {
            GameMode.TIMELINE -> players.size * settings.timeline.startingCards + 1
            GameMode.CLOSEST_YEAR -> 1
        }
}

/** Last used setup, remembered for the next "New game" across restarts. */
@Serializable
data class SetupMemory(val mode: GameMode, val players: List<GamePlayer>, val settings: GameSettings)

@OptIn(ExperimentalCoroutinesApi::class)
class NewGameViewModel(
    private val controller: GameController,
    library: LibraryRepository,
    private val setups: SetupStore,
    private val profileRepo: ProfileRepository,
    playlistRepo: PlaylistRepository,
) : ViewModel() {
    /** Built-in playlists ("Local files") first, then the user's. */
    val playlists: StateFlow<List<PlaylistWithCount>> = playlistRepo.withBuiltIns.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val profiles: StateFlow<List<ProfileEntity>> = profileRepo.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(NewGameState())
    val state: StateFlow<NewGameState> = _state.asStateFlow()

    init {
        // Guests on the remote page can pick their player while this setup is open (feedback round 32).
        viewModelScope.launch { _state.map { it.players }.distinctUntilChanged().collect(controller::setLobby) }
    }

    override fun onCleared() {
        controller.setLobby(null)
    }

    init {
        // Rules come from the last used setup, playback always from the Settings defaults (DECISIONS.md D-033, D-061).
        viewModelScope.launch {
            val last = setups.lastSetup()?.let { refreshProfiles(it) }
            val defaults = setups.defaultSettings()
            val existing = playlistRepo.playlists.first().map { it.id }.toSet()
            _state.update {
                if (last != null) {
                    val c = last.settings.common
                    val ids = c.playlistIds?.filter { id -> id in existing }?.takeIf { l -> l.isNotEmpty() }
                    val excluded = c.excludedPlaylistIds.filter { id -> id in existing }
                    // Timeline and Closest Year rules come from the Settings presets (D-067, D-138).
                    it.copy(
                        mode = last.mode,
                        players = last.players,
                        settings = last.settings.copy(
                            timeline = defaults.timeline,
                            closestYear = defaults.closestYear,
                            common = defaults.common.copy(playlistIds = ids, excludedPlaylistIds = excluded, yearFrom = c.yearFrom, yearTo = c.yearTo),
                        ),
                    )
                } else {
                    it.copy(settings = defaults)
                }
            }
        }
    }

    /** Back to the app defaults from Settings (keeps the pool choice). */
    fun resetToDefaults() = viewModelScope.launch {
        val defaults = setups.defaultSettings()
        _state.update { s -> s.copy(settings = defaults.copy(common = defaults.common.copy(playlistIds = s.settings.common.playlistIds, excludedPlaylistIds = s.settings.common.excludedPlaylistIds, yearFrom = s.settings.common.yearFrom, yearTo = s.settings.common.yearTo))) }
    }

    /** The pool's size, streamed songs and stats, worked out once per pool choice (off the main thread); null while counting. */
    val pool: StateFlow<PoolInfo?> = _state.map { it.settings }
        .distinctUntilChanged { a, b -> a.common == b.common }
        .mapLatest { controller.poolInfo(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setMode(mode: GameMode) = _state.update { it.copy(mode = mode) }

    fun goTo(step: SetupStep) = _state.update { it.copy(step = step, error = null) }

    fun savePlayer(existingId: String?, name: String, initials: String, colorIndex: Int) = _state.update { s ->
        val player = GamePlayer(
            id = existingId ?: "g-${UUID.randomUUID()}",
            name = name.trim(),
            initials = initials.trim().uppercase(),
            colorIndex = colorIndex,
        )
        if (existingId == null) s.copy(players = s.players + player)
        else s.copy(players = s.players.map { if (it.id == existingId) player.copy(photoPath = it.photoPath, profileId = it.profileId) else it })
    }

    /** Adds a saved profile as a player. Returns false if its color is already used in this game. */
    fun addProfile(profile: ProfileEntity): Boolean {
        val s = _state.value
        if (s.players.any { it.colorIndex == profile.colorIndex }) return false
        _state.update { it.copy(players = it.players + profile.asGamePlayer()) }
        return true
    }

    /** Adds a profile with a different color for this game only (its saved color is taken). */
    fun addProfileWithColor(profile: ProfileEntity, name: String, initials: String, colorIndex: Int) = _state.update {
        it.copy(players = it.players + profile.asGamePlayer().copy(name = name.trim(), initials = initials.trim().uppercase(), colorIndex = colorIndex))
    }

    /** Last-used players that are profiles pick up profile changes; deleted profiles drop out. */
    private suspend fun refreshProfiles(setup: SetupMemory): SetupMemory {
        val byId = profileRepo.all().associateBy { it.id }
        val players = setup.players.mapNotNull { p ->
            val id = p.profileId ?: return@mapNotNull p
            val profile = byId[id] ?: return@mapNotNull null
            p.copy(name = profile.name, initials = profile.initials, photoPath = profile.photoPath)
        }
        return setup.copy(players = players)
    }

    fun removePlayer(id: String) = _state.update { s -> s.copy(players = s.players.filterNot { it.id == id }) }

    fun movePlayer(from: Int, to: Int) = _state.update { s ->
        if (from !in s.players.indices || to !in s.players.indices) return@update s
        s.copy(players = s.players.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun shuffleSeats() = _state.update { s -> s.copy(players = s.players.shuffled()) }

    fun updateSettings(transform: (GameSettings) -> GameSettings) = _state.update { s -> s.copy(settings = transform(s.settings)) }

    fun start(onStarted: () -> Unit) {
        val s = _state.value
        if (s.starting) return
        _state.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            when (val r = controller.start(s.mode, s.players, s.settings)) {
                StartResult.Started -> {
                    setups.saveLastSetup(SetupMemory(s.mode, s.players, s.settings))
                    _state.update { it.copy(starting = false) }
                    onStarted()
                }
                is StartResult.Rejected -> _state.update { it.copy(starting = false, error = r.reason) }
            }
        }
    }
}
