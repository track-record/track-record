package com.trackrecord.data.musicbrainz

import android.util.Log

internal actual fun platformLog(tag: String, warning: Boolean, message: String) {
    if (warning) Log.w(tag, message) else Log.i(tag, message)
}
