package com.trackrecord.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.trackrecord.app.platform.AndroidPlatformUi
import com.trackrecord.app.platform.LocalPlatformUi
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.list_failed_unreadable
import com.trackrecord.app.ui.AppNavHost
import com.trackrecord.app.ui.Texts
import com.trackrecord.app.ui.components.NavigationBarHost
import com.trackrecord.app.ui.components.hideNavigationBarNow
import com.trackrecord.app.ui.theme.TrackRecordTheme
import com.trackrecord.app.ui.theme.isDark
import com.trackrecord.data.settings.ThemeChoice
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), NavigationBarHost {
    /** Set by the game screens, which hide the navigation bar; the menus keep it (feedback round 31). */
    override var hideNavigationBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) importSongList(intent)
        val container = (application as TrackRecordApp).container
        val settings = container.settings
        // Pickers, permissions and the share sheet act for this activity.
        val platformUi = AndroidPlatformUi(this)
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
            CompositionLocalProvider(LocalAppContainer provides container, LocalPlatformUi provides platformUi) {
                TrackRecordTheme(choice, app?.cornerRadiusDp ?: 4, app?.buttonCornerRadiusDp ?: 4) {
                    AppNavHost()
                }
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
     * (D-097). The list is imported and the library shows the progress. A library export goes to Settings instead
     * (shared with the desktop's dropped files: [openFiles]).
     */
    private fun importSongList(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        val container = (application as TrackRecordApp).container
        lifecycleScope.launch {
            if (!container.openFiles(listOf(uri.toString()))) {
                Toast.makeText(this@MainActivity, Texts.get(Res.string.list_failed_unreadable), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Back from a dialog or another app: hidden again at once, in a game.
        if (hasFocus && hideNavigationBar) hideNavigationBarNow(this)
    }
}
