package com.trackrecord.app.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.RejectReason
import com.trackrecord.playback.AudioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Screen-side state for a running game; the game itself lives in [GameController]. */
class GameViewModel(private val controller: GameController) : ViewModel() {
    val session: StateFlow<GameSession?> = controller.session
    val audio: StateFlow<AudioState> = controller.audio.state
    val rejections: SharedFlow<RejectReason> = controller.rejections

    /** Timeline vetoing: Reveal locked for remote players, auto-reveal countdown (SPEC v3). */
    val revealStatus = controller.revealStatus

    /** Players controlled from a guest's phone. */
    val remotePlayers = controller.remotePlayers

    /** Timeline vetoing: the player who tapped their name and now picks a gap. */
    private val _vetoer = MutableStateFlow<String?>(null)
    val vetoer: StateFlow<String?> = _vetoer.asStateFlow()

    /** The gap marked for the current vetoer: Confirm places it, or with "tap twice" a second tap (DESIGN-REVIEW IN-01). */
    private val _pendingVeto = MutableStateFlow<Int?>(null)
    val pendingVeto: StateFlow<Int?> = _pendingVeto.asStateFlow()

    fun dispatch(action: GameAction) {
        controller.dispatch(action)
        if (action !is GameAction.PlaceVeto) setVetoer(null)
        _pendingVeto.value = null
    }

    fun selectVetoer(id: String?) {
        setVetoer(if (_vetoer.value == id) null else id)
        _pendingVeto.value = null
    }

    /** Who picks a gap on this phone; Reveal waits for them like for a guest phone (feedback round 27). */
    private fun setVetoer(id: String?) {
        val previous = _vetoer.value
        if (previous == id) return
        _vetoer.value = id
        previous?.let { controller.setVetoPicking(it, false) }
        id?.let { controller.setVetoPicking(it, true) }
    }

    /**
     * A tap on a free gap while picking a veto: marks it, as on guest phones (DESIGN-REVIEW IN-01). With
     * [twice], tapping the marked gap again places the veto; otherwise Confirm does ([confirmVeto]).
     */
    fun placeVeto(slot: Int, twice: Boolean = false) {
        if (_vetoer.value == null) return
        if (twice && _pendingVeto.value == slot) confirmVeto() else _pendingVeto.value = slot
    }

    /** Places the marked veto (Confirm). */
    fun confirmVeto() {
        val id = _vetoer.value ?: return
        val slot = _pendingVeto.value ?: return
        _pendingVeto.value = null
        if (controller.dispatch(GameAction.PlaceVeto(id, slot))) setVetoer(null)
    }

    /** Stops picking a gap for a veto (Cancel). */
    fun cancelVeto() {
        setVetoer(null)
        _pendingVeto.value = null
    }

    fun correctYear(year: Int) = controller.correctYear(year)

    /** "Don't veto" for the one player on the host's phone (feedback round 16). */
    fun declineVeto(playerId: String) {
        setVetoer(null)
        _pendingVeto.value = null
        controller.declineVeto(playerId)
    }

    fun togglePlay() = controller.audio.togglePlay()

    fun replay() = controller.audio.replay()

    fun seekTo(positionMs: Long) = controller.audio.seekTo(positionMs)

    fun playFullSong() = viewModelScope.launch { controller.playFullSong() }

    /** v2: the current streamed song can't be played. */
    val unplayable = controller.unplayable
    val replacedThisRound = controller.replacedThisRound

    fun replaceUnplayable() = controller.replaceUnplayable()

    fun playAgain() = viewModelScope.launch { controller.playAgain() }

    fun endGame() = controller.endGame()

    fun pauseAudio() = controller.audio.pause()
}
