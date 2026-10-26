package com.trackrecord.app.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.remote.DeviceKinds

/** "phone", "tablet" or "computer": what a guest plays on, as its page told (DESIGN-REVIEW WD-01). */
@Composable
fun deviceName(kind: String?): String = stringResource(
    when (kind) {
        DeviceKinds.PHONE -> R.string.device_phone
        DeviceKinds.TABLET -> R.string.device_tablet
        DeviceKinds.COMPUTER -> R.string.device_computer
        else -> R.string.device_unknown
    },
)

/** What [playerId] plays on, while remote play runs; null when not known. */
@Composable
fun remoteDevice(playerId: String): String? {
    val state by LocalContext.current.appContainer.remote.state.collectAsStateWithLifecycle()
    return (state as? RemoteState.On)?.clients?.devices?.get(playerId)
}
