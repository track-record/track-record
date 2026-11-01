package com.trackrecord.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.trackrecord.app.audio.GameAudio
import com.trackrecord.app.platform.AppFolders
import com.trackrecord.app.platform.Downloads
import com.trackrecord.app.platform.FileAccess
import com.trackrecord.app.platform.PlatformImages
import com.trackrecord.app.platform.PlatformServices
import com.trackrecord.app.platform.PreviewPlayer
import com.trackrecord.app.platform.RoomHost
import com.trackrecord.app.remote.RoomIdentity
import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.create
import com.trackrecord.data.library.FolderScanner
import com.trackrecord.data.library.JaudiotaggerTagReader
import com.trackrecord.data.library.NioFolderScanner
import com.trackrecord.data.library.TagReader
import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.remote.LocalAddress
import org.jaudiotagger.audio.AudioFileIO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI

/**
 * The desktop's side of the seams (PLATFORMS.md phase 6): the data in [folder], folders as `file:` URIs, jaudiotagger
 * for tags, Chromium for music, fingerprints and the internet room. [onQuit] closes the app, for the update's
 * installer.
 */
class DesktopPlatform(private val folder: File, private val chromium: Chromium, mute: Boolean, onQuit: () -> Unit = {}) : PlatformServices {
    private val volume = if (mute) 0.0 else 1.0

    override val database: AppDatabase = AppDatabase.create(folder)
    override val settings = SettingsRepository(folder)
    override val folderScanner: FolderScanner = NioFolderScanner()
    override val tagReader: TagReader = JaudiotaggerTagReader()
    override val fingerprinter = ChromiumFingerprinter(chromium)
    override val deviceName: String = System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("PC")

    override val folders = object : AppFolders {
        override val covers = File(folder, "covers")
        override val photos = File(folder, "photos").apply { mkdirs() }
        override val private = File(folder, "private").apply { mkdirs() }
    }

    override val images = object : PlatformImages {
        override fun decode(bytes: ByteArray, targetPx: Int): ImageBitmap? = runCatching {
            val image = Image.makeFromEncoded(bytes)
            // Kept at about twice the size shown: a large cover costs memory on every card.
            val scaled = if (image.width > targetPx * 2) scale(image, targetPx * 2) else image
            scaled.toComposeImageBitmap()
        }.getOrNull()

        override fun embeddedCover(uri: String): ByteArray? = runCatching {
            AudioFileIO.read(File(URI(uri))).tag?.firstArtwork?.binaryData
        }.getOrNull()

        override fun jpeg(image: ImageBitmap, quality: Int): ByteArray? = runCatching {
            Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, quality)?.bytes
        }.getOrNull()

        private fun scale(image: Image, width: Int): Image {
            val height = (image.height.toLong() * width / image.width).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(width, height)
            surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
            return surface.makeImageSnapshot()
        }
    }

    override val files = object : FileAccess {
        override fun openRead(file: String): InputStream? = runCatching { fileOf(file).inputStream() }.getOrNull()

        override fun openWrite(file: String): OutputStream? = runCatching { fileOf(file).outputStream() }.getOrNull()

        override fun displayName(file: String): String = fileOf(file).name
    }

    /** Exportify's lists arrive in the user's Downloads folder, which the app may always read. */
    override val downloads = object : Downloads {
        override fun hasAccess() = true

        override val folder: File? = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }
    }

    private val audio by lazy { ChromiumAudio(chromium, volume) }
    private val preview by lazy { ChromiumPreview(chromium, volume) }

    override fun gameAudio(): GameAudio = audio

    override fun previewPlayer(): PreviewPlayer = preview

    override val canHostInternetRoom = true

    override fun internetRoom(identity: RoomIdentity, onMessage: (peer: String, text: String) -> Unit, onLeave: (peer: String) -> Unit): RoomHost =
        JcefRoom(chromium, identity, onMessage, onLeave)

    // The update comes as an MSI (PLATFORMS.md phase 8), downloaded into Downloads and started; it replaces this app.
    override val updatePackage = ".msi"
    override val updateInstaller = MsiInstaller(downloads.folder ?: File(folder, "updates"), onQuit)

    // Windows' firewall asks the first time the guests' server listens (PLATFORMS.md phase 7).
    override val asksForNetworkAccess = System.getProperty("os.name").lowercase().startsWith("windows")

    override fun webAsset(path: String): ByteArray? =
        if (".." in path) null else DesktopPlatform::class.java.getResourceAsStream("/remote/$path")?.use { it.readBytes() }

    /**
     * The address that holds the default route (PLATFORMS.md phase 0): Windows names its interfaces "ethernet_…" and
     * "wireless_…", and Hyper-V's switch looks like an ordinary adapter. A UDP connect to a documentation address
     * (192.0.2.1) only asks the routing table and sends nothing.
     */
    override fun lanAddress(): String? {
        val routed = runCatching {
            DatagramSocket().use {
                it.connect(InetAddress.getByName("192.0.2.1"), 9)
                it.localAddress.hostAddress
            }
        }.getOrNull()
        return routed?.takeIf { LocalAddress.pick(listOf("" to it)) != null } ?: LocalAddress.find()
    }

    private companion object {
        /** Files are `file:` URIs (folders, songs) or paths (from the file dialogs). */
        fun fileOf(file: String): File = if (file.startsWith("file:")) File(URI(file)) else File(file)
    }
}
