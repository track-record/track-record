package com.trackrecord.app.players

import com.trackrecord.app.game.GamePlayer
import com.trackrecord.data.db.ProfileEntity

/** A saved player as a game's player. */
fun ProfileEntity.asGamePlayer() = GamePlayer(
    id = "p-$id",
    name = name,
    initials = initials,
    colorIndex = colorIndex,
    photoPath = photoPath,
    profileId = id,
)

/** Deletes a photo taken in the editor but not saved with the profile. */
fun discardPhoto(path: String?) {
    if (path != null) java.io.File(path).delete()
}
