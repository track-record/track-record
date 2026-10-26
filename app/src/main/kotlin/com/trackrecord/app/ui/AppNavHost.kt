package com.trackrecord.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.trackrecord.app.appContainer
import com.trackrecord.app.game.GameScreen
import com.trackrecord.app.library.LibraryScreen
import com.trackrecord.app.library.SongDetailScreen
import com.trackrecord.app.newgame.NewGameScreen
import com.trackrecord.app.players.PlayerStatsScreen
import com.trackrecord.app.players.PlayersScreen
import com.trackrecord.app.players.ProfileEditorScreen
import com.trackrecord.app.remote.TakeoverPrompt
import com.trackrecord.app.settings.SettingsScreen
import com.trackrecord.app.ui.theme.LocalBackdrop
import com.trackrecord.engine.Phase
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object LibraryRoute
@Serializable data class SongDetailRoute(val songId: Long)
@Serializable object NewGameRoute
@Serializable object GameRoute
@Serializable object SettingsRoute
@Serializable object PlayersRoute
@Serializable data class ProfileEditRoute(val profileId: Long = -1)
@Serializable data class PlayerStatsRoute(val profileId: Long)

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val container = LocalContext.current.appContainer
    val session by container.game.session.collectAsStateWithLifecycle()
    val toHome = { nav.popBackStack(HomeRoute, inclusive = false) }

    // A song list was sent to the app: show the library, where the import's progress appears.
    LaunchedEffect(container) {
        container.openLibrary.collect { nav.navigate(LibraryRoute) { launchSingleTop = true } }
    }
    // A guest's takeover request reaches the host on any screen (D-182).
    TakeoverPrompt()
    // The home screen shows the theme's scene at full strength; the dim follows the destination at once,
    // not after the home screen's exit animation (feedback round 27).
    val backdrop = LocalBackdrop.current
    val entry by nav.currentBackStackEntryAsState()
    val onHome = entry?.destination?.hasRoute<HomeRoute>() ?: true
    LaunchedEffect(onHome) { backdrop.vivid = onHome }
    NavHost(
        navController = nav,
        startDestination = HomeRoute,
        // Short fades instead of the default 700 ms, so the new screen is ready right away.
        enterTransition = { fadeIn(tween(SCREEN_FADE_MS)) },
        exitTransition = { fadeOut(tween(SCREEN_FADE_MS)) },
        popEnterTransition = { fadeIn(tween(SCREEN_FADE_MS)) },
        popExitTransition = { fadeOut(tween(SCREEN_FADE_MS)) },
    ) {
        composable<HomeRoute> {
            HomeScreen(
                canContinue = session != null && session?.state?.phase != Phase.GAME_OVER,
                onNewGame = { nav.navigate(NewGameRoute) },
                onContinue = { nav.navigate(GameRoute) },
                onLibrary = { nav.navigate(LibraryRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
                onPlayers = { nav.navigate(PlayersRoute) },
            )
        }
        composable<LibraryRoute> {
            LibraryScreen(onBack = { nav.popBackStack() }, onSong = { nav.navigate(SongDetailRoute(it)) })
        }
        composable<SongDetailRoute> { entry ->
            SongDetailScreen(songId = entry.toRoute<SongDetailRoute>().songId, onBack = { nav.popBackStack() })
        }
        composable<NewGameRoute> {
            NewGameScreen(
                onBack = { nav.popBackStack() },
                onStarted = { nav.navigate(GameRoute) { popUpTo(HomeRoute) } },
            )
        }
        composable<PlayersRoute> {
            PlayersScreen(onBack = { nav.popBackStack() }, onEdit = { nav.navigate(ProfileEditRoute(it ?: -1)) }, onStats = { nav.navigate(PlayerStatsRoute(it)) })
        }
        composable<PlayerStatsRoute> { entry ->
            val id = entry.toRoute<PlayerStatsRoute>().profileId
            PlayerStatsScreen(profileId = id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate(ProfileEditRoute(id)) })
        }
        composable<ProfileEditRoute> { entry ->
            val id = entry.toRoute<ProfileEditRoute>().profileId
            ProfileEditorScreen(profileId = id.takeIf { it >= 0 }, onDone = { nav.popBackStack() })
        }
        composable<SettingsRoute> { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable<GameRoute> {
            GameScreen(onExit = { toHome() })
        }
    }
}

private const val SCREEN_FADE_MS = 150
