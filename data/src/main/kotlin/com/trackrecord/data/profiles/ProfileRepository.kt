package com.trackrecord.data.profiles

import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.ProfileEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Saved player profiles. Photos are files in app storage; this repository deletes them with the profile. */
class ProfileRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    val profiles: Flow<List<ProfileEntity>> = db.profiles().observeAll()

    suspend fun all(): List<ProfileEntity> = db.profiles().getAll()

    suspend fun get(id: Long): ProfileEntity? = db.profiles().get(id)

    /** Creates or updates a profile; returns its id. A replaced photo file is deleted. */
    suspend fun save(id: Long?, name: String, initials: String, colorIndex: Int, photoPath: String?): Long {
        val old = id?.let { db.profiles().get(it) }
        if (old?.photoPath != null && old.photoPath != photoPath) File(old.photoPath).delete()
        val entity = ProfileEntity(
            id = id ?: 0,
            name = name.trim(),
            initials = initials.trim().uppercase(),
            colorIndex = colorIndex,
            photoPath = photoPath,
            createdAt = old?.createdAt ?: clock(),
        )
        val rowId = db.profiles().upsert(entity)
        return if (id != null && id != 0L) id else rowId
    }

    suspend fun delete(id: Long) {
        db.profiles().get(id)?.photoPath?.let { File(it).delete() }
        db.profiles().delete(id)
    }
}
