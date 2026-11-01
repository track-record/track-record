package com.trackrecord.data.musicbrainz

/**
 * Network diagnostics in the platform's log (tag "TrackRecordNet"; logcat on Android): search terms and links, so only when [enabled]
 * (debug builds; DECISIONS.md D-170). No-op in JVM unit tests.
 */
object NetLog {
    private const val TAG = "TrackRecordNet"

    @Volatile var enabled = false

    internal fun i(message: String) {
        if (enabled) runCatching { platformLog(TAG, warning = false, message) }
    }

    internal fun w(message: String) {
        if (enabled) runCatching { platformLog(TAG, warning = true, message) }
    }
}

/** Writes one line to the platform's log. */
internal expect fun platformLog(tag: String, warning: Boolean, message: String)
