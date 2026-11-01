package com.trackrecord.app

import android.app.Application
import android.content.Intent
import com.trackrecord.app.platform.AndroidPlatform
import com.trackrecord.data.musicbrainz.NetLog

/** The Android app: its build's settings ([AppConfig]) and the one [AppContainer]. */
class TrackRecordApp :
    Application(),
    HasAppContainer {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppConfig.current = AppConfig(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            debug = BuildConfig.DEBUG,
            guestPage = BuildConfig.GUEST_PAGE,
            acoustIdKey = BuildConfig.ACOUSTID_KEY,
            musicBrainzContact = BuildConfig.MUSICBRAINZ_CONTACT,
            updateCheck = BuildConfig.UPDATE_CHECK,
        )
        NetLog.enabled = BuildConfig.DEBUG
        container = AppContainer(AndroidPlatform(this)).apply {
            // The activity is singleTask: this brings the running one back, which closes Exportify's tab above it (D-097).
            bringToFront = {
                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}
