package com.trackrecord.app.library

import androidx.compose.runtime.Composable
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.playlist_excluded
import com.trackrecord.app.resources.playlist_local_files
import com.trackrecord.app.resources.playlist_streamed
import com.trackrecord.data.db.PlaylistWithCount
import com.trackrecord.data.playlists.BuiltInPlaylists
import org.jetbrains.compose.resources.stringResource

/** A playlist's display name; built-in playlists are named by the app. */
@Composable
fun playlistName(p: PlaylistWithCount): String = when (p.id) {
    BuiltInPlaylists.LOCAL_FILES -> stringResource(Res.string.playlist_local_files)
    BuiltInPlaylists.STREAMED -> stringResource(Res.string.playlist_streamed)
    BuiltInPlaylists.EXCLUDED -> stringResource(Res.string.playlist_excluded)
    else -> p.name
}
