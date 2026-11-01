package com.trackrecord.data.musicbrainz

internal actual fun platformLog(tag: String, warning: Boolean, message: String) {
    System.err.println("${if (warning) "W" else "I"}/$tag: $message")
}
