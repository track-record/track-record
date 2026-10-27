package com.trackrecord.app.library

import com.trackrecord.data.deezer.DeezerImportException
import com.trackrecord.data.deezer.DeezerImportResult
import com.trackrecord.data.deezer.DeezerImporter
import com.trackrecord.data.lists.SongListParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DeezerImportState {
    data object Idle : DeezerImportState

    /** [total] is the number of new tracks whose details are being read (0 while reading the playlist). */
    data class Running(val done: Int, val total: Int) : DeezerImportState
    data class Done(val result: DeezerImportResult, val update: Boolean) : DeezerImportState

    /** Several song lists at once (an Exportify ZIP): one playlist each. */
    data class DoneMany(val results: List<DeezerImportResult>) : DeezerImportState
    data class Failed(val reason: DeezerImportException.Reason) : DeezerImportState
}

/** Runs Deezer playlist imports and updates app-wide, so they go on while the host leaves the library. */
class DeezerImports(private val importer: DeezerImporter) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<DeezerImportState>(DeezerImportState.Idle)
    val state: StateFlow<DeezerImportState> = _state.asStateFlow()

    /** An import is running; a new one now would be dropped. */
    val busy: Boolean get() = job?.isActive == true

    fun import(link: String) = run(update = false) { importer.import(link, ::progress) }

    fun update(playlistId: Long) = run(update = true) { importer.update(playlistId, ::progress) }

    /** A song list (CSV or text) read from a file; [name] becomes the playlist name. */
    fun importList(name: String, text: String) = run(update = false) { importer.importList(name, SongListParser.parse(text), ::progress) }

    /** Song lists as (name, text); several make one playlist each. A list without songs is skipped. */
    fun importLists(files: List<Pair<String, String>>) {
        if (files.size == 1) return importList(files[0].first, files[0].second)
        if (job?.isActive == true || files.isEmpty()) return
        _state.value = DeezerImportState.Running(0, 0)
        job = scope.launch {
            val results = mutableListOf<DeezerImportResult>()
            for ((name, text) in files) {
                try {
                    results += importer.importList(name, SongListParser.parse(text), ::progress)
                } catch (e: DeezerImportException) {
                    if (e.reason != DeezerImportException.Reason.EMPTY_LIST) {
                        _state.value = DeezerImportState.Failed(e.reason)
                        return@launch
                    }
                }
            }
            _state.value = DeezerImportState.DoneMany(results)
        }
    }

    /** The default Deezer playlists the library doesn't have yet (feedback round 48). */
    fun importDefaults() {
        if (job?.isActive == true) return
        _state.value = DeezerImportState.Running(0, 0)
        job = scope.launch {
            _state.value = try {
                DeezerImportState.DoneMany(importer.importDefaults(::progress))
            } catch (e: DeezerImportException) {
                DeezerImportState.Failed(e.reason)
            }
        }
    }

    /** After a playlist was deleted: drop streamed songs nothing uses any more. */
    fun cleanUp() {
        scope.launch { importer.removeUnused() }
    }

    /**
     * Stops the import or update (feedback round 48: no requests that can't be stopped). A song list keeps the songs
     * saved so far; a Deezer playlist is saved only when complete, so it stays as it was.
     */
    fun cancel() {
        job?.cancel()
        _state.value = DeezerImportState.Idle
    }

    fun dismiss() {
        if (_state.value !is DeezerImportState.Running) _state.value = DeezerImportState.Idle
    }

    private fun progress(done: Int, total: Int) {
        _state.value = DeezerImportState.Running(done, total)
    }

    private fun run(update: Boolean, block: suspend () -> DeezerImportResult) {
        if (job?.isActive == true) return
        _state.value = DeezerImportState.Running(0, 0)
        job = scope.launch {
            _state.value = try {
                DeezerImportState.Done(block(), update)
            } catch (e: DeezerImportException) {
                DeezerImportState.Failed(e.reason)
            }
        }
    }
}
