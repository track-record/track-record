package com.trackrecord.engine.year

import kotlinx.serialization.Serializable

const val MIN_PLAYABLE_YEAR = 1900

enum class YearSource { MANUAL, TAG_ORIGINAL, TAG, MUSICBRAINZ, NONE }

/** Which year counts when the tags and MusicBrainz disagree (a setting, feedback rounds 28, 32), in the order offered. */
@Serializable
enum class YearPreference { MUSICBRAINZ, TAGS, LOWEST }

/** Every year known for one song. Any field may be missing. */
data class YearInputs(
    val manualYear: Int? = null,
    val tagOriginalYear: Int? = null,
    val tagYear: Int? = null,
    /** Year of an accepted MusicBrainz match, if any. */
    val musicBrainzYear: Int? = null,
)

data class ResolvedYear(
    /** The effective year, or `null` if the song is not playable. */
    val year: Int?,
    val source: YearSource,
    /** Tag original year, else tag year. */
    val baseYear: Int?,
    /** An accepted MusicBrainz match exists. */
    val verified: Boolean,
    /** Tags and MusicBrainz both gave a year and they differ. */
    val sourcesDisagree: Boolean,
) {
    val playable: Boolean get() = year != null
}

object YearResolver {
    fun isValidYear(year: Int?, currentYear: Int): Boolean =
        year != null && year in MIN_PLAYABLE_YEAR..currentYear

    /**
     * 1. Manual edit → use it.
     * 2. Tags and an accepted MusicBrainz match both give a year → [preference] decides: MusicBrainz's
     *    (the default, DECISIONS.md D-055), the tags' or the lower one (feedback rounds 28, 32).
     * 3. Otherwise whichever exists; the base year (tag original year, else tag year) is unverified.
     */
    fun resolve(inputs: YearInputs, currentYear: Int, preference: YearPreference = YearPreference.MUSICBRAINZ): ResolvedYear {
        fun valid(y: Int?) = y?.takeIf { isValidYear(it, currentYear) }
        val manual = valid(inputs.manualYear)
        val original = valid(inputs.tagOriginalYear)
        val tag = valid(inputs.tagYear)
        val mb = valid(inputs.musicBrainzYear)
        val base = original ?: tag
        val baseSource = if (original != null) YearSource.TAG_ORIGINAL else YearSource.TAG
        val disagree = base != null && mb != null && base != mb

        val tagsLead = when (preference) {
            YearPreference.LOWEST -> base != null && mb != null && base < mb
            YearPreference.MUSICBRAINZ -> false
            YearPreference.TAGS -> base != null
        }
        val (year, source) = when {
            manual != null -> manual to YearSource.MANUAL
            mb != null && !tagsLead -> mb to YearSource.MUSICBRAINZ
            base != null -> base to baseSource
            else -> null to YearSource.NONE
        }
        return ResolvedYear(year, source, base, verified = mb != null, sourcesDisagree = disagree)
    }
}
