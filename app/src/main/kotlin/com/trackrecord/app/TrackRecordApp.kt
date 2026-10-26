package com.trackrecord.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.trackrecord.app.game.GameController
import com.trackrecord.app.game.RevealSettings
import com.trackrecord.app.library.DeezerImports
import com.trackrecord.app.library.ExportifyWatcher
import com.trackrecord.app.library.LibraryVerifier
import com.trackrecord.app.newgame.SetupStore
import com.trackrecord.app.remote.RemotePlay
import com.trackrecord.app.settings.AppUpdates
import com.trackrecord.app.ui.components.CoverLoader
import com.trackrecord.data.db.AppDatabase
import com.trackrecord.data.db.MbCacheEntity
import com.trackrecord.data.deezer.DeezerAudioResolver
import com.trackrecord.data.deezer.DeezerClient
import com.trackrecord.data.deezer.DeezerImporter
import com.trackrecord.data.deezer.OkHttpLinkResolver
import com.trackrecord.data.library.LibraryRepository
import com.trackrecord.data.library.Media3TagReader
import com.trackrecord.data.library.SafFolderScanner
import com.trackrecord.data.musicbrainz.AcoustIdClient
import com.trackrecord.data.musicbrainz.CoverArtClient
import com.trackrecord.data.musicbrainz.CoverArtService
import com.trackrecord.data.musicbrainz.FingerprintMatcher
import com.trackrecord.data.musicbrainz.Fingerprinter
import com.trackrecord.data.musicbrainz.MbCache
import com.trackrecord.data.musicbrainz.MusicBrainzClient
import com.trackrecord.data.musicbrainz.MusicBrainzLookup
import com.trackrecord.data.musicbrainz.NetLog
import com.trackrecord.data.musicbrainz.OkHttpFetcher
import com.trackrecord.data.musicbrainz.RateLimitedQueue
import com.trackrecord.data.playlists.PlaylistRepository
import com.trackrecord.data.profiles.ProfileRepository
import com.trackrecord.data.settings.AppSettings
import com.trackrecord.data.settings.SettingsRepository
import com.trackrecord.data.sources.AudioResolver
import com.trackrecord.data.stats.PlayerStatsRepository
import com.trackrecord.fingerprint.AudioFingerprinter
import com.trackrecord.playback.GameAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
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
import java.io.File
import java.time.Year

