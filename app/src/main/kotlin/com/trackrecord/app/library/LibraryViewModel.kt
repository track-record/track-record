package com.trackrecord.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackrecord.data.db.FolderEntity
import com.trackrecord.data.db.PlaylistWithCount
import com.trackrecord.data.library.LibraryQuery
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.library.LibrarySong
import com.trackrecord.data.library.ScanReport
import com.trackrecord.data.library.ScanState
import com.trackrecord.data.library.filtered
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import com.trackrecord.data.playlists.BuiltInPlaylists
import com.trackrecord.data.playlists.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loaded: Boolean = false,
    val folders: List<FolderEntity> = emptyList(),
    /** Songs matching the current query. */
    val songs: List<LibrarySong> = emptyList(),
    val totalSongs: Int = 0,
    val playableSongs: Int = 0,
    val missingYearSongs: Int = 0,
    val query: LibraryQuery = LibraryQuery(),
    val scan: ScanState = ScanState.Idle,
    /** Songs without a tag year whose MusicBrainz match waits for confirmation. */
    val pending: List<LibrarySong> = emptyList(),
    /** The user's playlists (editable, offered in "Add to" / "Remove from"). */
    val playlists: List<PlaylistWithCount> = emptyList(),
    /** Built-in playlists ("Local files"); shown with "All", never edited. */
    val builtIns: List<PlaylistWithCount> = emptyList(),
    val selection: Set<Long> = emptySet(),
    /** Playlists holding at least one selected song ("Remove from" offers only these). */
    val selectionPlaylists: List<PlaylistWithCount> = emptyList(),
    /** A selected song is on "Excluded songs" (feedback round 28). */
    val selectionExcluded: Boolean = false,
) {
    val selecting: Boolean get() = selection.isNotEmpty()

    /** Every listed song is selected: "Select all listed" turns into "Deselect all". */
    val allListedSelected: Boolean get() = songs.isNotEmpty() && songs.all { it.id in selection }
}

private data class LibraryData(val folders: List<FolderEntity>, val songs: List<LibrarySong>, val scan: ScanState)

class LibraryViewModel(
    private val repo: LibraryRepository,
    private val musicBrainz: MusicBrainzLookup,
    private val playlistRepo: PlaylistRepository,
) : ViewModel() {
    private val _query = MutableStateFlow(LibraryQuery())

    /** The search and filters as typed, straight away (the list follows a moment later). */
    val query: StateFlow<LibraryQuery> = _query.asStateFlow()
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val _reports = MutableSharedFlow<ScanReport>(extraBufferCapacity = 1)
    val reports: SharedFlow<ScanReport> = _reports

    private val data = combine(repo.folders, repo.songs, repo.scanState) { folders, songs, scan -> LibraryData(folders, songs, scan) }

    val state: StateFlow<LibraryUiState> = combine(data, _query, selection, playlistRepo.withBuiltIns) { d, q, sel, all ->
        val (builtIns, playlists) = all.partition { BuiltInPlaylists.isBuiltIn(it.id) }
        val onSelected = d.songs.filter { it.id in sel }.flatMapTo(HashSet()) { it.playlistIds }
        LibraryUiState(
            loaded = true,
            folders = d.folders,
            songs = d.songs.filtered(q),
            totalSongs = d.songs.size,
            playableSongs = d.songs.count { it.playable },
            missingYearSongs = d.songs.count { it.missingYear },
            query = q,
            scan = d.scan,
            playlists = playlists,
            builtIns = builtIns,
            selection = sel,
            selectionPlaylists = playlists.filter { it.id in onSelected },
            selectionExcluded = BuiltInPlaylists.EXCLUDED in onSelected,
        )
    }
        // Filtering thousands of songs on every keystroke or tap: not on the main thread (feedback round 29).
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // The library rescans whenever its screen opens; unchanged files are skipped.
        rescan()
    }

    fun updateQuery(transform: (LibraryQuery) -> LibraryQuery) = _query.update(transform)

    fun resetFilters() = _query.update { LibraryQuery(text = it.text) }

    fun rescan() {
        viewModelScope.launch { _reports.tryEmit(repo.rescanAll()) }
    }

    fun addFolder(treeUri: String) {
        viewModelScope.launch { _reports.tryEmit(repo.addFolder(treeUri)) }
    }

    fun removeFolder(folderId: Long) {
        viewModelScope.launch { repo.removeFolder(folderId) }
    }

    // ---- Selection ----

    fun toggleSelection(songId: Long) = selection.update { if (songId in it) it - songId else it + songId }

    /** Selects every song currently listed (after search and filters). */
    fun selectAllListed() = selection.update { it + state.value.songs.map { s -> s.id } }

    fun clearSelection() = selection.update { emptySet() }

    fun addSelectionTo(playlistId: Long) {
        val ids = selection.value
        viewModelScope.launch { playlistRepo.addSongs(playlistId, ids) }
        clearSelection()
    }

    fun addSelectionToNew(name: String) {
        val ids = selection.value
        viewModelScope.launch { playlistRepo.addSongs(playlistRepo.create(name), ids) }
        clearSelection()
    }

    fun removeSelectionFrom(playlistId: Long, then: () -> Unit = {}) {
        val ids = selection.value
        viewModelScope.launch {
            playlistRepo.removeSongs(playlistId, ids)
            then()
        }
        clearSelection()
    }

    // ---- Playlists ----

    fun createPlaylist(name: String) = viewModelScope.launch { playlistRepo.create(name) }

    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { playlistRepo.rename(id, name) }

    fun deletePlaylist(id: Long, afterwards: () -> Unit = {}) {
        _query.update { if (it.playlistId == id) it.copy(playlistId = null) else it }
        viewModelScope.launch {
            playlistRepo.delete(id)
            afterwards()
        }
    }
}
