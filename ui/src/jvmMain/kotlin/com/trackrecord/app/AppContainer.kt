package com.trackrecord.app

import androidx.compose.runtime.staticCompositionLocalOf
import com.trackrecord.app.game.GameController
import com.trackrecord.app.game.RevealSettings
import com.trackrecord.app.library.DeezerImports
import com.trackrecord.app.library.ExportifyWatcher
import com.trackrecord.app.library.LibraryVerifier
import com.trackrecord.app.newgame.SetupStore
import com.trackrecord.app.platform.PlatformServices
import com.trackrecord.app.platform.monotonicMs
import com.trackrecord.app.remote.RemotePlay
import com.trackrecord.app.settings.AppUpdates
import com.trackrecord.app.settings.LibraryTransfer
import com.trackrecord.app.ui.components.CoverLoader
import com.trackrecord.data.backup.LibraryBackup
import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.deezer.DeezerAudioResolver
import com.trackrecord.data.deezer.DeezerClient
import com.trackrecord.data.deezer.DeezerImporter
import com.trackrecord.data.deezer.OkHttpLinkResolver
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.musicbrainz.AcoustIdClient
import com.trackrecord.data.musicbrainz.CoverArtClient
import com.trackrecord.data.musicbrainz.CoverArtService
import com.trackrecord.data.musicbrainz.FingerprintMatcher
import com.trackrecord.data.musicbrainz.Fingerprinter
import com.trackrecord.data.musicbrainz.MbCache
import com.trackrecord.data.musicbrainz.MusicBrainzClient
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import com.trackrecord.data.musicbrainz.OkHttpFetcher
import com.trackrecord.data.musicbrainz.RateLimitedQueue
import com.trackrecord.data.playlists.PlaylistRepository
import com.trackrecord.data.profiles.ProfileRepository
import com.trackrecord.data.settings.AppSettings
import com.trackrecord.data.sources.AudioResolver
import com.trackrecord.data.stats.PlayerStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Year

/**
 * Hand-wired dependencies, shared by both apps; the app is small enough not to need a DI framework. What differs
 * between Android and the desktop comes from [platform] (PLATFORMS.md phase 5).
 */
class AppContainer(val platform: PlatformServices) {
    /** For app-wide background work that lives as long as the process. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val currentYear: () -> Int = { Year.now().value }
    val database = platform.database
    val settings = platform.settings
    val library = LibraryRepository(
        database, platform.folderScanner, platform.tagReader, currentYear,
        yearPreference = settings.appSettings.map { it.sourcesDisagree },
    )

    /** Fingerprint matching through AcoustID (3 requests per second; no contact email is sent there). */
    val fingerprints = FingerprintMatcher(
        client = AcoustIdClient(
            RateLimitedQueue(
                fetcher = OkHttpFetcher("acoustid") { "TrackRecord/${AppConfig.current.versionName}" },
                clock = ::monotonicMs,
                sleep = { delay(it) },
                minIntervalMs = 334,
            ),
            apiKey = { settings.current().acoustIdKeyInUse },
        ),
        // Where songs can't be fingerprinted, it is never on and the UI hides it.
        fingerprinter = platform.fingerprinter ?: Fingerprinter { null },
        enabled = { platform.fingerprinter != null && settings.current().let { it.acoustIdKeyInUse.isNotBlank() && it.musicBrainzEnabled && it.contactEmailInUse.isNotBlank() } },
    )

    val musicBrainz = MusicBrainzLookup(
        client = MusicBrainzClient(
            RateLimitedQueue(
                fetcher = OkHttpFetcher { "TrackRecord/${AppConfig.current.versionName} ( ${settings.current().contactEmailInUse} )" },
                clock = ::monotonicMs,
                sleep = { delay(it) },
            ),
        ),
        cache = object : MbCache {
            override suspend fun get(key: String) = database.mbCache().get(key)
            override suspend fun put(entry: MbCacheEntity) = database.mbCache().put(entry)
        },
        enabled = { settings.current().let { it.musicBrainzEnabled && it.contactEmailInUse.isNotBlank() } },
        currentYear = currentYear,
        fingerprints = fingerprints,
    )
    val coverArt = CoverArtService(
        dir = platform.folders.covers,
        lookup = musicBrainz,
        client = CoverArtClient { "TrackRecord/${AppConfig.current.versionName} ( ${settings.current().contactEmailInUse} )" },
        enabled = { settings.current().let { it.loadCovers && it.musicBrainzEnabled && it.contactEmailInUse.isNotBlank() } },
    )
    val covers = CoverLoader(platform.images, library, musicBrainz, coverArt)
    /** Deezer's public API (v2): its own queue, about 9 requests per second; no contact email is sent. */
    val deezer = DeezerClient(
        RateLimitedQueue(
            fetcher = OkHttpFetcher("deezer") { "TrackRecord/${AppConfig.current.versionName}" },
            clock = ::monotonicMs,
            sleep = { delay(it) },
            minIntervalMs = DeezerClient.SPACING_MS,
        ),
    )

