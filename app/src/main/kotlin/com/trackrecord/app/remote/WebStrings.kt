package com.trackrecord.app.remote

import android.content.Context
import com.trackrecord.app.R
import com.trackrecord.app.ui.theme.label
import com.trackrecord.data.settings.ThemeChoice
import com.trackrecord.remote.HubError

/**
 * The guest page's texts, taken from the string resources (DECISIONS.md D-108). Patterns keep their
 * Android placeholders (%1$s, %1$d); the page fills them in. Plurals are sent as "_one" and "_other".
 */
object WebStrings {
    private val strings = mapOf(
        "app_name" to R.string.app_name,
        "list_separator" to R.string.list_separator,
        "web_connecting" to R.string.web_connecting,
        "web_reconnecting" to R.string.web_reconnecting,
        "web_invalid_link" to R.string.web_invalid_link,
        "web_waiting_title" to R.string.web_waiting_title,
        "web_paused_title" to R.string.web_paused_title,
        "web_paused_text" to R.string.web_paused_text,
        "web_waiting_text" to R.string.web_waiting_text,
        "web_lobby_title" to R.string.web_lobby_title,
        "web_lobby_text" to R.string.web_lobby_text,
        "web_pick_title" to R.string.web_pick_title,
        "web_pick_hint" to R.string.web_pick_hint,
        "web_taken" to R.string.web_taken,
        "web_you" to R.string.web_you,
        "web_takeover_confirm" to R.string.web_takeover_confirm,
        "web_round" to R.string.round_title,
        "web_game_over" to R.string.game_over_title,
        "web_next_song" to R.string.game_next_song,
        "web_your_turn" to R.string.web_your_turn,
        "web_placing" to R.string.web_placing,
        "web_lock_in" to R.string.timeline_lock_in,
        "web_your_turn_twice" to R.string.web_your_turn_twice,
        "web_veto_pick_twice" to R.string.web_veto_pick_twice,
        "web_tap_again" to R.string.gap_tap_again,
        "web_tap_again_place" to R.string.gap_tap_again_place,
        "web_veto_here" to R.string.gap_veto_here,
        "web_confirm" to R.string.action_confirm,
        "web_confirm_twice" to R.string.settings_confirm_twice,
        "web_show_covers" to R.string.settings_show_covers,
        "web_guesses" to R.string.result_guesses,
        "web_result_guess" to R.string.result_guess_chip,
        "web_fits_here" to R.string.result_fits_here,
        "web_open_deezer" to R.string.playback_open_deezer,
        "web_gained" to R.string.scoreboard_gained,
        "web_skip" to R.string.timeline_skip,
        "web_buy" to R.string.scoreboard_buy,
        "web_bought" to R.string.purchase_title,
        "web_skipped" to R.string.skip_title,
        "web_you_have" to R.string.web_you_have,
        "web_play" to R.string.playback_play,
        "web_pause" to R.string.playback_pause,
        "web_replay" to R.string.playback_replay,
        "web_veto_question" to R.string.web_veto_question,
        "web_veto" to R.string.timeline_veto_button,
        "web_no_veto" to R.string.timeline_no_veto_button,
        "web_veto_pick" to R.string.web_veto_pick,
        "web_veto_placed" to R.string.web_veto_placed,
        "web_declined" to R.string.timeline_declined,
        "web_waiting_vetoes" to R.string.web_waiting_vetoes,
        "web_waiting_reveal" to R.string.web_waiting_reveal,
        "web_reveal_in" to R.string.reveal_locked,
        "web_auto_reveal_in" to R.string.reveal_auto,
        "web_gap_here" to R.string.gap_here,
        "web_gap_veto" to R.string.gap_veto,
        "web_gap_choose" to R.string.web_gap_choose,
        "web_cancel" to R.string.action_cancel,
        "web_outcome_kept" to R.string.outcome_kept,
        "web_outcome_stolen" to R.string.outcome_stolen,
        "web_outcome_discarded" to R.string.outcome_discarded,
        "web_outcome_won" to R.string.outcome_round_won,
        "web_outcome_none" to R.string.outcome_nobody_won,
        "web_your_guess" to R.string.web_your_guess,
        "web_guessing" to R.string.web_guessing,
        "web_guess" to R.string.closest_guess,
        "web_backspace" to R.string.keypad_backspace,
        "web_guess_range" to R.string.closest_range,
        "web_axis_hint" to R.string.closest_axis_hint,
        "web_guess_taken" to R.string.web_guess_taken,
        "web_game_over_winner" to R.string.game_over_winner,
        "web_game_over_shared" to R.string.game_over_shared,
        "web_game_over_pool_empty" to R.string.game_over_pool_empty,
        "web_final_scores" to R.string.game_over_final_scores,
        "web_scoreboard" to R.string.scoreboard_title,
        "web_cards_of" to R.string.score_cards_of,
        "web_menu" to R.string.web_menu,
        "web_my_cards" to R.string.web_my_cards,
        "web_my_songs" to R.string.web_my_songs,
        "web_no_cards" to R.string.web_no_cards,
        "web_switch_player" to R.string.web_switch_player,
        "web_theme" to R.string.web_theme,
        "web_theme_host" to R.string.web_theme_host,
        "web_close" to R.string.action_close,
        "web_error_network" to R.string.web_error_network,
        "web_p2p_unreachable" to R.string.web_p2p_unreachable,
        "web_outdated" to R.string.web_outdated,
        "web_sound_on" to R.string.web_sound_on,
        "web_volume" to R.string.web_volume,
        "web_settings" to R.string.settings_title,
        "web_clip_length" to R.string.settings_clip_limit,
        "web_start_offset" to R.string.settings_start_offset,
        "web_start_offset_hint" to R.string.start_offset_next_song,
        "web_clip_off" to R.string.value_off,
        "web_skip_plain" to R.string.closest_skip,
        "web_host_reveal" to R.string.action_reveal,
        "web_host_reveal_locked" to R.string.reveal_locked,
        "web_host_reveal_auto" to R.string.reveal_auto,
        "web_host_reveal_waiting" to R.string.reveal_waiting_veto,
        "web_host_named_who" to R.string.result_named_who,
        "web_host_named_hint_active" to R.string.result_named_hint,
        "web_host_named_hint_vetoers" to R.string.result_named_hint_vetoers,
        "web_host_named_hint_all" to R.string.result_named_hint_all,
        "web_host_named_hint_skip" to R.string.result_named_skip_hint,
        "web_host_year_details" to R.string.result_year_details,
        "web_host_adjust_vetoes" to R.string.scoreboard_adjust_vetoes,
        "web_host_adjust_vetoes_hint" to R.string.scoreboard_adjust_vetoes_hint,
        "web_host_adjust_skips" to R.string.scoreboard_adjust_skips,
        "web_host_adjust_skips_hint" to R.string.scoreboard_adjust_skips_hint,
        "web_host_next" to R.string.action_next,
        "web_host_undo_guess" to R.string.closest_undo_guess,
        "web_host_named" to R.string.result_named,
        "web_host_year" to R.string.year_editor_title,
        "web_host_save" to R.string.action_save,
        "web_host_more" to R.string.web_host_more,
        "web_host_less" to R.string.web_host_less,
        "web_takeover_waiting" to R.string.web_takeover_waiting,
        "web_takeover_denied" to R.string.web_takeover_denied,
        "web_blocked" to R.string.web_blocked,
        // Feedback round 36
        "web_info" to R.string.info_title,
        "web_info_rules" to R.string.info_rules,
        "web_info_settings" to R.string.info_settings,
        "web_info_songs" to R.string.info_songs,
        "web_info_included" to R.string.info_included,
        "web_info_excluded" to R.string.info_excluded,
        "web_points_of" to R.string.score_points_of,
        "web_outcome_won_shared" to R.string.outcome_round_won_shared,
        "web_waiting_for" to R.string.closest_waiting_for,
        "web_guessed" to R.string.closest_guessed,
        "web_removed" to R.string.scoreboard_removed,
        "web_left" to R.string.web_left,
        // Design review (DESIGN-REVIEW.md, D-277 to D-290)
        "web_invalid_link_internet" to R.string.web_invalid_link_internet,
        "web_takeover_title" to R.string.web_takeover_title,
        "web_takeover_take" to R.string.web_takeover_take,
        "web_device_phone" to R.string.device_phone,
        "web_device_tablet" to R.string.device_tablet,
        "web_device_computer" to R.string.device_computer,
        "web_device_unknown" to R.string.device_unknown,
        "web_confirm_twice_hint" to R.string.settings_confirm_twice_hint,
        "web_playback_error" to R.string.playback_error,
        "web_sources" to R.string.result_sources,
        "web_corrected" to R.string.result_corrected,
        "web_vetoes_lost" to R.string.outcome_vetoes_lost,
        "web_unknown_artist" to R.string.unknown_artist,
        "web_ok" to R.string.action_ok,
        "web_round_counted" to R.string.year_sheet_round,
        // Feedback round 43: a co-host plays the whole song and removes players, as on the host.
        "web_full_preview" to R.string.playback_full_preview,
        "web_full_song" to R.string.playback_full_song,
        "web_remove" to R.string.scoreboard_remove,
        "web_remove_title" to R.string.scoreboard_remove_title,
        "web_remove_text" to R.string.scoreboard_remove_text,
        "web_remove_turn" to R.string.scoreboard_remove_turn,
        "web_remove_confirm" to R.string.action_remove,
        // Feedback round 44: a co-host's seek bar with the time.
        "web_seek" to R.string.playback_seek,
        "web_play_time" to R.string.playback_time,
    )

