package com.trackrecord.app.remote

import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_cancel
import com.trackrecord.app.resources.action_close
import com.trackrecord.app.resources.action_confirm
import com.trackrecord.app.resources.action_next
import com.trackrecord.app.resources.action_ok
import com.trackrecord.app.resources.action_remove
import com.trackrecord.app.resources.action_reveal
import com.trackrecord.app.resources.action_save
import com.trackrecord.app.resources.app_name
import com.trackrecord.app.resources.cards
import com.trackrecord.app.resources.closest_axis_hint
import com.trackrecord.app.resources.closest_guess
import com.trackrecord.app.resources.closest_guessed
import com.trackrecord.app.resources.closest_range
import com.trackrecord.app.resources.closest_skip
import com.trackrecord.app.resources.closest_undo_guess
import com.trackrecord.app.resources.closest_waiting_for
import com.trackrecord.app.resources.device_computer
import com.trackrecord.app.resources.device_phone
import com.trackrecord.app.resources.device_tablet
import com.trackrecord.app.resources.device_unknown
import com.trackrecord.app.resources.game_next_song
import com.trackrecord.app.resources.game_over_final_scores
import com.trackrecord.app.resources.game_over_pool_empty
import com.trackrecord.app.resources.game_over_shared
import com.trackrecord.app.resources.game_over_title
import com.trackrecord.app.resources.game_over_winner
import com.trackrecord.app.resources.gap_here
import com.trackrecord.app.resources.gap_newer
import com.trackrecord.app.resources.gap_older
import com.trackrecord.app.resources.gap_tap_again
import com.trackrecord.app.resources.gap_tap_again_place
import com.trackrecord.app.resources.gap_veto
import com.trackrecord.app.resources.gap_veto_here
import com.trackrecord.app.resources.info_excluded
import com.trackrecord.app.resources.info_included
import com.trackrecord.app.resources.info_rules
import com.trackrecord.app.resources.info_settings
import com.trackrecord.app.resources.info_songs
import com.trackrecord.app.resources.info_title
import com.trackrecord.app.resources.keypad_backspace
import com.trackrecord.app.resources.list_separator
import com.trackrecord.app.resources.outcome_discarded
import com.trackrecord.app.resources.outcome_kept
import com.trackrecord.app.resources.outcome_nobody_won
import com.trackrecord.app.resources.outcome_round_won
import com.trackrecord.app.resources.outcome_round_won_shared
import com.trackrecord.app.resources.outcome_stolen
import com.trackrecord.app.resources.outcome_vetoes_lost
import com.trackrecord.app.resources.playback_error
import com.trackrecord.app.resources.playback_full_preview
import com.trackrecord.app.resources.playback_full_song
import com.trackrecord.app.resources.playback_open_deezer
import com.trackrecord.app.resources.playback_pause
import com.trackrecord.app.resources.playback_play
import com.trackrecord.app.resources.playback_replay
import com.trackrecord.app.resources.playback_seek
import com.trackrecord.app.resources.playback_streamed_deezer
import com.trackrecord.app.resources.playback_time
import com.trackrecord.app.resources.points
import com.trackrecord.app.resources.purchase_points_title
import com.trackrecord.app.resources.purchase_title
import com.trackrecord.app.resources.reject_not_now
import com.trackrecord.app.resources.requests_covers
import com.trackrecord.app.resources.requests_stop
import com.trackrecord.app.resources.requests_stop_hint
import com.trackrecord.app.resources.requests_stopped
import com.trackrecord.app.resources.requests_title
import com.trackrecord.app.resources.requests_years
import com.trackrecord.app.resources.result_corrected
import com.trackrecord.app.resources.result_favorite_add
import com.trackrecord.app.resources.result_favorite_remove
import com.trackrecord.app.resources.result_fits_here
import com.trackrecord.app.resources.result_guess_chip
import com.trackrecord.app.resources.result_guesses
import com.trackrecord.app.resources.result_named
import com.trackrecord.app.resources.result_named_hint
import com.trackrecord.app.resources.result_named_hint_all
import com.trackrecord.app.resources.result_named_hint_vetoers
import com.trackrecord.app.resources.result_named_skip_hint
import com.trackrecord.app.resources.result_named_who
import com.trackrecord.app.resources.result_sources
import com.trackrecord.app.resources.result_year_details
import com.trackrecord.app.resources.reveal_auto
import com.trackrecord.app.resources.reveal_locked
import com.trackrecord.app.resources.reveal_waiting_veto
import com.trackrecord.app.resources.round_title
import com.trackrecord.app.resources.score_cards_of
import com.trackrecord.app.resources.score_points_of
import com.trackrecord.app.resources.scoreboard_adjust_skips
import com.trackrecord.app.resources.scoreboard_adjust_skips_hint
import com.trackrecord.app.resources.scoreboard_adjust_vetoes
import com.trackrecord.app.resources.scoreboard_adjust_vetoes_hint
import com.trackrecord.app.resources.scoreboard_buy
import com.trackrecord.app.resources.scoreboard_buy_points
import com.trackrecord.app.resources.scoreboard_card_target
import com.trackrecord.app.resources.scoreboard_gained
import com.trackrecord.app.resources.scoreboard_remove
import com.trackrecord.app.resources.scoreboard_remove_text
import com.trackrecord.app.resources.scoreboard_remove_title
import com.trackrecord.app.resources.scoreboard_remove_turn
import com.trackrecord.app.resources.scoreboard_removed
import com.trackrecord.app.resources.scoreboard_title
import com.trackrecord.app.resources.seconds
import com.trackrecord.app.resources.settings_clip_limit
import com.trackrecord.app.resources.settings_confirm_twice
import com.trackrecord.app.resources.settings_confirm_twice_hint
import com.trackrecord.app.resources.settings_show_covers
import com.trackrecord.app.resources.settings_start_offset
import com.trackrecord.app.resources.settings_title
import com.trackrecord.app.resources.skip_title
import com.trackrecord.app.resources.skips
import com.trackrecord.app.resources.start_offset_next_song
import com.trackrecord.app.resources.timeline_declined
import com.trackrecord.app.resources.timeline_lock_in
import com.trackrecord.app.resources.timeline_no_veto_button
import com.trackrecord.app.resources.timeline_skip
import com.trackrecord.app.resources.timeline_veto_button
import com.trackrecord.app.resources.unknown_artist
import com.trackrecord.app.resources.value_off
import com.trackrecord.app.resources.vetoes
import com.trackrecord.app.resources.web_blocked
import com.trackrecord.app.resources.web_brief_ok
import com.trackrecord.app.resources.web_connecting
import com.trackrecord.app.resources.web_error_bad_request
import com.trackrecord.app.resources.web_error_blocked
import com.trackrecord.app.resources.web_error_network
import com.trackrecord.app.resources.web_error_no_game
import com.trackrecord.app.resources.web_error_not_host
import com.trackrecord.app.resources.web_error_paused
import com.trackrecord.app.resources.web_error_pick_player
import com.trackrecord.app.resources.web_error_stale
import com.trackrecord.app.resources.web_gap_choose
import com.trackrecord.app.resources.web_guess_taken
import com.trackrecord.app.resources.web_guessing
import com.trackrecord.app.resources.web_host_less
import com.trackrecord.app.resources.web_host_more
import com.trackrecord.app.resources.web_invalid_link
import com.trackrecord.app.resources.web_invalid_link_internet
import com.trackrecord.app.resources.web_left
import com.trackrecord.app.resources.web_lobby_text
import com.trackrecord.app.resources.web_lobby_title
import com.trackrecord.app.resources.web_local_sound
import com.trackrecord.app.resources.web_local_sound_hint
import com.trackrecord.app.resources.web_menu
import com.trackrecord.app.resources.web_my_cards
import com.trackrecord.app.resources.web_my_songs
import com.trackrecord.app.resources.web_no_cards
import com.trackrecord.app.resources.web_outdated
import com.trackrecord.app.resources.web_p2p_unreachable
import com.trackrecord.app.resources.web_paused_text
import com.trackrecord.app.resources.web_paused_title
import com.trackrecord.app.resources.web_pick_hint
import com.trackrecord.app.resources.web_pick_title
import com.trackrecord.app.resources.web_placing
import com.trackrecord.app.resources.web_reconnecting
import com.trackrecord.app.resources.web_sound_on
import com.trackrecord.app.resources.web_sound_turn_off
import com.trackrecord.app.resources.web_sound_turn_on
import com.trackrecord.app.resources.web_switch_player
import com.trackrecord.app.resources.web_taken
import com.trackrecord.app.resources.web_takeover_confirm
import com.trackrecord.app.resources.web_takeover_denied
import com.trackrecord.app.resources.web_takeover_take
import com.trackrecord.app.resources.web_takeover_title
import com.trackrecord.app.resources.web_takeover_waiting
import com.trackrecord.app.resources.web_theme
import com.trackrecord.app.resources.web_theme_host
import com.trackrecord.app.resources.web_veto_pick
import com.trackrecord.app.resources.web_veto_pick_twice
import com.trackrecord.app.resources.web_veto_placed
import com.trackrecord.app.resources.web_veto_question
import com.trackrecord.app.resources.web_volume
import com.trackrecord.app.resources.web_volume_open
import com.trackrecord.app.resources.web_waiting_reveal
import com.trackrecord.app.resources.web_waiting_text
import com.trackrecord.app.resources.web_waiting_title
import com.trackrecord.app.resources.web_waiting_vetoes
import com.trackrecord.app.resources.web_you
import com.trackrecord.app.resources.web_you_have
import com.trackrecord.app.resources.web_your_guess
import com.trackrecord.app.resources.web_your_turn
import com.trackrecord.app.resources.web_your_turn_twice
import com.trackrecord.app.resources.year_editor_title
import com.trackrecord.app.resources.year_sheet_round
import com.trackrecord.app.resources.years_off
import com.trackrecord.app.ui.Texts
import com.trackrecord.app.ui.theme.label
import com.trackrecord.data.settings.ThemeChoice
import com.trackrecord.remote.HubError
import org.jetbrains.compose.resources.StringResource