/** Hand-wired dependencies; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    /** For app-wide background work that lives as long as the process. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val currentYear: () -> Int = { Year.now().value }
    val database: AppDatabase = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val library = LibraryRepository(
        database, SafFolderScanner(context), Media3TagReader(context), currentYear,
        yearPreference = settings.appSettings.map { it.sourcesDisagree },
    )

    /** One global queue for every MusicBrainz request (game prefetch, Verify library, Look up). */
    private val audioFingerprinter = AudioFingerprinter(context)

    /** Fingerprint matching through AcoustID (3 requests per second; no contact email is sent there). */
    val fingerprints = FingerprintMatcher(
        client = AcoustIdClient(
            RateLimitedQueue(
                fetcher = OkHttpFetcher("acoustid") { "TrackRecord/${BuildConfig.VERSION_NAME}" },
                clock = SystemClock::elapsedRealtime,
                sleep = { delay(it) },
                minIntervalMs = 334,
            ),
            apiKey = { settings.current().acoustIdKeyInUse },
        ),
        fingerprinter = Fingerprinter { song -> audioFingerprinter.fingerprint(Uri.parse(song.uri)) },
        enabled = { settings.current().let { it.acoustIdKeyInUse.isNotBlank() && it.musicBrainzEnabled && it.contactEmailInUse.isNotBlank() } },
    )

    val musicBrainz = MusicBrainzLookup(
        client = MusicBrainzClient(
            RateLimitedQueue(
                fetcher = OkHttpFetcher { "TrackRecord/${BuildConfig.VERSION_NAME} ( ${settings.current().contactEmailInUse} )" },
                clock = SystemClock::elapsedRealtime,
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
        dir = File(context.filesDir, "mb_covers"),
        lookup = musicBrainz,
        client = CoverArtClient { "TrackRecord/${BuildConfig.VERSION_NAME} ( ${settings.current().contactEmailInUse} )" },
        enabled = { settings.current().let { it.loadCovers && it.musicBrainzEnabled && it.contactEmailInUse.isNotBlank() } },
    )
    val covers = CoverLoader(context, library, musicBrainz, coverArt)
    /** Deezer's public API (v2): its own queue, about 9 requests per second; no contact email is sent. */
    val deezer = DeezerClient(
        RateLimitedQueue(
            fetcher = OkHttpFetcher("deezer") { "TrackRecord/${BuildConfig.VERSION_NAME}" },
            clock = SystemClock::elapsedRealtime,
            sleep = { delay(it) },
            minIntervalMs = DeezerClient.SPACING_MS,
        ),
    )

    /** Local files play as they are; Deezer tracks as their preview, fetched right before playback. */
    val audioResolver: AudioResolver = DeezerAudioResolver(deezer)
    /** Players controlled from a guest's phone (SPEC v3); kept by the remote play server. */
    val remotePlayers = MutableStateFlow<Set<String>>(emptySet())
    /** Newer releases on GitHub (feedback round 47). */
    val updates = AppUpdates(appScope, settings)

    /** Saved players' stats (feedback round 47). */
    val playerStats = PlayerStatsRepository(database)
    val game = GameController(
        context, library, GameAudio(context), database.savedGame(), musicBrainz, coverArt, covers::invalidate, currentYear, audioResolver,
        revealSettings = settings.appSettings.map { RevealSettings(it.autoReveal, it.minVetoTimeSeconds, it.autoRevealDespiteRemote) }.stateIn(appScope, SharingStarted.Eagerly, RevealSettings()),
        remotePlayers = remotePlayers,
        playlistNames = { playlists.playlists.first().associate { it.id to it.name } },
        stats = playerStats,
    )
    /** Remote play: guests' phones as remote controls (SPEC v3); off until a QR code is generated. */
    val remote = RemotePlay(context, game, settings.appSettings, remotePlayers, covers)
    val verifier = LibraryVerifier(library, musicBrainz, coverArt, covers::invalidate)
    val setups = SetupStore(settings)

    init {
        // Check the AcoustID key at start and whenever it changes, so a bad key is reported right away.
        @OptIn(FlowPreview::class)
        appScope.launch {
            settings.appSettings.map { it.acoustIdKeyInUse }.distinctUntilChanged().debounce(800).collectLatest { fingerprints.checkKey(it) }
        }
    }
    val deezerImports = DeezerImports(
        DeezerImporter(database, deezer, OkHttpLinkResolver(), inUse = { game.session.value?.songs?.keys.orEmpty() }, currentYear = currentYear),
    )
    /** Asks the navigation to show the library (a song list was sent to the app and is being imported). */
    val openLibrary = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Exportify's downloads, imported by themselves with the file permission (feedback round 32). */
    val exportify = ExportifyWatcher(context, appScope, busy = { deezerImports.busy }, import = { lists ->
        deezerImports.importLists(lists)
        // Back to the app on the library, which closes Exportify's browser tab above it (singleTask, D-097;
        // feedback round 34), as "Open" in the download message does.
        openLibrary.tryEmit(Unit)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    })
    val profiles = ProfileRepository(database)
    val playlists = PlaylistRepository(database)
}

/**
 * The AcoustID key and MusicBrainz contact in use: the app's own from secrets.properties when the
 * build has them (Settings then doesn't show the fields), else the ones entered in Settings
 * (DECISIONS.md D-177).
 */
val AppSettings.acoustIdKeyInUse: String get() = BuildConfig.ACOUSTID_KEY.ifBlank { acoustIdKey }
val AppSettings.contactEmailInUse: String get() = BuildConfig.MUSICBRAINZ_CONTACT.ifBlank { contactEmail }

class TrackRecordApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        NetLog.enabled = BuildConfig.DEBUG
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as TrackRecordApp).container
