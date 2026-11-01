package com.trackrecord.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackrecord.app.platform.PreviewPlayer
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.library.LibrarySong
import com.trackrecord.data.musicbrainz.LookupResult
import com.trackrecord.data.musicbrainz.LookupService
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import com.trackrecord.data.sources.AudioResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LookupUi {
    data object Idle : LookupUi
    data object Running : LookupUi
    data object NoMatch : LookupUi
    /** Lookups are switched off. */
    data object Off : LookupUi

    data object Offline : LookupUi

    /** [service] kept refusing: try again later (feedback round 47). */
    data class Refused(val service: LookupService) : LookupUi
}

class SongDetailViewModel(
    private val repo: LibraryRepository,
    private val musicBrainz: MusicBrainzLookup,
    private val player: PreviewPlayer,
    private val songId: Long,
    val currentYear: Int,
    private val audioResolver: AudioResolver = AudioResolver.LocalOnly,
) : ViewModel() {
    val song: StateFlow<LibrarySong?> = repo.song(songId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Library preview: a plain player without a media session, as the library is a host-only screen. */
    val previewing: StateFlow<Boolean> = player.playing

    fun setManualYear(year: Int) = viewModelScope.launch { repo.setManualYear(songId, year) }

    fun resetToAutomatic() = viewModelScope.launch { repo.setManualYear(songId, null) }

    private val _lookup = MutableStateFlow<LookupUi>(LookupUi.Idle)
    val lookup: StateFlow<LookupUi> = _lookup.asStateFlow()

    /**
     * "Look up": asks MusicBrainz now, ignoring the cache; always by fingerprint (and name) when an AcoustID
     * key works, else by name (feedback round 30).
     */
    fun lookUp() = viewModelScope.launch {
        val entity = repo.songEntity(songId) ?: return@launch
        _lookup.value = LookupUi.Running
        val byFingerprint = musicBrainz.currentVersion() == MusicBrainzLookup.LOOKUP_VERSION_FINGERPRINT
        val result = musicBrainz.lookupAll(listOf(entity), force = true, byFingerprint = byFingerprint)[entity.id] ?: LookupResult.Skipped
        _lookup.value = when (result) {
            is LookupResult.Match -> LookupUi.Idle
            LookupResult.NoMatch -> LookupUi.NoMatch
            LookupResult.Skipped -> LookupUi.Off
            LookupResult.Offline -> LookupUi.Offline
            is LookupResult.Unavailable -> LookupUi.Refused(result.service)
        }
    }

    fun setExcluded(excluded: Boolean) = viewModelScope.launch { repo.setExcluded(songId, excluded) }

    fun togglePreview() {
        val s = song.value ?: return
        if (player.playing.value) {
            player.pause()
        } else {
            viewModelScope.launch {
                val playable = audioResolver.playableUri(s.song.uri) ?: return@launch
                player.play(playable)
            }
        }
    }

    fun stopPreview() {
        player.pause()
    }

    override fun onCleared() {
        player.release()
    }
}
