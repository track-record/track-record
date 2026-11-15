package com.trackrecord.app.settings

import com.trackrecord.app.AppConfig
import com.trackrecord.app.platform.UpdateInstaller
import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.data.update.AppRelease
import com.trackrecord.data.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

sealed interface UpdateStatus {
    data object Unknown : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    /**
     * [progress]: the package's download, while the app installs it itself; [failed]: that download or the installer's
     * start failed; [installFailed]: the installer ran before this start and failed (the desktop; REVIEW-3 B-20).
     */
    data class Available(val release: AppRelease, val progress: Float? = null, val failed: Boolean = false, val installFailed: Boolean = false) : UpdateStatus
    data object Failed : UpdateStatus
}

/**
 * Whether a newer release is out: checked once at start unless turned off in Settings, and
 * on request. Home asks to update once per app start; Settings keeps showing it. "Update" fetches the platform's
 * package ([packageExtension]): the [installer] downloads and installs it (the desktop), else the browser does.
 */
class AppUpdates(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val packageExtension: String,
    private val installer: UpdateInstaller?,
    /** The newest release on GitHub, or null when it can't be reached. */
    private val latest: suspend () -> AppRelease? = UpdateChecker()::latest,
    private val currentVersion: Int = AppConfig.current.versionCode,
    /** The last update's installer failed: say so with the release found, even with the check at start turned off. */
    private val installFailed: Boolean = false,
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _dismissed = MutableStateFlow(false)
    private var download: Job? = null

    /** "Later" on Home: not asked again until the app starts anew. */
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    init {
        // Debug builds don't ask: the release is another app beside them.
        if (AppConfig.current.updateCheck) scope.launch { if (installFailed || settings.appSettings.first().checkForUpdates) check() }
    }

    /** Asks GitHub again; not while it is asking or while an update downloads (REVIEW-3 B-15). */
    fun check() {
        val now = _status.value
        if (now == UpdateStatus.Checking || (now is UpdateStatus.Available && now.progress != null)) return
        _status.value = UpdateStatus.Checking
        scope.launch {
            val release = latest()
            _status.value = when {
                release == null -> UpdateStatus.Failed
                release.versionCode > currentVersion -> UpdateStatus.Available(release, installFailed = installFailed)
                else -> UpdateStatus.UpToDate
            }
        }
    }

    fun dismiss() {
        _dismissed.value = true
    }

    /** Whether [update] installs [release] in the app, with its progress in [status]; else it hands over a link. */
    fun installsItself(release: AppRelease): Boolean = installer != null && release.packageFor(packageExtension) != null

    /**
     * Updates to the available release: the [installer] downloads and starts the platform's package, and the installer
     * then closes the app; without one, [open] gets the package's link for the browser, or the release's page.
     */
    fun update(open: (url: String) -> Unit) {
        val available = _status.value as? UpdateStatus.Available ?: return
        if (available.progress != null) return
        val pkg = available.release.packageFor(packageExtension)
        val installer = installer
        if (pkg == null || installer == null) {
            open(pkg?.url ?: available.release.pageUrl)
            return
        }
        _status.value = available.copy(progress = 0f, failed = false, installFailed = false)
        download = scope.launch {
            val job = coroutineContext.job
            // A late progress report from the download's thread doesn't undo a cancel.
            val started = installer.install(pkg) { done -> if (job.isActive) _status.value = available.copy(progress = done) }
            if (!started) _status.value = available.copy(failed = true)
        }
    }

    /** Stops the update's download at once (REVIEW-3 B-15); Update can start it again. */
    fun cancelDownload() {
        val available = _status.value as? UpdateStatus.Available ?: return
        download?.cancel()
        download = null
        _status.value = available.copy(progress = null, failed = false)
    }
}
