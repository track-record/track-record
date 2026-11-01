package com.trackrecord.app.game

import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.reject_active_cannot_veto
import com.trackrecord.app.resources.reject_all_guessed
import com.trackrecord.app.resources.reject_duplicate_guess
import com.trackrecord.app.resources.reject_duplicate_player
import com.trackrecord.app.resources.reject_feature_disabled
import com.trackrecord.app.resources.reject_guesses_incomplete
import com.trackrecord.app.resources.reject_invalid_settings
import com.trackrecord.app.resources.reject_invalid_slot
import com.trackrecord.app.resources.reject_no_game
import com.trackrecord.app.resources.reject_no_slot
import com.trackrecord.app.resources.reject_not_a_guesser
import com.trackrecord.app.resources.reject_not_allowed_to_name
import com.trackrecord.app.resources.reject_not_enough_skips
import com.trackrecord.app.resources.reject_not_enough_vetoes
import com.trackrecord.app.resources.reject_not_now
import com.trackrecord.app.resources.reject_nothing_to_undo
import com.trackrecord.app.resources.reject_pool_empty
import com.trackrecord.app.resources.reject_pool_too_small
import com.trackrecord.app.resources.reject_skips_at_max
import com.trackrecord.app.resources.reject_slot_taken
import com.trackrecord.app.resources.reject_song_in_game
import com.trackrecord.app.resources.reject_song_not_found
import com.trackrecord.app.resources.reject_too_few_players
import com.trackrecord.app.resources.reject_too_many_players
import com.trackrecord.app.resources.reject_unknown_player
import com.trackrecord.app.resources.reject_veto_limit
import com.trackrecord.app.resources.reject_vetoes_at_max
import com.trackrecord.app.resources.reject_year_out_of_range
import com.trackrecord.engine.RejectReason
import org.jetbrains.compose.resources.StringResource

/** User-facing text for engine rejections. */
val RejectReason.message: StringResource
    get() = when (this) {
        RejectReason.NO_GAME -> Res.string.reject_no_game
        RejectReason.WRONG_PHASE, RejectReason.WRONG_MODE -> Res.string.reject_not_now
        RejectReason.TOO_FEW_PLAYERS -> Res.string.reject_too_few_players
        RejectReason.TOO_MANY_PLAYERS -> Res.string.reject_too_many_players
        RejectReason.DUPLICATE_PLAYER -> Res.string.reject_duplicate_player
        RejectReason.INVALID_SETTINGS -> Res.string.reject_invalid_settings
        RejectReason.POOL_TOO_SMALL -> Res.string.reject_pool_too_small
        RejectReason.POOL_EMPTY -> Res.string.reject_pool_empty
        RejectReason.UNKNOWN_PLAYER -> Res.string.reject_unknown_player
        RejectReason.ACTIVE_PLAYER_CANNOT_VETO -> Res.string.reject_active_cannot_veto
        RejectReason.INVALID_SLOT -> Res.string.reject_invalid_slot
        RejectReason.SLOT_TAKEN -> Res.string.reject_slot_taken
        RejectReason.NO_SLOT_SELECTED -> Res.string.reject_no_slot
        RejectReason.NOT_ENOUGH_VETOES -> Res.string.reject_not_enough_vetoes
        RejectReason.NOT_ENOUGH_SKIPS -> Res.string.reject_not_enough_skips
        RejectReason.SKIPS_AT_MAX -> Res.string.reject_skips_at_max
        RejectReason.NOT_A_GUESSER -> Res.string.reject_not_a_guesser
        RejectReason.NOT_ALLOWED_TO_NAME -> Res.string.reject_not_allowed_to_name
        RejectReason.VETOES_AT_MAX -> Res.string.reject_vetoes_at_max
        RejectReason.VETO_LIMIT_REACHED -> Res.string.reject_veto_limit
        RejectReason.NOTHING_TO_UNDO -> Res.string.reject_nothing_to_undo
        RejectReason.FEATURE_DISABLED -> Res.string.reject_feature_disabled
        RejectReason.YEAR_OUT_OF_RANGE -> Res.string.reject_year_out_of_range
        RejectReason.DUPLICATE_GUESS -> Res.string.reject_duplicate_guess
        RejectReason.GUESSES_INCOMPLETE -> Res.string.reject_guesses_incomplete
        RejectReason.ALL_GUESSED -> Res.string.reject_all_guessed
        RejectReason.SONG_NOT_FOUND -> Res.string.reject_song_not_found
        RejectReason.SONG_ALREADY_IN_GAME -> Res.string.reject_song_in_game
    }
