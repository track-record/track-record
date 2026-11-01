package com.trackrecord.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/** The app's database on the desktop: [folder]/trackrecord.db, with the SQLite bundled with the app. */
fun AppDatabase.Companion.create(folder: File): AppDatabase =
    AppDatabase.build(Room.databaseBuilder<AppDatabase>(File(folder, AppDatabase.FILE_NAME).absolutePath).setDriver(BundledSQLiteDriver()))
