package com.trackrecord.app.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackrecord.data.settings.FULL_RADIUS
import com.trackrecord.data.settings.ThemeChoice

/** The corner shape of cards, slots and other components. */
val LocalComponentShape = staticCompositionLocalOf<Shape> { RoundedCornerShape(4.dp) }

/** The corner shape of buttons (own setting; Material3 1.4 doesn't theme button corners). */
val LocalButtonShape = staticCompositionLocalOf<Shape> { RoundedCornerShape(4.dp) }

/**
 * Whether the theme's background scene shows at full strength. Only the home screen asks for it;
 * elsewhere the scene is dimmed so text stays readable (feedback round 6).
 */
class Backdrop {
    var vivid by mutableStateOf(false)
}

val LocalBackdrop = staticCompositionLocalOf { Backdrop() }

/**
 * Colours of song cards without a cover: one fill per song (so neighbouring cards differ, but never
 * look like a player colour) and the text on it. Each theme brings its own (D-099). The guest page
 * picks a card's fill the same way, from the song id (DESIGN-REVIEW TH-03).
 */
sealed class CardColors(val text: Color) {
    abstract fun fillFor(songId: Long): Color

    /** A hue per song at a fixed [saturation] and [value]. */
    class Hue(text: Color, val saturation: Float, val value: Float) : CardColors(text) {
        override fun fillFor(songId: Long): Color = Color.hsv(hueFor(songId), saturation, value)
    }

    /** One of a theme's own deep [colors] per song. */
    class Palette(text: Color, val colors: List<Color>) : CardColors(text) {
        override fun fillFor(songId: Long): Color = colors[indexFor(songId, colors.size)]
    }

    companion object {
        fun hueFor(songId: Long) = ((songId * 137.508) % 360.0).toFloat().let { if (it < 0) it + 360f else it }

        fun indexFor(songId: Long, size: Int) = Math.floorMod(songId * 0x9E3779B1L, size.toLong()).toInt()

        /** Soft pastels with dark text: the light theme. */
        val Pastel = Hue(Color(0xFF1C1B1F), 0.22f, 0.93f)

        /** Deep, muted tones with light text: the dark theme. */
        val Deep = Hue(Color(0xFFF2F0F7), 0.35f, 0.32f)
    }
}

val LocalCardColors = staticCompositionLocalOf<CardColors> { CardColors.Pastel }

/**
 * The shades of panels (DESIGN-REVIEW TH-02: the guest page's): [panel] for cards and sections on a
 * screen, [solid] for bottom bars and sheets, [high] for cards on a sheet and for dialogs.
 */
data class Panels(val panel: Color, val solid: Color, val high: Color)

val LocalPanels = staticCompositionLocalOf { Panels(Color.Unspecified, Color.Unspecified, Color.Unspecified) }

/** The colour [com.trackrecord.app.ui.components.Card] fills with: panels on a screen, [Panels.high] on a sheet. */
val LocalCardFill = staticCompositionLocalOf { Color.Unspecified }

/** Whether the current theme is dark (for system bar icons). */
fun ThemeChoice.isDark(): Boolean = this != ThemeChoice.LIGHT

/**
 * Everything about a theme's look but its scene: the host app and the guest page both take it from
 * here, the page as CSS variables sent with every view (DESIGN-REVIEW TH-01; D-277).
 */
class ThemePalette(
    val colors: ColorScheme,
    val panels: Panels,
    /** Display, headline and large titles: bold, or black for the illustrated theme (TY-02). */
    val headingWeight: FontWeight,
    val cardColors: CardColors,
    /** Illustrated themes: the colour the scene is dimmed with; null without a scene. */
    val dimColor: Color? = null,
) {
    /** Behind everything: the screen's background, or for a scene the colour it is dimmed with. */
    val page: Color get() = if (colors.background.alpha < 1f) dimColor ?: Color.Black else colors.background
}

/** The palette of [this] theme; fixed values, no colours from the wallpaper (Felix, TH-01). */
fun ThemeChoice.palette(): ThemePalette = when (this) {
    ThemeChoice.LOW_POLY -> LowPolyPalette
    ThemeChoice.LIGHT -> LightPalette
    ThemeChoice.DARK -> DarkPalette
}

/**
 * The app theme (feedback rounds 6 and 7; D-172): light, dark, or the illustrated Low Poly.
 * Components share one corner radius ([cornerRadiusDp]), buttons have their own ([buttonRadiusDp]);
 * both are internal settings.
 */
@Composable
fun TrackRecordTheme(choice: ThemeChoice = ThemeChoice.LOW_POLY, cornerRadiusDp: Int = 4, buttonRadiusDp: Int = 4, content: @Composable () -> Unit) {
    val palette = choice.palette()
    val scene: (@Composable () -> Unit)? = if (choice == ThemeChoice.LOW_POLY) ({ LowPolyScene(Modifier.fillMaxSize()) }) else null
    val shape = RoundedCornerShape(cornerRadiusDp.coerceIn(0, 32).dp)
    val shapes = Shapes(extraSmall = shape, small = shape, medium = shape, large = shape, extraLarge = shape)
    val buttonShape = if (buttonRadiusDp >= FULL_RADIUS) RoundedCornerShape(percent = 50) else RoundedCornerShape(buttonRadiusDp.coerceAtLeast(0).dp)
    val backdrop = remember { Backdrop() }
    CompositionLocalProvider(
        LocalComponentShape provides shape,
        LocalButtonShape provides buttonShape,
        LocalBackdrop provides backdrop,
        LocalCardColors provides palette.cardColors,
        LocalPanels provides palette.panels,
        LocalCardFill provides palette.panels.panel,
    ) {
        MaterialTheme(colorScheme = palette.colors, shapes = shapes, typography = appTypography(palette.headingWeight)) {
            // The same tree for every theme, so switching themes keeps the navigation state.
            Box(Modifier.fillMaxSize()) {
                scene?.let {
                    it()
                    val dim by animateFloatAsState(if (backdrop.vivid) 0f else SCENE_DIM, label = "dim")
                    Box(Modifier.fillMaxSize().background((palette.dimColor ?: Color.Black).copy(alpha = dim)))
                }
                content()
            }
        }
    }
}

/** How strongly a scene is dimmed outside the home screen; the guest page dims it as much. */
const val SCENE_DIM = 0.6f
