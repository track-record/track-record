package com.trackrecord.app.settings

import com.trackrecord.app.AppConfig
import com.trackrecord.app.platform.UpdateInstaller
import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.data.update.AppRelease
import com.trackrecord.data.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface UpdateStatus {
    data object Unknown : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    /** [progress]: the package's download, while the app installs it itself; [failed]: that download or install failed. */
    data class Available(val release: AppRelease, val progress: Float? = null, val failed: Boolean = false) : UpdateStatus
    data object Failed : UpdateStatus
}

/**
 * Whether a newer release is out (feedback round 47): checked once at start unless turned off in Settings, and
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
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _dismissed = MutableStateFlow(false)

    /** "Later" on Home: not asked again until the app starts anew. */
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    init {
        // Debug builds don't ask (feedback round 47): the release is another app beside them.
        if (AppConfig.current.updateCheck) scope.launch { if (settings.appSettings.first().checkForUpdates) check() }
    }

    fun check() {
        if (_status.value == UpdateStatus.Checking) return
        _status.value = UpdateStatus.Checking
        scope.launch {
            val release = latest()
            _status.value = when {
                release == null -> UpdateStatus.Failed
                release.versionCode > currentVersion -> UpdateStatus.Available(release)
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
        _status.value = available.copy(progress = 0f, failed = false)
        scope.launch {
            val started = installer.install(pkg) { done -> _status.value = available.copy(progress = done) }
            if (!started) _status.value = available.copy(failed = true)
        }
    }
}
