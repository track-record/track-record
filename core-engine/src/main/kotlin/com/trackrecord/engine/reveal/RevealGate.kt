package com.trackrecord.engine.reveal

import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameState
import com.trackrecord.engine.Phase
import kotlinx.serialization.Serializable

/** When the app reveals a Timeline round by itself (SPEC v3; DECISIONS.md D-105). */
@Serializable
enum class AutoReveal(val delayMs: Long?) {
    OFF(null),
    INSTANT(0),
    AFTER_5S(5_000),
    AFTER_10S(10_000),
}

/**
 * What happened in the current vetoing phase that the engine doesn't track: when Lock in happened,
 * who tapped "Don't veto", who is picking a gap for a veto, and since when the auto-reveal condition
 * holds.
 */
data class VetoRound(
    val roundNumber: Int,
    val lockedAtMs: Long,
    val declined: Set<String> = emptySet(),
    val readySinceMs: Long? = null,
    /**
     * Players who tapped "Veto" and are picking a gap (feedback round 10), on a guest phone or on the
     * host's (feedback round 27).
     */
    val vetoing: Set<String> = emptySet(),
)

/** The host's Reveal button: locked for [lockedForMs], and auto-reveal due in [autoRevealInMs] (null: none). */
data class RevealStatus(
    /** Locked by the veto window for this long (0: not by the window). */
    val lockedForMs: Long,
    /** Remote players who could veto and haven't decided, in seat order. */
    val waitingFor: List<String>,
    val autoRevealInMs: Long?,
    /** Remote players who tapped "Don't veto" this round. */
    val declined: Set<String>,
    /** Players picking a gap for their veto: Reveal stays locked until they placed it, without a time limit. */
    val picking: List<String> = emptyList(),
) {
    val locked: Boolean get() = lockedForMs > 0 || picking.isNotEmpty()
}

/**
 * The veto window and auto-reveal (SPEC v3; DECISIONS.md D-104, D-105). Pure: the caller keeps the
 * [VetoRound], passes the time, and dispatches Reveal when [RevealStatus.autoRevealInMs] reaches 0.
 */
object RevealGate {
    /** Remote players who could still veto and haven't tapped "Don't veto". */
    fun waitingFor(state: GameState, remote: Set<String>, round: VetoRound): List<String> =
        GameEngine.vetoEligible(state).filter { it in remote && it !in round.declined }

    /**
     * Auto-reveal may happen: everyone who could still veto declined, on a guest phone or with the
     * host's "Don't veto" (feedback round 16); also true when nobody can veto. A veto placed on the
     * host's phone no longer stops it: there is no Undo veto any more (feedback round 27).
     *
     * With [ignoreRemoteAfterMs] ("Auto-reveal despite outstanding remote vetoes", feedback round 32),
     * remote players still undecided that long after Lock in no longer count; one picking a gap does.
     */
    fun ready(state: GameState, round: VetoRound, remote: Set<String> = emptySet(), nowMs: Long = 0, ignoreRemoteAfterMs: Long? = null): Boolean {
        val overdue = ignoreRemoteAfterMs != null && nowMs >= round.lockedAtMs + ignoreRemoteAfterMs
        return state.phase == Phase.VETOING && GameEngine.vetoEligible(state).all {
            it in round.declined || (overdue && it in remote && it !in round.vetoing)
        }
    }

    /** Updates [VetoRound.readySinceMs]: set when [ready] starts to hold, cleared when it stops. */
    fun advance(state: GameState, remote: Set<String>, round: VetoRound, nowMs: Long, ignoreRemoteAfterMs: Long? = null): VetoRound {
        val ready = ready(state, round, remote, nowMs, ignoreRemoteAfterMs)
        return when {
            ready && round.readySinceMs == null -> round.copy(readySinceMs = nowMs)
            !ready && round.readySinceMs != null -> round.copy(readySinceMs = null)
            else -> round
        }
    }

    fun status(
        state: GameState,
        remote: Set<String>,
        round: VetoRound,
        vetoWindowMs: Long,
        auto: AutoReveal,
        nowMs: Long,
        ignoreRemoteAfterMs: Long? = null,
    ): RevealStatus {
        val waiting = waitingFor(state, remote, round)
        // Also a player on the host's phone picking a gap: a co-host sees "Veto pending" (feedback round 27).
        val picking = GameEngine.vetoEligible(state).filter { it in round.vetoing && it !in round.declined }
        val lockedFor = if (waiting.isEmpty()) 0L else (round.lockedAtMs + vetoWindowMs - nowMs).coerceAtLeast(0L)
        val delay = auto.delayMs
        val since = round.readySinceMs
        val autoIn = if (delay != null && since != null && ready(state, round, remote, nowMs, ignoreRemoteAfterMs)) (since + delay - nowMs).coerceAtLeast(0L) else null
        return RevealStatus(lockedFor, waiting, autoIn, round.declined, picking)
    }
}
