package com.trackrecord.app.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.trackrecord.app.R
import com.trackrecord.data.db.PlaylistWithCount
import com.trackrecord.data.playlists.BuiltInPlaylists

/** A playlist's display name; built-in playlists are named by the app. */
@Composable
fun playlistName(p: PlaylistWithCount): String = when (p.id) {
    BuiltInPlaylists.LOCAL_FILES -> stringResource(R.string.playlist_local_files)
    BuiltInPlaylists.STREAMED -> stringResource(R.string.playlist_streamed)
    BuiltInPlaylists.EXCLUDED -> stringResource(R.string.playlist_excluded)
    else -> p.name
}
