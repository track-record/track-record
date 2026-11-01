package com.trackrecord.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toOkioPath
import java.io.File

/** The settings on the desktop: [folder]/settings.preferences_pb. Only one per folder may exist at a time. */
fun SettingsRepository(folder: File): SettingsRepository =
    SettingsRepository(PreferenceDataStoreFactory.createWithPath { File(folder, "${SettingsRepository.FILE_NAME}.preferences_pb").toOkioPath() })
