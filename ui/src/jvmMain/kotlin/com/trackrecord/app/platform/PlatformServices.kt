package com.trackrecord.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import com.trackrecord.app.audio.GameAudio
import com.trackrecord.app.remote.RoomIdentity
import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.library.FolderScanner
import com.trackrecord.data.library.TagReader
import com.trackrecord.data.musicbrainz.Fingerprinter
import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.data.update.ReleasePackage
import com.trackrecord.remote.ListenInfo
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * What differs between the Android and the desktop app (PLATFORMS.md section 2, platform seams). The shared
 * [com.trackrecord.app.AppContainer] builds everything else on these.
 */
interface PlatformServices {
    val database: AppDatabase
    val settings: SettingsRepository
    val folderScanner: FolderScanner
    val tagReader: TagReader

    /** Fingerprints a local song's audio for AcoustID; null where the app can't, and the UI hides it. */
    val fingerprinter: Fingerprinter?
    val folders: AppFolders

    /** This device's name, for library exports. */
    val deviceName: String
    val images: PlatformImages
    val files: FileAccess

    /** Where downloaded song lists (Exportify) arrive; null where the app doesn't watch for them. */
    val downloads: Downloads?

    /** A new player for a game. */
    fun gameAudio(): GameAudio

    /** A new player for the library's preview of a song. */
    fun previewPlayer(): PreviewPlayer

    /** Whether [internetRoom] can run here (Android: a WebView with message ports). */
    val canHostInternetRoom: Boolean

    /** The internet room (SPEC v4): room.js in a hidden browser, joined as [identity]; null where it can't run. */
    fun internetRoom(identity: RoomIdentity, onMessage: (peer: String, text: String) -> Unit, onLeave: (peer: String) -> Unit): RoomHost?

    /** A file of the guest page (web/remote), e.g. "index.html" or "fonts/roboto.woff2"; null if there is none. */
    fun webAsset(path: String): ByteArray?

    /** The address Wi-Fi guests reach the app at, or null without a usable network. */
    fun lanAddress(): String?

    /** Keeps the network responsive while guests are connected (Android's Wi-Fi lock); nothing elsewhere. */
    fun holdNetwork(hold: Boolean) {}

    /** The file a release attaches for this platform, by its extension (".apk", ".msi"). */
    val updatePackage: String

    /** Installs a newer release by itself (the desktop); null where the browser downloads it (Android installs the APK). */
    val updateInstaller: UpdateInstaller?

    /** Whether the system asks the first time guests may reach the app (Windows' firewall), so the invite sheet explains it. */
    val asksForNetworkAccess: Boolean get() = false
}

/** Downloads a release's package and starts installing it (PLATFORMS.md phase 8). */
fun interface UpdateInstaller {
    /** False if the download or the installer's start failed; [onProgress] gets the download's share done (0 to 1). */
    suspend fun install(pkg: ReleasePackage, onProgress: (Float) -> Unit): Boolean
}

/** The app's folders for files of its own. */
interface AppFolders {
    /** Covers downloaded from the Cover Art Archive. */
    val covers: File

    /** Players' photos. */
    val photos: File

    /** Files that never leave the device, not even in a backup (the kept internet room's key). */
    val private: File
}

/** Images the shared code can't decode or encode by itself. */
interface PlatformImages {
    /** [bytes] (JPEG, PNG, …) decoded, scaled down to about [targetPx] wide; null if they aren't an image. */
    fun decode(bytes: ByteArray, targetPx: Int): ImageBitmap?

    /** The cover embedded in a local song's file, as stored there, or null. */
    fun embeddedCover(uri: String): ByteArray?

    /** [image] as a JPEG, for guests' phones. */
    fun jpeg(image: ImageBitmap, quality: Int): ByteArray?
}

/** Files the host picked or opened with the app: content URIs on Android, file URIs on the desktop. */
interface FileAccess {
    fun openRead(file: String): InputStream?

    fun openWrite(file: String): OutputStream?

    /** [file]'s size in bytes, or null if it can't be found out. */
    fun size(file: String): Long?

    /** [file]'s name, or null if it can't be found out. */
    fun displayName(file: String): String?
}

/** The downloads folder, watched for Exportify's lists (feedback round 32). */
interface Downloads {
    /** The app may read it (Android asks for a permission). */
    fun hasAccess(): Boolean

    val folder: File?
}

/** A running internet room (Android: a WebView; desktop: Chromium). */
interface RoomHost {
    /** The invite link: the guest page with the room, secret and the host key's id. */
    val link: String

    fun open()

    /** What the host plays: a local file ([uri]) is streamed to internet guests in step with [listen]. */
    fun follow(listen: ListenInfo?, uri: String?)

    fun send(peer: String, text: String)

    fun close()
}
