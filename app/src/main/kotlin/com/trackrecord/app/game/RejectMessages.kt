package com.trackrecord.app.game

import com.trackrecord.app.R
import com.trackrecord.engine.RejectReason

/** User-facing text for engine rejections. */
val RejectReason.message: Int
    get() = when (this) {
        RejectReason.NO_GAME -> R.string.reject_no_game
        RejectReason.WRONG_PHASE, RejectReason.WRONG_MODE -> R.string.reject_not_now
        RejectReason.TOO_FEW_PLAYERS -> R.string.reject_too_few_players
        RejectReason.TOO_MANY_PLAYERS -> R.string.reject_too_many_players
        RejectReason.DUPLICATE_PLAYER -> R.string.reject_duplicate_player
        RejectReason.INVALID_SETTINGS -> R.string.reject_invalid_settings
        RejectReason.POOL_TOO_SMALL -> R.string.reject_pool_too_small
        RejectReason.POOL_EMPTY -> R.string.reject_pool_empty
        RejectReason.UNKNOWN_PLAYER -> R.string.reject_unknown_player
        RejectReason.ACTIVE_PLAYER_CANNOT_VETO -> R.string.reject_active_cannot_veto
        RejectReason.INVALID_SLOT -> R.string.reject_invalid_slot
        RejectReason.SLOT_TAKEN -> R.string.reject_slot_taken
        RejectReason.NO_SLOT_SELECTED -> R.string.reject_no_slot
        RejectReason.NOT_ENOUGH_VETOES -> R.string.reject_not_enough_vetoes
        RejectReason.NOT_ENOUGH_SKIPS -> R.string.reject_not_enough_skips
        RejectReason.SKIPS_AT_MAX -> R.string.reject_skips_at_max
        RejectReason.NOT_A_GUESSER -> R.string.reject_not_a_guesser
        RejectReason.NOT_ALLOWED_TO_NAME -> R.string.reject_not_allowed_to_name
        RejectReason.VETOES_AT_MAX -> R.string.reject_vetoes_at_max
        RejectReason.VETO_LIMIT_REACHED -> R.string.reject_veto_limit
        RejectReason.NOTHING_TO_UNDO -> R.string.reject_nothing_to_undo
        RejectReason.FEATURE_DISABLED -> R.string.reject_feature_disabled
        RejectReason.YEAR_OUT_OF_RANGE -> R.string.reject_year_out_of_range
        RejectReason.DUPLICATE_GUESS -> R.string.reject_duplicate_guess
        RejectReason.GUESSES_INCOMPLETE -> R.string.reject_guesses_incomplete
        RejectReason.ALL_GUESSED -> R.string.reject_all_guessed
        RejectReason.SONG_NOT_FOUND -> R.string.reject_song_not_found
        RejectReason.SONG_ALREADY_IN_GAME -> R.string.reject_song_in_game
    }
