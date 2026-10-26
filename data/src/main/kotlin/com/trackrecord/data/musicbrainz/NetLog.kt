package com.trackrecord.data.musicbrainz

import android.util.Log

/**
 * Network diagnostics in logcat (tag "TrackRecordNet"): search terms and links, so only when [enabled]
 * (debug builds; DECISIONS.md D-170). No-op in JVM unit tests.
 */
object NetLog {
    private const val TAG = "TrackRecordNet"

    @Volatile var enabled = false

    internal fun i(message: String) {
        if (enabled) runCatching { Log.i(TAG, message) }
    }

    internal fun w(message: String) {
        if (enabled) runCatching { Log.w(TAG, message) }
    }
}
