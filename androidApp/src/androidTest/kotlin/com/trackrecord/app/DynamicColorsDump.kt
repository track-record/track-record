package com.trackrecord.app

import android.os.Build
import android.util.Log
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check, a tool: logs the Material You colours this device gives the app, as Kotlin for
 * ui/theme/LightDark.kt (tag "ThemeDump"). Light and Dark are those of Felix's phone, fixed (DESIGN-REVIEW
 * TH-01, D-277); run this on a phone to take another phone's colours.
 */
@RunWith(AndroidJUnit4::class)
class DynamicColorsDump {
    @Test
    fun dumpDynamicColors() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((name, scheme) in listOf("Light" to dynamicLightColorScheme(context), "Dark" to dynamicDarkColorScheme(context))) {
            Log.i("ThemeDump", "$name\n" + roles(scheme).joinToString("\n") { (role, c) -> "    $role = Color(0x%08X),".format(c.toArgb()) })
        }
    }

    private fun roles(s: ColorScheme): List<Pair<String, Color>> = listOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary, "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
        "inversePrimary" to s.inversePrimary, "secondary" to s.secondary, "onSecondary" to s.onSecondary,
        "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer, "tertiary" to s.tertiary,
        "onTertiary" to s.onTertiary, "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
        "background" to s.background, "onBackground" to s.onBackground, "surface" to s.surface, "onSurface" to s.onSurface,
        "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant, "surfaceTint" to s.surfaceTint,
        "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface, "error" to s.error, "onError" to s.onError,
        "errorContainer" to s.errorContainer, "onErrorContainer" to s.onErrorContainer, "outline" to s.outline,
        "outlineVariant" to s.outlineVariant, "scrim" to s.scrim, "surfaceBright" to s.surfaceBright, "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh, "surfaceContainerHighest" to s.surfaceContainerHighest,
        "surfaceContainerLow" to s.surfaceContainerLow, "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceDim" to s.surfaceDim,
    )
}
