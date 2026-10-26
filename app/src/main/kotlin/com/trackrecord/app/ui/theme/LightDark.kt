package com.trackrecord.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Light and Dark. Fixed colours instead of the wallpaper's (Material You), so every phone and every
// guest page shows the same (Felix, DESIGN-REVIEW TH-01; D-277): the ones Felix's phone gave the app on
// 2026-10-26, read with the instrumented DynamicColorsDump.

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1C6586),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC4E7FF),
    onPrimaryContainer = Color(0xFF001E2C),
    inversePrimary = Color(0xFF90CEF3),
    secondary = Color(0xFF4E616D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1E5F4),
    onSecondaryContainer = Color(0xFF0A1E28),
    tertiary = Color(0xFF615A7D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6DEFF),
    onTertiaryContainer = Color(0xFF1D1736),
    background = Color(0xFFF6FAFF),
    onBackground = Color(0xFF161C21),
    surface = Color(0xFFF6FAFF),
    onSurface = Color(0xFF161C21),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484D),
    surfaceTint = Color(0xFF1C6586),
    inverseSurface = Color(0xFF2A3136),
    inverseOnSurface = Color(0xFFEBF2F8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF70777C),
    outlineVariant = Color(0xFFC0C7CD),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FAFF),
    surfaceContainer = Color(0xFFE8EFF6),
    surfaceContainerHigh = Color(0xFFE2E9F0),
    surfaceContainerHighest = Color(0xFFDDE3EA),
    surfaceContainerLow = Color(0xFFEEF4FB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD4DBE2),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF90CEF3),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004C69),
    onPrimaryContainer = Color(0xFFC4E7FF),
    inversePrimary = Color(0xFF1C6586),
    secondary = Color(0xFFB5C9D7),
    onSecondary = Color(0xFF20333E),
    secondaryContainer = Color(0xFF374955),
    onSecondaryContainer = Color(0xFFD1E5F4),
    tertiary = Color(0xFFCAC1E9),
    onTertiary = Color(0xFF322C4C),
    tertiaryContainer = Color(0xFF494264),
    onTertiaryContainer = Color(0xFFE6DEFF),
    background = Color(0xFF0D1419),
    onBackground = Color(0xFFDDE3EA),
    surface = Color(0xFF0D1419),
    onSurface = Color(0xFFDDE3EA),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC0C7CD),
    surfaceTint = Color(0xFF90CEF3),
    inverseSurface = Color(0xFFDDE3EA),
    inverseOnSurface = Color(0xFF2A3136),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF8B9297),
    outlineVariant = Color(0xFF41484D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333A40),
    surfaceContainer = Color(0xFF1A2026),
    surfaceContainerHigh = Color(0xFF242B30),
    surfaceContainerHighest = Color(0xFF2F363B),
    surfaceContainerLow = Color(0xFF161C21),
    surfaceContainerLowest = Color(0xFF080F14),
    surfaceDim = Color(0xFF0D1419),
)

/** Panels as on the guest page (TH-02): cards and bars in the container shade, cards on sheets a step higher. */
private fun plain(colors: ColorScheme, cards: CardColors) = ThemePalette(
    colors = colors,
    panels = Panels(panel = colors.surfaceContainer, solid = colors.surfaceContainer, high = colors.surfaceContainerHigh),
    headingWeight = FontWeight.Bold,
    cardColors = cards,
)

val LightPalette: ThemePalette = plain(LightColors, CardColors.Pastel)

val DarkPalette: ThemePalette = plain(DarkColors, CardColors.Deep)