/**
 * The guest page's texts, taken from the string resources (DECISIONS.md D-108). Patterns keep their
 * Android placeholders (%1$s, %1$d); the page fills them in. Plurals are sent as "_one" and "_other".
 */
object WebStrings {
    private val strings = mapOf(
        "app_name" to Res.string.app_name,
        "list_separator" to Res.string.list_separator,
        "web_connecting" to Res.string.web_connecting,
        "web_reconnecting" to Res.string.web_reconnecting,
        "web_invalid_link" to Res.string.web_invalid_link,
        "web_waiting_title" to Res.string.web_waiting_title,
        "web_paused_title" to Res.string.web_paused_title,
        "web_paused_text" to Res.string.web_paused_text,
        "web_waiting_text" to Res.string.web_waiting_text,
        "web_lobby_title" to Res.string.web_lobby_title,
        "web_lobby_text" to Res.string.web_lobby_text,
        "web_pick_title" to Res.string.web_pick_title,
        "web_pick_hint" to Res.string.web_pick_hint,
        "web_taken" to Res.string.web_taken,
        "web_you" to Res.string.web_you,
        "web_takeover_confirm" to Res.string.web_takeover_confirm,
        "web_round" to Res.string.round_title,
        "web_game_over" to Res.string.game_over_title,
        "web_next_song" to Res.string.game_next_song,
        "web_your_turn" to Res.string.web_your_turn,
        "web_placing" to Res.string.web_placing,
        "web_lock_in" to Res.string.timeline_lock_in,
        "web_your_turn_twice" to Res.string.web_your_turn_twice,
        "web_veto_pick_twice" to Res.string.web_veto_pick_twice,
        "web_tap_again" to Res.string.gap_tap_again,
        "web_tap_again_place" to Res.string.gap_tap_again_place,
        "web_veto_here" to Res.string.gap_veto_here,
        "web_confirm" to Res.string.action_confirm,
        "web_confirm_twice" to Res.string.settings_confirm_twice,
        "web_show_covers" to Res.string.settings_show_covers,
        "web_guesses" to Res.string.result_guesses,
        "web_result_guess" to Res.string.result_guess_chip,
        "web_fits_here" to Res.string.result_fits_here,
        "web_open_deezer" to Res.string.playback_open_deezer,
        "web_streamed_deezer" to Res.string.playback_streamed_deezer,
        "web_gained" to Res.string.scoreboard_gained,
        "web_skip" to Res.string.timeline_skip,
        "web_buy" to Res.string.scoreboard_buy,
        "web_buy_points" to Res.string.scoreboard_buy_points,
        "web_bought" to Res.string.purchase_title,
        "web_bought_points" to Res.string.purchase_points_title,
        "web_skipped" to Res.string.skip_title,
        "web_you_have" to Res.string.web_you_have,
        "web_play" to Res.string.playback_play,
        "web_pause" to Res.string.playback_pause,
        "web_replay" to Res.string.playback_replay,
        "web_veto_question" to Res.string.web_veto_question,
        "web_veto" to Res.string.timeline_veto_button,
        "web_no_veto" to Res.string.timeline_no_veto_button,
        "web_veto_pick" to Res.string.web_veto_pick,
        "web_veto_placed" to Res.string.web_veto_placed,
        "web_declined" to Res.string.timeline_declined,
        "web_waiting_vetoes" to Res.string.web_waiting_vetoes,
        "web_waiting_reveal" to Res.string.web_waiting_reveal,
        "web_reveal_in" to Res.string.reveal_locked,
        "web_auto_reveal_in" to Res.string.reveal_auto,
        "web_gap_here" to Res.string.gap_here,
        "web_gap_veto" to Res.string.gap_veto,
        "web_gap_choose" to Res.string.web_gap_choose,
        "web_gap_older" to Res.string.gap_older,
        "web_gap_newer" to Res.string.gap_newer,
        "web_cancel" to Res.string.action_cancel,
        "web_outcome_kept" to Res.string.outcome_kept,
        "web_outcome_stolen" to Res.string.outcome_stolen,
        "web_outcome_discarded" to Res.string.outcome_discarded,
        "web_outcome_won" to Res.string.outcome_round_won,
        "web_outcome_none" to Res.string.outcome_nobody_won,
        "web_your_guess" to Res.string.web_your_guess,
        "web_guessing" to Res.string.web_guessing,
        "web_guess" to Res.string.closest_guess,
        "web_backspace" to Res.string.keypad_backspace,
        "web_guess_range" to Res.string.closest_range,
        "web_axis_hint" to Res.string.closest_axis_hint,
        "web_guess_taken" to Res.string.web_guess_taken,
        "web_game_over_winner" to Res.string.game_over_winner,
        "web_game_over_shared" to Res.string.game_over_shared,
        "web_game_over_pool_empty" to Res.string.game_over_pool_empty,
        "web_final_scores" to Res.string.game_over_final_scores,
        "web_scoreboard" to Res.string.scoreboard_title,
        "web_scoreboard_target" to Res.string.scoreboard_card_target,
        "web_cards_of" to Res.string.score_cards_of,
        "web_menu" to Res.string.web_menu,
        "web_my_cards" to Res.string.web_my_cards,
        "web_my_songs" to Res.string.web_my_songs,
        "web_no_cards" to Res.string.web_no_cards,
        "web_switch_player" to Res.string.web_switch_player,
        "web_theme" to Res.string.web_theme,
        "web_theme_host" to Res.string.web_theme_host,
        "web_close" to Res.string.action_close,
        "web_error_network" to Res.string.web_error_network,
        "web_p2p_unreachable" to Res.string.web_p2p_unreachable,
        "web_outdated" to Res.string.web_outdated,
        "web_sound_on" to Res.string.web_sound_on,
        "web_volume" to Res.string.web_volume,
        "web_sound_turn_on" to Res.string.web_sound_turn_on,
        "web_sound_turn_off" to Res.string.web_sound_turn_off,
        "web_local_sound" to Res.string.web_local_sound,
        "web_local_sound_hint" to Res.string.web_local_sound_hint,
        "web_volume_open" to Res.string.web_volume_open,
        "web_brief_ok" to Res.string.web_brief_ok,
        "web_favorite_add" to Res.string.result_favorite_add,
        "web_favorite_remove" to Res.string.result_favorite_remove,
        "web_settings" to Res.string.settings_title,
        "web_clip_length" to Res.string.settings_clip_limit,
        "web_requests_title" to Res.string.requests_title,
        "web_requests_years" to Res.string.requests_years,
        "web_requests_covers" to Res.string.requests_covers,
        "web_requests_stop" to Res.string.requests_stop,
        "web_requests_stop_hint" to Res.string.requests_stop_hint,
        "web_requests_stopped" to Res.string.requests_stopped,
        "web_start_offset" to Res.string.settings_start_offset,
        "web_start_offset_hint" to Res.string.start_offset_next_song,
        "web_clip_off" to Res.string.value_off,
        "web_skip_plain" to Res.string.closest_skip,
        "web_host_reveal" to Res.string.action_reveal,
        "web_host_reveal_locked" to Res.string.reveal_locked,
        "web_host_reveal_auto" to Res.string.reveal_auto,
        "web_host_reveal_waiting" to Res.string.reveal_waiting_veto,
        "web_host_named_who" to Res.string.result_named_who,
        "web_host_named_hint_active" to Res.string.result_named_hint,
        "web_host_named_hint_vetoers" to Res.string.result_named_hint_vetoers,
        "web_host_named_hint_all" to Res.string.result_named_hint_all,
        "web_host_named_hint_skip" to Res.string.result_named_skip_hint,
        "web_host_year_details" to Res.string.result_year_details,
        "web_host_adjust_vetoes" to Res.string.scoreboard_adjust_vetoes,
        "web_host_adjust_vetoes_hint" to Res.string.scoreboard_adjust_vetoes_hint,
        "web_host_adjust_skips" to Res.string.scoreboard_adjust_skips,
        "web_host_adjust_skips_hint" to Res.string.scoreboard_adjust_skips_hint,
        "web_host_next" to Res.string.action_next,
        "web_host_undo_guess" to Res.string.closest_undo_guess,
        "web_host_named" to Res.string.result_named,
        "web_host_year" to Res.string.year_editor_title,
        "web_host_save" to Res.string.action_save,
        "web_host_more" to Res.string.web_host_more,
        "web_host_less" to Res.string.web_host_less,
        "web_takeover_waiting" to Res.string.web_takeover_waiting,
        "web_takeover_denied" to Res.string.web_takeover_denied,
        "web_blocked" to Res.string.web_blocked,
        // Feedback round 36
        "web_info" to Res.string.info_title,
        "web_info_rules" to Res.string.info_rules,
        "web_info_settings" to Res.string.info_settings,
        "web_info_songs" to Res.string.info_songs,
        "web_info_included" to Res.string.info_included,
        "web_info_excluded" to Res.string.info_excluded,
        "web_points_of" to Res.string.score_points_of,
        "web_outcome_won_shared" to Res.string.outcome_round_won_shared,
        "web_waiting_for" to Res.string.closest_waiting_for,
        "web_guessed" to Res.string.closest_guessed,
        "web_removed" to Res.string.scoreboard_removed,
        "web_left" to Res.string.web_left,
        // Design review (DESIGN-REVIEW.md, D-277 to D-290)
        "web_invalid_link_internet" to Res.string.web_invalid_link_internet,
        "web_takeover_title" to Res.string.web_takeover_title,
        "web_takeover_take" to Res.string.web_takeover_take,
        "web_device_phone" to Res.string.device_phone,
        "web_device_tablet" to Res.string.device_tablet,
        "web_device_computer" to Res.string.device_computer,
        "web_device_unknown" to Res.string.device_unknown,
        "web_confirm_twice_hint" to Res.string.settings_confirm_twice_hint,
        "web_playback_error" to Res.string.playback_error,
        "web_sources" to Res.string.result_sources,
        "web_corrected" to Res.string.result_corrected,
        "web_vetoes_lost" to Res.string.outcome_vetoes_lost,
        "web_unknown_artist" to Res.string.unknown_artist,
        "web_ok" to Res.string.action_ok,
        "web_round_counted" to Res.string.year_sheet_round,
        // Feedback round 43: a co-host plays the whole song and removes players, as on the host.
        "web_full_preview" to Res.string.playback_full_preview,
        "web_full_song" to Res.string.playback_full_song,
        "web_remove" to Res.string.scoreboard_remove,
        "web_remove_title" to Res.string.scoreboard_remove_title,
        "web_remove_text" to Res.string.scoreboard_remove_text,
        "web_remove_turn" to Res.string.scoreboard_remove_turn,
        "web_remove_confirm" to Res.string.action_remove,
        // Feedback round 44: a co-host's seek bar with the time.
        "web_seek" to Res.string.playback_seek,
        "web_play_time" to Res.string.playback_time,
    )

