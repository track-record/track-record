package com.trackrecord.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.trackrecord.app.library.readSongLists
import com.trackrecord.app.ui.AppNavHost
import com.trackrecord.app.ui.components.NavigationBarHost
import com.trackrecord.app.ui.components.hideNavigationBarNow
import com.trackrecord.app.ui.theme.TrackRecordTheme
import com.trackrecord.app.ui.theme.isDark
import com.trackrecord.data.settings.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), NavigationBarHost {
    /** Set by the game screens, which hide the navigation bar; the menus keep it (feedback round 31). */
    override var hideNavigationBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) importSongList(intent)
        val settings = (application as TrackRecordApp).container.settings
        setContent {
            val app by settings.appSettings.collectAsState(initial = null)
            val choice = app?.theme ?: ThemeChoice.LOW_POLY
            val dark = choice.isDark()
            // Status bar icons follow the chosen theme, not the system's (Low Poly is always dark).
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                if (hideNavigationBar) hideNavigationBarNow(this@MainActivity)
            }
            TrackRecordTheme(choice, app?.cornerRadiusDp ?: 4, app?.buttonCornerRadiusDp ?: 4) {
                AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSongList(intent)
    }

    /**
     * A song list sent to the app: "Open" on Chrome's download message after an Exportify export, or a
     * file manager. The activity is singleTask, so this also closes the Exportify Custom Tab above it
     * (D-097). The list is imported and the library shows the progress.
     */
    private fun importSongList(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        val container = (application as TrackRecordApp).container
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) { readSongLists(this@MainActivity, uri) }
            // Already imported from Download by the watcher: not twice (feedback round 32).
            val files = container.exportify.notYetImported(read)
            if (read.isNotEmpty() && files.isEmpty()) {
                container.openLibrary.tryEmit(Unit)
            } else if (files.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.list_failed_unreadable, Toast.LENGTH_LONG).show()
            } else {
                container.deezerImports.importLists(files)
                container.openLibrary.tryEmit(Unit)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Back from a dialog or another app: hidden again at once, in a game.
        if (hasFocus && hideNavigationBar) hideNavigationBarNow(this)
    }
}
