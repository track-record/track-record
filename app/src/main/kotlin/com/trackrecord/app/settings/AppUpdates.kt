package com.trackrecord.app.settings

import com.trackrecord.app.BuildConfig
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
    data class Available(val release: AppRelease) : UpdateStatus
    data object Failed : UpdateStatus
}

/**
 * Whether a newer release is out (feedback round 47): checked once at start unless turned off in Settings, and
 * on request. Home asks to update once per app start; Settings keeps showing it.
 */
class AppUpdates(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val checker: UpdateChecker = UpdateChecker(),
    private val currentVersion: Int = BuildConfig.VERSION_CODE,
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _dismissed = MutableStateFlow(false)

    /** "Later" on Home: not asked again until the app starts anew. */
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    init {
        scope.launch { if (settings.appSettings.first().checkForUpdates) check() }
    }

    fun check() {
        if (_status.value == UpdateStatus.Checking) return
        _status.value = UpdateStatus.Checking
        scope.launch {
            val latest = checker.latest()
            _status.value = when {
                latest == null -> UpdateStatus.Failed
                latest.versionCode > currentVersion -> UpdateStatus.Available(latest)
                else -> UpdateStatus.UpToDate
            }
        }
    }

    fun dismiss() {
        _dismissed.value = true
    }
}
