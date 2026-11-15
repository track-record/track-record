package com.trackrecord.app.remote

import com.trackrecord.app.game.RevealYear
import com.trackrecord.app.library.LookupUi
import com.trackrecord.app.library.fingerprinting
import com.trackrecord.app.library.label
import com.trackrecord.app.library.lookupsOff
import com.trackrecord.app.library.verificationLabel
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.acoustid_rejected_warning
import com.trackrecord.app.resources.lookup_no_match
import com.trackrecord.app.resources.lookup_offline
import com.trackrecord.app.resources.lookup_running
import com.trackrecord.app.resources.lookup_running_fingerprint
import com.trackrecord.app.resources.missing_year
import com.trackrecord.app.resources.service_unavailable
import com.trackrecord.app.resources.song_manual_year
import com.trackrecord.app.resources.song_musicbrainz_year
import com.trackrecord.app.resources.song_tag_original_year
import com.trackrecord.app.resources.song_tag_year
import com.trackrecord.app.resources.song_via_fingerprint
import com.trackrecord.app.resources.song_year_source
import com.trackrecord.app.resources.year_none
import com.trackrecord.app.ui.Texts
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.data.musicbrainz.AcoustIdKeyState
import com.trackrecord.data.musicbrainz.AcoustIdKeyStatus
import com.trackrecord.data.settings.AppSettings
import com.trackrecord.remote.YearDetailsView
import com.trackrecord.remote.YearSourceLine

/** The reveal's year card for a co-host's phone, worded as the host's (D-385; YearSourcesTable and LookupSection). */
object YearDetailsViews {
    fun of(reveal: RevealYear, key: AcoustIdKeyStatus, app: AppSettings?): YearDetailsView {
        val s = reveal.song
        val year = s.resolved.year
        val mbYear = s.musicBrainzYear ?: s.lookup?.year
        val outdated = s.checkedByOldRules
        fun line(label: String, value: Int?, caution: Boolean = false) = YearSourceLine(label, value?.let(::formatYear) ?: Texts.get(Res.string.year_none), caution)
        val lookup = reveal.lookup
        return YearDetailsView(
            year = year?.let(::formatYear) ?: Texts.get(Res.string.missing_year),
            missing = year == null,
            source = year?.let { Texts.get(Res.string.song_year_source, Texts.get(s.resolved.source.label)) },
            verification = Texts.get(verificationLabel(s)),
            outdated = outdated,
            lines = listOf(
                line(Texts.get(Res.string.song_tag_original_year), s.song.tagOriginalYear),
                line(Texts.get(Res.string.song_tag_year), s.song.tagYear),
                line(Texts.get(Res.string.song_musicbrainz_year), mbYear, caution = outdated && mbYear != null),
                line(Texts.get(Res.string.song_manual_year), s.manualYear),
            ),
            note = Texts.get(Res.string.song_via_fingerprint).takeIf { s.lookup?.let { it.year != null && it.viaFingerprint } == true },
            lookUp = s.needsNameCheck || lookup != LookupUi.Idle,
            lookingUp = lookup == LookupUi.Running,
            lookupText = when (lookup) {
                LookupUi.Running -> Texts.get(if (key.fingerprinting) Res.string.lookup_running_fingerprint else Res.string.lookup_running)
                LookupUi.NoMatch -> Texts.get(Res.string.lookup_no_match)
                LookupUi.Off -> Texts.get(lookupsOff(app))
                LookupUi.Offline -> Texts.get(Res.string.lookup_offline)
                is LookupUi.Refused -> Texts.get(Res.string.service_unavailable, Texts.get(lookup.service.label))
                LookupUi.Idle -> null
            },
            lookupError = lookup == LookupUi.Off || lookup == LookupUi.Offline || lookup is LookupUi.Refused,
            warning = Texts.get(Res.string.acoustid_rejected_warning).takeIf { key.state == AcoustIdKeyState.REJECTED },
            editYear = year,
        )
    }
}
