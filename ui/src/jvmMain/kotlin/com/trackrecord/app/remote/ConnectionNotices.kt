package com.trackrecord.app.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** A guest's device connected for [player], or lost its connection ([connected] false; feedback round 60). */
data class ConnectionNotice(val player: String, val connected: Boolean)

/**
 * The host's popups about guests' devices (feedback round 60), from the players' connection states: a player whose
 * device connects, and one whose device stays away for [graceMs], so a reload or a short gap shows nothing. A player
 * the host disconnected, or one given up for another, is no longer claimed: no popup.
 */
internal class ConnectionNotices(private val scope: CoroutineScope, private val graceMs: Long = LOST_GRACE_MS) {
    private val _events = MutableSharedFlow<ConnectionNotice>(extraBufferCapacity = 16)
    val events: SharedFlow<ConnectionNotice> = _events.asSharedFlow()

    private var connected = emptyMap<String, Boolean>()
    private val pendingLoss = HashMap<String, Job>()

    /** The players claimed from a guest's device, and whether it is connected now ([com.trackrecord.remote.RemoteClients.claimed]). */
    @Synchronized
    fun update(claimed: Map<String, Boolean>) {
        for ((player, on) in claimed) {
            val was = connected[player] == true
            if (on && !was) {
                // Back within the grace time: nothing was shown, so nothing to say.
                val gap = pendingLoss.remove(player)
                if (gap != null) gap.cancel() else _events.tryEmit(ConnectionNotice(player, connected = true))
            } else if (!on && was) {
                pendingLoss[player] = scope.launch {
                    delay(graceMs)
                    val lost = synchronized(this@ConnectionNotices) { pendingLoss.remove(player) != null }
                    if (lost) _events.emit(ConnectionNotice(player, connected = false))
                }
            }
        }
        for (player in connected.keys - claimed.keys) pendingLoss.remove(player)?.cancel()
        connected = claimed
    }

    /** Remote play stopped: nothing pending. */
    @Synchronized
    fun reset() {
        pendingLoss.values.forEach { it.cancel() }
        pendingLoss.clear()
        connected = emptyMap()
    }

    companion object {
        const val LOST_GRACE_MS = 5_000L
    }
}
