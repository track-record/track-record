package com.trackrecord.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = SettingsRepository.FILE_NAME)

/** The settings on Android, in the same file as before (datastore/settings.preferences_pb). */
fun SettingsRepository(context: Context): SettingsRepository = SettingsRepository(context.applicationContext.dataStore)
