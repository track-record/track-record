package com.trackrecord.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver

/** The app's database on Android: the same file as before Room KMP, with Android's own SQLite. */
fun AppDatabase.Companion.create(context: Context): AppDatabase {
    val app = context.applicationContext
    return AppDatabase.build(
        Room.databaseBuilder<AppDatabase>(app, app.getDatabasePath(AppDatabase.FILE_NAME).absolutePath).setDriver(AndroidSQLiteDriver()),
    )
}
