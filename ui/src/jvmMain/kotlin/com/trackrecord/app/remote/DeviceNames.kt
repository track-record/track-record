package com.trackrecord.app.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.device_computer
import com.trackrecord.app.resources.device_phone
import com.trackrecord.app.resources.device_tablet
import com.trackrecord.app.resources.device_unknown
import com.trackrecord.remote.DeviceKinds
import org.jetbrains.compose.resources.stringResource

/** "phone", "tablet" or "computer": what a guest plays on, as its page told (DESIGN-REVIEW WD-01). */
@Composable
fun deviceName(kind: String?): String = stringResource(
    when (kind) {
        DeviceKinds.PHONE -> Res.string.device_phone
        DeviceKinds.TABLET -> Res.string.device_tablet
        DeviceKinds.COMPUTER -> Res.string.device_computer
        else -> Res.string.device_unknown
    },
)

/** What [playerId] plays on, while remote play runs; null when not known. */
@Composable
fun remoteDevice(playerId: String): String? {
    val state by LocalAppContainer.current.remote.state.collectAsStateWithLifecycle()
    return (state as? RemoteState.On)?.clients?.devices?.get(playerId)
}