    private val plurals = mapOf("web_vetoes" to R.plurals.vetoes, "web_skips" to R.plurals.skips, "web_years_off" to R.plurals.years_off, "web_cards" to R.plurals.cards, "web_points" to R.plurals.points, "web_seconds" to R.plurals.seconds)

    /** Every key [build] sends; WebStringsTest checks the guest page against them (REVIEW.md T-02). */
    val keys: Set<String>
        get() = strings.keys + plurals.keys.flatMap { listOf("${it}_one", "${it}_other") } + ThemeChoice.entries.map { "theme_${it.name}" }

    /** Keys sent as plurals ("_one" and "_other"); the page asks for them with plural(key, n). */
    val pluralKeys: Set<String> get() = plurals.keys

    fun build(context: Context): Map<String, String> {
        val res = context.resources
        return buildMap {
            for ((key, id) in strings) put(key, res.getString(id))
            for ((key, id) in plurals) {
                put("${key}_one", res.getQuantityString(id, 1))
                put("${key}_other", res.getQuantityString(id, 2))
            }
            for (theme in ThemeChoice.entries) put("theme_${theme.name}", res.getString(theme.label))
        }
    }

    val HubError.text: Int
        get() = when (this) {
            HubError.NO_GAME -> R.string.web_error_no_game
            HubError.PICK_PLAYER -> R.string.web_error_pick_player
            HubError.NOT_YOUR_TURN -> R.string.reject_not_now
            HubError.STALE -> R.string.web_error_stale
            HubError.BAD_REQUEST -> R.string.web_error_bad_request
            HubError.PAUSED -> R.string.web_error_paused
            HubError.BLOCKED -> R.string.web_error_blocked
            HubError.NOT_HOST -> R.string.web_error_not_host
        }
}
