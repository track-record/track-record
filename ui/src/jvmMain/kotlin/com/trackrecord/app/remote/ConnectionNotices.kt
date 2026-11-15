package com.trackrecord.app.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** A guest's device connected for [player], or lost its connection ([connected] false). */
data class ConnectionNotice(val player: String, val connected: Boolean)

/**
 * The host's popups about guests' devices, from the players' connection states: a player whose
 * device connects, and one whose device stays away for [graceMs], so a reload or a short gap shows nothing. A player
 * the host disconnected, or one given up for another, is no longer claimed: no popup. A lost connection while nothing
 * shows the popups (the host on another screen) is kept for [takeMissed] until the player is back (REVIEW-3 B-22).
 */
internal class ConnectionNotices(private val scope: CoroutineScope, private val graceMs: Long = LOST_GRACE_MS) {
    private val _events = MutableSharedFlow<ConnectionNotice>(extraBufferCapacity = 16)
    val events: SharedFlow<ConnectionNotice> = _events.asSharedFlow()

    private var connected = emptyMap<String, Boolean>()
    private val pendingLoss = HashMap<String, Job>()
    private val missed = LinkedHashSet<String>()

    /** The players claimed from a guest's device, and whether it is connected now ([com.trackrecord.remote.RemoteClients.claimed]). */
    @Synchronized
    fun update(claimed: Map<String, Boolean>) {
        for ((player, on) in claimed) {
            val was = connected[player] == true
            if (on && !was) {
                missed -= player
                // Back within the grace time: nothing was shown, so nothing to say.
                val gap = pendingLoss.remove(player)
                if (gap != null) gap.cancel() else _events.tryEmit(ConnectionNotice(player, connected = true))
            } else if (!on && was) {
                pendingLoss[player] = scope.launch {
                    delay(graceMs)
                    val lost = synchronized(this@ConnectionNotices) {
                        val due = pendingLoss.remove(player) != null
                        if (due && _events.subscriptionCount.value == 0) missed += player
                        due && player !in missed
                    }
                    if (lost) _events.emit(ConnectionNotice(player, connected = false))
                }
            }
        }
        for (player in connected.keys - claimed.keys) {
            pendingLoss.remove(player)?.cancel()
            missed -= player
        }
        connected = claimed
    }

    /** The lost connections nobody was shown, of players still away; each is handed out once. */
    @Synchronized
    fun takeMissed(): List<ConnectionNotice> = missed.map { ConnectionNotice(it, connected = false) }.also { missed.clear() }

    /** Remote play stopped: nothing pending. */
    @Synchronized
    fun reset() {
        pendingLoss.values.forEach { it.cancel() }
        pendingLoss.clear()
        missed.clear()
        connected = emptyMap()
    }

    companion object {
        const val LOST_GRACE_MS = 5_000L
    }
}
