package com.trackrecord.app.game

import com.trackrecord.engine.GameState
import com.trackrecord.engine.Phase
import com.trackrecord.engine.reveal.AutoReveal
import com.trackrecord.engine.reveal.RevealGate
import com.trackrecord.engine.reveal.RevealStatus
import com.trackrecord.engine.reveal.VetoRound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The two Settings values behind the veto window and auto-reveal. */
data class RevealSettings(
    val autoReveal: AutoReveal = AutoReveal.INSTANT,
    val vetoTimeSeconds: Int = 10,
    /** After the veto time, undecided remote players no longer hold auto-reveal (feedback round 32). */
    val despiteRemote: Boolean = false,
) {
    val vetoWindowMs: Long get() = vetoTimeSeconds * 1000L
    val ignoreRemoteAfterMs: Long? get() = vetoWindowMs.takeIf { despiteRemote }
}

/**
 * Runs the veto window and auto-reveal of the current Timeline round (SPEC v3; DECISIONS.md D-104,
 * D-105) on top of the pure [RevealGate]: keeps the [VetoRound], ticks while the round is in the
 * vetoing phase, and calls [reveal] when auto-reveal is due.
 */
class RevealTimer(
    private val scope: CoroutineScope,
    private val settings: StateFlow<RevealSettings>,
    private val remotePlayers: StateFlow<Set<String>>,
    private val clock: () -> Long,
    private val reveal: () -> Unit,
) {
    private var state: GameState? = null
    private var round: VetoRound? = null
    private var ticker: Job? = null

    private val _status = MutableStateFlow<RevealStatus?>(null)

    /** The Reveal button's state while vetoing; null in every other phase. */
    val status: StateFlow<RevealStatus?> = _status.asStateFlow()

    /** Follows every accepted game state. Returns true when the round should be revealed right away. */
    fun onState(new: GameState): Boolean {
        state = new
        val number = new.round?.number
        if (new.phase != Phase.VETOING || number == null) {
            stop()
            return false
        }
        if (round?.roundNumber != number) round = VetoRound(number, clock())
        val due = refresh()
        if (!due && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    if (refresh()) {
                        reveal()
                        break
                    }
                }
            }
        }
        return due
    }

    /** A player tapped "Veto" ([picking] true) and picks a gap, or stopped picking. */
    fun setPicking(playerId: String, picking: Boolean) {
        val r = round ?: return
        round = r.copy(vetoing = if (picking) r.vetoing + playerId else r.vetoing - playerId)
        if (refresh()) reveal()
    }

    /** [playerId] placed their veto: no longer picking. The new game state refreshes the status. */
    fun vetoPlaced(playerId: String) {
        val r = round ?: return
        round = r.copy(vetoing = r.vetoing - playerId)
    }

    /** A remote player tapped "Don't veto". */
    fun decline(playerId: String) {
        val r = round ?: return
        round = r.copy(declined = r.declined + playerId, vetoing = r.vetoing - playerId)
        if (refresh()) reveal()
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        round = null
        _status.value = null
    }

    /** Recomputes the status; true when auto-reveal is due now. */
    private fun refresh(): Boolean {
        val s = state ?: return false
        val now = clock()
        val remote = remotePlayers.value
        val config = settings.value
        val r = RevealGate.advance(s, remote, round ?: return false, now, config.ignoreRemoteAfterMs)
        round = r
        val status = RevealGate.status(s, remote, r, config.vetoWindowMs, config.autoReveal, now, config.ignoreRemoteAfterMs)
        _status.value = status
        return status.autoRevealInMs == 0L
    }

    private companion object {
        const val TICK_MS = 200L
    }
}
