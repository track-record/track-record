package com.trackrecord.app.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackrecord.app.R
import com.trackrecord.app.game.GamePlayer
import com.trackrecord.app.game.PlayerColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Circle in the player's color with their initials, or their photo ringed in their color. Never color
 * alone: the photo identifies the player, so it shows no initials (feedback round 11). On a fill of the
 * player's own colour, [ring] outlines it so it doesn't vanish (DESIGN-REVIEW UX-02).
 */
@Composable
fun PlayerAvatar(player: GamePlayer, size: Dp = 40.dp, modifier: Modifier = Modifier, ring: Color? = null) {
    // Decoded once per photo for every chip, and off the main thread (REVIEW.md P-04).
    // Keyed by the path: a chip that changes player (the next guesser) shows the new photo (feedback round 49).
    val path = player.photoPath
    val photo = remember(path) { mutableStateOf(path?.let(Avatars::cached)) }
    LaunchedEffect(path) {
        if (photo.value == null && path != null) photo.value = Avatars.load(path)
    }
    val color = PlayerColors.color(player.colorIndex)
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color).border(if (ring != null) 2.dp else 3.dp, ring ?: color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val image = photo.value
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
        } else {
            Text(
                player.initials,
                color = PlayerColors.onColor(player.colorIndex),
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Avatar plus name and an optional trailing label (e.g. "2 vetoes"). */
@Composable
fun PlayerChip(player: GamePlayer, modifier: Modifier = Modifier, label: String? = null, avatarSize: Dp = 40.dp) {
    val description = if (label == null) player.name else stringResource(R.string.a11y_with_detail, player.name, label)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayerAvatar(player, avatarSize)
        Text(player.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Decoded profile photos by file path, shared by every chip (REVIEW.md P-04). A photo file never changes
 * (a new photo is a new file, ProfilePhotos), so an entry stays right.
 */
private object Avatars {
    private val bitmaps = LruCache<String, ImageBitmap>(32)

    fun cached(path: String): ImageBitmap? = bitmaps.get(path)

    suspend fun load(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(File(path).path)?.asImageBitmap() }.getOrNull()?.also { bitmaps.put(path, it) }
    }
}
