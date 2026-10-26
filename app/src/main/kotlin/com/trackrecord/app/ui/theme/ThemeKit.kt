package com.trackrecord.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.trackrecord.app.R

/**
 * Roboto, shipped with the app and the guest page so both look the same on every device (DESIGN-REVIEW
 * TY-01): one variable font, a Latin subset of Roboto 3 (SIL Open Font License, assets/remote/fonts).
 */
val Roboto = FontFamily(
    (100..900 step 100).map { w -> Font(R.font.roboto, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) },
)

/**
 * Material's type scale in [Roboto], with display, headline and the large title in [headingWeight]
 * (bold, or black for Low Poly; DESIGN-REVIEW TY-02). The guest page uses the same sizes.
 */
fun appTypography(headingWeight: FontWeight): Typography {
    val base = Typography()
    fun TextStyle.roboto() = copy(fontFamily = Roboto)
    fun TextStyle.heading() = copy(fontFamily = Roboto, fontWeight = headingWeight)
    return Typography(
        displayLarge = base.displayLarge.heading(),
        displayMedium = base.displayMedium.heading(),
        displaySmall = base.displaySmall.heading(),
        headlineLarge = base.headlineLarge.heading(),
        headlineMedium = base.headlineMedium.heading(),
        headlineSmall = base.headlineSmall.heading(),
        titleLarge = base.titleLarge.heading(),
        titleMedium = base.titleMedium.roboto(),
        titleSmall = base.titleSmall.roboto(),
        bodyLarge = base.bodyLarge.roboto(),
        bodyMedium = base.bodyMedium.roboto(),
        bodySmall = base.bodySmall.roboto(),
        labelLarge = base.labelLarge.roboto(),
        labelMedium = base.labelMedium.roboto(),
        labelSmall = base.labelSmall.roboto(),
    )
}

/**
 * A dark colour scheme for an illustrated theme: the screen background is transparent (the scene
 * shows), lists and cards are [panel]-tinted and slightly see-through, dialogs and sheets solid.
 */
fun illustratedScheme(
    primary: Color,
    onPrimary: Color,
    secondary: Color,
    onSecondary: Color,
    tertiary: Color,
    onTertiary: Color,
    panel: Color,
    onPanel: Color,
    onPanelVariant: Color,
    outline: Color,
    error: Color = Color(0xFFFF6B6B),
) = androidx.compose.material3.darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = lerp(panel, primary, 0.4f),
    onPrimaryContainer = lerp(Color.White, primary, 0.15f),
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = lerp(panel, secondary, 0.4f),
    onSecondaryContainer = lerp(Color.White, secondary, 0.15f),
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = lerp(panel, tertiary, 0.4f),
    onTertiaryContainer = lerp(Color.White, tertiary, 0.15f),
    background = Color.Transparent,
    onBackground = onPanel,
    surface = panel.copy(alpha = 0.78f),
    onSurface = onPanel,
    surfaceVariant = lerp(panel, onPanel, 0.08f).copy(alpha = 0.86f),
    onSurfaceVariant = onPanelVariant,
    surfaceContainerLowest = panel,
    surfaceContainerLow = lerp(panel, onPanel, 0.03f),
    surfaceContainer = lerp(panel, onPanel, 0.05f).copy(alpha = 0.94f),
    surfaceContainerHigh = lerp(panel, onPanel, 0.07f),
    surfaceContainerHighest = lerp(panel, onPanel, 0.10f).copy(alpha = 0.88f),
    inverseSurface = onPanel,
    inverseOnSurface = panel,
    outline = outline,
    outlineVariant = lerp(panel, outline, 0.5f),
    error = error,
    onError = Color.Black,
    scrim = Color.Black,
)