    /** Local files play as they are; Deezer tracks as their preview, fetched right before playback. */
    val audioResolver: AudioResolver = DeezerAudioResolver(deezer)
    /** Players controlled from a guest's phone (SPEC v3); kept by the remote play server. */
    val remotePlayers = MutableStateFlow<Set<String>>(emptySet())
    /** Newer releases on GitHub (feedback round 47). */
    val updates = AppUpdates(appScope, settings, platform.updatePackage, platform.updateInstaller)

    /** Saved players' stats (feedback round 47). */
    val playerStats = PlayerStatsRepository(database)
    val playlists = PlaylistRepository(database)
    val game = GameController(
        library, platform.gameAudio(), database.savedGame(), musicBrainz, coverArt, covers::invalidate, currentYear, audioResolver,
        revealSettings = settings.appSettings.map { RevealSettings(it.autoReveal, it.minVetoTimeSeconds, it.autoRevealDespiteRemote, it.autoRevealWhenAllName) }.stateIn(appScope, SharingStarted.Eagerly, RevealSettings()),
        remotePlayers = remotePlayers,
        playlistNames = { playlists.playlists.first().associate { it.id to it.name } },
        stats = playerStats,
    )
    /** Remote play: guests' phones as remote controls (SPEC v3); off until a QR code is generated. */
    val remote = RemotePlay(platform, game, settings.appSettings, remotePlayers, covers, playlists)
    val verifier = LibraryVerifier(library, musicBrainz, coverArt, covers::invalidate)
    val setups = SetupStore(settings)

    init {
        // Check the AcoustID key at start and whenever it changes, so a bad key is reported right away; not where
        // songs can't be fingerprinted.
        @OptIn(FlowPreview::class)
        if (platform.fingerprinter != null) {
            appScope.launch {
                settings.appSettings.map { it.acoustIdKeyInUse }.distinctUntilChanged().debounce(800).collectLatest { fingerprints.checkKey(it) }
            }
        }
    }
    val deezerImports = DeezerImports(
        DeezerImporter(database, deezer, OkHttpLinkResolver(), inUse = { game.session.value?.songs?.keys.orEmpty() }, currentYear = currentYear),
    )
    /** Asks the navigation to show the library (a song list was sent to the app and is being imported). */
    val openLibrary = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Brings the app to the front (Android: its activity, which closes Exportify's browser tab above it; singleTask,
     * D-097). Set by the app.
     */
    var bringToFront: () -> Unit = {}

    /** Exportify's downloads, imported by themselves with the file permission (feedback round 32); null without a downloads folder. */
    val exportify = platform.downloads?.let { downloads ->
        ExportifyWatcher(downloads, appScope, busy = { deezerImports.busy }, import = { lists ->
            deezerImports.importLists(lists)
            // Back to the app on the library (feedback round 34), as "Open" in the download message does.
            openLibrary.tryEmit(Unit)
            bringToFront()
        })
    }
    val profiles = ProfileRepository(database)

    /** Settings › Backup and move (PLATFORMS.md section 5). */
    val libraryTransfer = LibraryTransfer(platform.files, LibraryBackup(database, settings, platform.folders.photos), appScope, platform.deviceName)

    /**
     * Asks the navigation to show Settings (a library export was opened with the app). Kept until the navigation
     * reads it: on a cold start the file arrives before the screens exist.
     */
    val openSettings = Channel<Unit>(Channel.CONFLATED)
}

/**
 * The AcoustID key and MusicBrainz contact in use: the app's own from secrets.properties when the
 * build has them (Settings then doesn't show the fields), else the ones entered in Settings
 * (DECISIONS.md D-177).
 */
val AppSettings.acoustIdKeyInUse: String get() = AppConfig.current.acoustIdKey.ifBlank { acoustIdKey }
val AppSettings.contactEmailInUse: String get() = AppConfig.current.musicBrainzContact.ifBlank { contactEmail }

/** The app's container, for the screens (set by the app around everything it shows). */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("no AppContainer") }
