package com.trackrecord.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.trackrecord.app.audio.GameAudio
import com.trackrecord.app.audio.MediaSessionAudio
import com.trackrecord.app.players.ProfilePhotos
import com.trackrecord.app.remote.InternetRoom
import com.trackrecord.app.remote.RoomIdentity
import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.create
import com.trackrecord.data.library.FolderScanner
import com.trackrecord.data.library.Media3TagReader
import com.trackrecord.data.library.SafFolderScanner
import com.trackrecord.data.library.TagReader
import com.trackrecord.data.musicbrainz.Fingerprinter
import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.fingerprint.AudioFingerprinter
import com.trackrecord.remote.LocalAddress
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The Android app's side of the seams (PLATFORMS.md section 2). */
class AndroidPlatform(context: Context) : PlatformServices {
    private val context = context.applicationContext
    override val database: AppDatabase = AppDatabase.create(this.context)
    override val settings = SettingsRepository(this.context)
    override val folderScanner: FolderScanner = SafFolderScanner(this.context)
    override val tagReader: TagReader = Media3TagReader(this.context)

    private val audioFingerprinter = AudioFingerprinter(this.context)
    override val fingerprinter = Fingerprinter { song -> audioFingerprinter.fingerprint(Uri.parse(song.uri)) }

    override val deviceName: String = Build.MODEL

    override val folders = object : AppFolders {
        override val covers = File(this@AndroidPlatform.context.filesDir, "mb_covers")
        override val photos: File get() = ProfilePhotos.dir(this@AndroidPlatform.context)
        override val private: File get() = this@AndroidPlatform.context.noBackupFilesDir
    }

    override val images = object : PlatformImages {
        override fun decode(bytes: ByteArray, targetPx: Int): ImageBitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, targetPx) })?.asImageBitmap()
        }.getOrNull()

        override fun embeddedCover(uri: String): ByteArray? = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this@AndroidPlatform.context, Uri.parse(uri))
                r.embeddedPicture
            } finally {
                r.release()
            }
        }.getOrNull()

        override fun jpeg(image: ImageBitmap, quality: Int): ByteArray? = runCatching {
            ByteArrayOutputStream().use { out ->
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        }.getOrNull()

        /** The largest power of two that keeps the image at least [targetPx] wide. */
        private fun sample(width: Int, targetPx: Int): Int {
            var s = 1
            while (width / (s * 2) >= targetPx) s *= 2
            return s
        }
    }

    override val files = object : FileAccess {
        private val resolver get() = this@AndroidPlatform.context.contentResolver

        override fun openRead(file: String): InputStream? = runCatching { resolver.openInputStream(Uri.parse(file)) }.getOrNull()

        override fun openWrite(file: String): OutputStream? = runCatching { resolver.openOutputStream(Uri.parse(file), "wt") }.getOrNull()

        override fun size(file: String): Long? = runCatching {
            resolver.openAssetFileDescriptor(Uri.parse(file), "r")?.use { it.length.takeIf { n -> n >= 0 } }
        }.getOrNull()

        override fun displayName(file: String): String? = runCatching {
            resolver.query(Uri.parse(file), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    override val downloads = object : Downloads {
        override fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            this@AndroidPlatform.context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        override val folder: File? get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    override fun gameAudio(): GameAudio = MediaSessionAudio(context)

    override fun previewPlayer(): PreviewPlayer = ExoPreviewPlayer(context)

    override val canHostInternetRoom: Boolean get() = InternetRoom.supported()

    // "Update" opens the APK's link: the browser downloads it and Android installs it from there.
    override val updatePackage = ".apk"
    override val updateInstaller = null

    override fun internetRoom(identity: RoomIdentity, onMessage: (peer: String, text: String) -> Unit, onLeave: (peer: String) -> Unit): RoomHost? =
        if (InternetRoom.supported()) InternetRoom(context, identity, onMessage, onLeave) else null

    override fun webAsset(path: String): ByteArray? = runCatching { context.assets.open("remote/$path").use { it.readBytes() } }.getOrNull()

    override fun lanAddress(): String? = LocalAddress.find()

    private var wifiLock: WifiManager.WifiLock? = null

    /** Keeps Wi-Fi responsive while a guest page is open, and only then (DECISIONS.md D-109, D-165). */
    @Synchronized
    override fun holdNetwork(hold: Boolean) {
        val lock = wifiLock ?: if (!hold) return else createWifiLock()?.also { wifiLock = it } ?: return
        runCatching { if (hold && !lock.isHeld) lock.acquire() else if (!hold && lock.isHeld) lock.release() }
    }

    private fun createWifiLock(): WifiManager.WifiLock? {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        return runCatching { wifi.createWifiLock(mode, "TrackRecord:remote").apply { setReferenceCounted(false) } }.getOrNull()
    }
}
