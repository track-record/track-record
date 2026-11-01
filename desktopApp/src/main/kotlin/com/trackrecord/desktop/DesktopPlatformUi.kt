package com.trackrecord.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.trackrecord.app.platform.PlatformUi
import com.trackrecord.app.players.PhotoCropDialog
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.profiles_choose_photo
import com.trackrecord.app.resources.profiles_take_photo
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.data.library.NioFolderScanner
import org.jetbrains.compose.resources.stringResource
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.nfd.NFDFilterItem
import org.lwjgl.util.nfd.NativeFileDialog.NFD_FreePath
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Init
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OpenDialog
import org.lwjgl.util.nfd.NativeFileDialog.NFD_PickFolder
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Quit
import org.lwjgl.util.nfd.NativeFileDialog.NFD_SaveDialog
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * The desktop's pickers (PLATFORMS.md, FilePicker): the system's own dialogs (Windows' Explorer dialogs, with quick
 * access), through nativefiledialog-extended. No share sheet: the link is copied. Players' photos come from the webcam
 * (through [chromium]) or a picture file, cropped as on Android and kept by [photos].
 */
class DesktopPlatformUi(private val chromium: Chromium, private val photos: DesktopPhotos) : PlatformUi {
    @Composable
    override fun rememberFolderPicker(onPicked: (folder: String) -> Unit): () -> Unit = {
        dialog { out -> NFD_PickFolder(out, folderIn("Music")) }?.let { onPicked(NioFolderScanner.uriOf(File(it))) }
    }

    @Composable
    override fun rememberListPicker(onPicked: (file: String) -> Unit): () -> Unit = {
        dialog { out -> MemoryStack.stackPush().use { stack -> NFD_OpenDialog(out, filter(stack, "Song lists", "csv,txt,zip"), folderIn("Downloads")) } }?.let(onPicked)
    }

    @Composable
    override fun rememberFileOpener(onPicked: (file: String) -> Unit): () -> Unit = {
        dialog { out -> MemoryStack.stackPush().use { stack -> NFD_OpenDialog(out, filter(stack, "Track Record library", "trackrecord"), null as CharSequence?) } }?.let(onPicked)
    }

    @Composable
    override fun rememberFileSaver(onPicked: (file: String) -> Unit): (name: String) -> Unit = { name ->
        dialog { out -> MemoryStack.stackPush().use { stack -> NFD_SaveDialog(out, filter(stack, "Track Record library", "trackrecord"), null as CharSequence?, name) } }?.let(onPicked)
    }

    // The desktop app may read the Downloads folder: nothing to ask.
    @Composable
    override fun rememberDownloadsAccess(after: () -> Unit): (() -> Unit)? = null

    override fun openExportify() {
        runCatching { Desktop.getDesktop().browse(URI("https://exportify.app/")) }
    }

    override val share: ((String) -> Unit)? = null

    override val photoButtons: @Composable (onPhoto: (path: String) -> Unit) -> Unit = { onPhoto ->
        var camera by remember { mutableStateOf(false) }
        // A new photo first goes through the crop dialog (feedback round 16).
        var cropping by remember { mutableStateOf<ImageBitmap?>(null) }
        if (camera) {
            CameraDialog(chromium, onCancel = { camera = false }) { photo ->
                camera = false
                cropping = photo
            }
        }
        cropping?.let { image ->
            PhotoCropDialog(image, onCancel = { cropping = null }, mouse = true) { square ->
                cropping = null
                photos.save(image, square)?.let(onPhoto)
            }
        }
        OutlinedButton(onClick = { camera = true }) { Text(stringResource(Res.string.profiles_take_photo)) }
        OutlinedButton(onClick = {
            dialog { out -> MemoryStack.stackPush().use { stack -> NFD_OpenDialog(out, filter(stack, "Pictures", "jpg,jpeg,png,webp,bmp,gif"), folderIn("Pictures")) } }
                ?.let { path -> runCatching { File(path).readBytes() }.getOrNull()?.let(photos::decode) }
                ?.let { cropping = it }
        }) { Text(stringResource(Res.string.profiles_choose_photo)) }
    }

    private companion object {
        /** A folder in the user's home, if it is there. */
        fun folderIn(name: String): String? = File(System.getProperty("user.home"), name).takeIf { it.isDirectory }?.path

        fun filter(stack: MemoryStack, name: String, extensions: String): NFDFilterItem.Buffer =
            NFDFilterItem.malloc(1, stack).also { it.get(0).name(stack.UTF8(name)).spec(stack.UTF8(extensions)) }

        /** Shows a dialog ([show] returns its result code); the picked path, or null when cancelled. Blocks until closed. */
        fun dialog(show: (PointerBuffer) -> Int): String? {
            NFD_Init()
            try {
                MemoryStack.stackPush().use { stack ->
                    val out = stack.mallocPointer(1)
                    if (show(out) != NFD_OKAY) return null
                    val path = out.getStringUTF8(0)
                    NFD_FreePath(out.get(0))
                    return path
                }
            } finally {
                NFD_Quit()
            }
        }
    }
}
