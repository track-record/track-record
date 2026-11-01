package com.trackrecord.app.settings

import com.trackrecord.app.AppConfig
import com.trackrecord.app.platform.FileAccess
import com.trackrecord.data.backup.BackupFormatException
import com.trackrecord.data.backup.BackupSource
import com.trackrecord.data.backup.ImportSummary
import com.trackrecord.data.backup.LibraryBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.zip.ZipInputStream

/** Where a library export or import stands (PLATFORMS.md section 5). */
sealed interface TransferState {
    data object Idle : TransferState

    /** A file to import was picked or opened with the app; waits for the host's OK. */
    data class Offered(val file: String, val name: String, val hasSettings: Boolean) : TransferState

    data class Running(val export: Boolean, val done: Int, val total: Int) : TransferState
    data object Exported : TransferState
    data class Imported(val summary: ImportSummary) : TransferState
    data class Failed(val export: Boolean, val unreadable: Boolean) : TransferState
}

/**
 * Settings › Backup and move: exports the library to a file the host picks and imports one, with progress and
 * Cancel. One at a time; an import runs in one transaction, so Cancel leaves the library as it was.
 */
class LibraryTransfer(
    private val files: FileAccess,
    private val backup: LibraryBackup,
    private val scope: CoroutineScope,
    /** Named in the export's manifest. */
    private val device: String,
) {
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()
    private var job: Job? = null

    /** The name the save dialog suggests. */
    fun suggestedName(): String = LibraryBackup.fileName(LocalDate.now().toString())

    fun export(file: String) = run(export = true) {
        val out = files.openWrite(file) ?: error("can't write $file")
        out.use { backup.export(it, BackupSource(AppConfig.current.versionName, device), withSettings = true, onProgress = progress(true)) }
        TransferState.Exported
    }

    /** A file to import: asks first, after checking it is an export. */
    fun offer(file: String) {
        if (_state.value is TransferState.Running) return
        scope.launch(Dispatchers.IO) {
            val settings = runCatching { files.openRead(file)?.use(backup::hasSettings) }
            _state.value = settings.getOrNull()?.let { TransferState.Offered(file, files.displayName(file) ?: file.substringAfterLast('/'), it) }
                ?: TransferState.Failed(export = false, unreadable = true)
        }
    }

    fun import(file: String, withSettings: Boolean) = run(export = false) {
        val input = files.openRead(file) ?: error("can't read $file")
        TransferState.Imported(input.use { backup.import(it, withSettings, progress(false)) })
    }

    fun cancel() {
        job?.cancel()
    }

    /** Closes the result or the question. */
    fun dismiss() {
        if (_state.value !is TransferState.Running) _state.value = TransferState.Idle
    }

    private fun progress(export: Boolean) = { done: Int, total: Int -> _state.value = TransferState.Running(export, done, total) }

    private fun run(export: Boolean, block: suspend () -> TransferState) {
        if (_state.value is TransferState.Running) return
        _state.value = TransferState.Running(export, 0, 0)
        job = scope.launch(Dispatchers.IO) {
            _state.value = try {
                block()
            } catch (e: CancellationException) {
                TransferState.Idle
            } catch (e: BackupFormatException) {
                TransferState.Failed(export, unreadable = true)
            } catch (e: Exception) {
                TransferState.Failed(export, unreadable = export.not() && e is java.util.zip.ZipException)
            }
        }
    }

    companion object {
        /** Whether [file] is a library export (its first entry is the manifest), not a song list. */
        fun isExport(files: FileAccess, file: String): Boolean = runCatching {
            files.openRead(file)?.use { ZipInputStream(it).nextEntry?.name == "manifest.json" }
        }.getOrNull() == true
    }
}
