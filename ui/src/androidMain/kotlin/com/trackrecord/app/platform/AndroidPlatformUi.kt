package com.trackrecord.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.trackrecord.app.players.PhotoCropDialog
import com.trackrecord.app.players.ProfilePhotos
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.profiles_choose_photo
import com.trackrecord.app.resources.profiles_take_photo
import com.trackrecord.app.ui.components.OutlinedButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Android's pickers, permissions, share sheet and photos for the screens; [context] is the activity's. */
class AndroidPlatformUi(private val context: Context) : PlatformUi {
    @Composable
    override fun rememberFolderPicker(onPicked: (folder: String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                onPicked(uri.toString())
            }
        }
        return { launcher.launch(null) }
    }

    override fun releaseFolder(folder: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    @Composable
    override fun rememberListPicker(onPicked: (file: String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(OpenInDownloads()) { uri: Uri? -> uri?.let { onPicked(it.toString()) } }
        // Only CSV files in the picker (feedback round 20); text lists and Exportify's ZIP still import when opened
        // from a file manager or the download message.
        return { launcher.launch(LIST_TYPES) }
    }

    @Composable
    override fun rememberFileOpener(onPicked: (file: String) -> Unit): () -> Unit {
        // A .trackrecord file has no type of its own: any file, checked before anything is imported.
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { onPicked(it.toString()) } }
        return { launcher.launch(arrayOf("*/*")) }
    }

    @Composable
    override fun rememberFileSaver(onPicked: (file: String) -> Unit): (name: String) -> Unit {
        // As octet-stream, so the provider doesn't add an extension to ".trackrecord".
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? -> uri?.let { onPicked(it.toString()) } }
        return { name -> launcher.launch(name) }
    }

    @Composable
    override fun rememberDownloadsAccess(after: () -> Unit): (() -> Unit)? {
        val allFilesAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { after() }
        val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { after() }
        return {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                runCatching { allFilesAccess.launch(intent) }.onFailure { after() }
            } else {
                storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    /**
     * Exportify as a Custom Tab of the default browser, so its Spotify login carries over (D-092); its menu also
     * opens the page in the browser app. Tapping "Open" on Chrome's download message sends the file back to the app,
     * which closes the tab and imports it (D-097).
     */
    override fun openExportify() {
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse("https://exportify.app/")) }
    }

    override val share: (String) -> Unit = { text ->
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null))
    }

    override val photoButtons: @Composable (onPhoto: (path: String) -> Unit) -> Unit = { onPhoto ->
        val scope = rememberCoroutineScope()
        // A new photo first goes through the crop dialog (feedback round 16).
        var cropping by remember { mutableStateOf<Bitmap?>(null) }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) cropping = bitmap
        }
        val app = LocalContext.current
        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch { cropping = ProfilePhotos.decode(app, uri) }
        }
        cropping?.let { bitmap ->
            PhotoCropDialog(
                remember(bitmap) { bitmap.asImageBitmap() },
                onCancel = { cropping = null },
                onDone = { rect ->
                    cropping = null
                    scope.launch { ProfilePhotos.save(app, bitmap, Rect(rect.left, rect.top, rect.right, rect.bottom))?.let(onPhoto) }
                },
            )
        }
        OutlinedButton(onClick = { camera.launch(null) }) { Text(stringResource(Res.string.profiles_take_photo)) }
        OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(stringResource(Res.string.profiles_choose_photo))
        }
    }

    /**
     * Opens the picker in "Downloads" (the downloads provider, as in the picker's menu), which lists files of the
     * accepted types first, rather than the storage folder Download, which lists everything mixed (feedback round 20).
     */
    private class OpenInDownloads : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"),
            )
    }

    private companion object {
        val LIST_TYPES = arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/x-csv", "application/vnd.ms-excel")
    }
}

/** The library's preview on Android: a plain ExoPlayer without a media session, so nothing reaches the notification shade. */
class ExoPreviewPlayer(private val context: Context) : PreviewPlayer {
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private var player: ExoPlayer? = null

    override fun play(uri: String) {
        val p = player ?: ExoPlayer.Builder(context).build().also { created ->
            created.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playing.value = isPlaying
                }
            })
            player = created
        }
        p.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        p.prepare()
        p.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun release() {
        player?.release()
        player = null
    }
}
