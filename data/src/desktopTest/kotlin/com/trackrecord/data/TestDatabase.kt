package com.trackrecord.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.trackrecord.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** An empty in-memory database on the desktop's SQLite (PLATFORMS.md phase 3); formerly an instrumented test's. */
fun testDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