    private val plurals = mapOf("web_vetoes" to Res.plurals.vetoes, "web_skips" to Res.plurals.skips, "web_years_off" to Res.plurals.years_off, "web_cards" to Res.plurals.cards, "web_points" to Res.plurals.points, "web_seconds" to Res.plurals.seconds)

    /** Every key [build] sends; WebStringsTest checks the guest page against them (REVIEW.md T-02). */
    val keys: Set<String>
        get() = strings.keys + plurals.keys.flatMap { listOf("${it}_one", "${it}_other") } + ThemeChoice.entries.map { "theme_${it.name}" }

    /** Keys sent as plurals ("_one" and "_other"); the page asks for them with plural(key, n). */
    val pluralKeys: Set<String> get() = plurals.keys

    fun build(): Map<String, String> = buildMap {
        for ((key, id) in strings) put(key, Texts.get(id))
        for ((key, id) in plurals) {
            put("${key}_one", Texts.plural(id, 1))
            put("${key}_other", Texts.plural(id, 2))
        }
        for (theme in ThemeChoice.entries) put("theme_${theme.name}", Texts.get(theme.label))
    }

    val HubError.text: StringResource
        get() = when (this) {
            HubError.NO_GAME -> Res.string.web_error_no_game
            HubError.PICK_PLAYER -> Res.string.web_error_pick_player
            HubError.NOT_YOUR_TURN -> Res.string.reject_not_now
            HubError.STALE -> Res.string.web_error_stale
            HubError.BAD_REQUEST -> Res.string.web_error_bad_request
            HubError.PAUSED -> Res.string.web_error_paused
            HubError.BLOCKED -> Res.string.web_error_blocked
            HubError.NOT_HOST -> Res.string.web_error_not_host
        }
}
