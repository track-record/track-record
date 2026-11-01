package com.trackrecord.app

/**
 * What the build says about the app (PLATFORMS.md, the AppConfig seam): from BuildConfig on Android, generated at
 * build time on the desktop. Set once at start, before anything reads it.
 */
data class AppConfig(
    val versionName: String,
    val versionCode: Int,
    val debug: Boolean,
    /** Where internet guests open the guest page (SPEC v4). */
    val guestPage: String,
    /** The app's own AcoustID key and MusicBrainz contact (secrets.properties, D-177); blank without. */
    val acoustIdKey: String,
    val musicBrainzContact: String,
    /** Whether the app looks for new releases (not in debug builds, which sit beside the release). */
    val updateCheck: Boolean,
) {
    companion object {
        lateinit var current: AppConfig
    }
}
