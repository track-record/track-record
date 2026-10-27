package com.trackrecord.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.library.FolderScanner
import com.trackrecord.data.library.FoundFile
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.library.RawTags
import com.trackrecord.data.library.TagReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Folders in a real (in-memory) database: removing one while it is read in (the round 48 crash). */
@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()

    private val scanner = object : FolderScanner {
        override fun scan(treeUri: String) = (1..5).map { FoundFile("$treeUri/$it.mp3", "$it.mp3", 1L, 1L) }

        override fun displayName(treeUri: String) = treeUri
    }

    /** Completed once the first file is being read; [slow] folders never finish reading. */
    private val reading = CompletableDeferred<Unit>()
    private val slow = mutableSetOf<String>()

    private val tags = object : TagReader {
        override suspend fun read(uri: String): RawTags {
            if (slow.any { uri.startsWith(it) }) {
                reading.complete(Unit)
                awaitCancellation()
            }
            return RawTags(mapOf("TIT2" to uri, "TPE1" to "Artist", "TDRC" to "1990"), 200_000, false)
        }
    }

    private val repo = LibraryRepository(db, scanner, tags, currentYear = { 2026 })

    @After
    fun close() = db.close()

    @Test
    fun removing_a_folder_while_it_is_read_in_stops_the_read_in() = runBlocking {
        slow += "slow"
        val adding = async { repo.addFolder("slow") }
        reading.await()
        repo.removeFolder(db.folders().getAll().single().id)
        assertEquals(0, adding.await().added)
        assertTrue(db.folders().getAll().isEmpty())
        assertTrue(db.songs().getAll().isEmpty())
    }

    @Test
    fun a_rescan_goes_on_with_the_other_folders() = runBlocking {
        repo.addFolder("fast")
        repo.addFolder("slow2")
        slow += "slow2"
        db.songs().getAll().filter { it.uri.startsWith("slow2") }.let { songs -> db.songs().delete(songs.map { it.id }) }
        val rescanning = async { repo.rescanAll() }
        reading.await()
        repo.removeFolder(db.folders().getAll().single { it.treeUri == "slow2" }.id)
        rescanning.await()
        assertEquals(listOf("fast"), db.folders().getAll().map { it.treeUri })
        assertEquals(5, db.songs().getAll().size)
    }
}
