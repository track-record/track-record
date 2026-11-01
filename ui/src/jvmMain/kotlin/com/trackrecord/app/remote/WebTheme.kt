package com.trackrecord.app.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.trackrecord.app.ui.theme.CardColors
import com.trackrecord.app.ui.theme.SCENE_DIM
import com.trackrecord.app.ui.theme.ThemePalette
import com.trackrecord.app.ui.theme.palette
import com.trackrecord.data.settings.ThemeChoice
import com.trackrecord.remote.WebPalette

/**
 * The guest page's colours, taken from the app's themes and sent with every view (DESIGN-REVIEW TH-01;
 * D-277). app.css holds the same values for the moments before the first view; WebThemeTest checks them.
 */
object WebTheme {
    /** Every theme's palette by name. */
    val palettes: Map<String, WebPalette> by lazy { ThemeChoice.entries.associate { it.name to of(it.palette()) } }

    fun of(p: ThemePalette): WebPalette {
        val c = p.colors
        val colors = linkedMapOf(
            "--bg" to css(p.page),
            "--panel" to css(p.panels.panel),
            "--panel-solid" to css(p.panels.solid),
            "--panel-high" to css(p.panels.high),
            "--panel-highest" to css(c.surfaceContainerHighest),
            "--on" to css(c.onSurface),
            "--on-variant" to css(c.onSurfaceVariant),
            "--primary" to css(c.primary),
            "--on-primary" to css(c.onPrimary),
            "--tonal" to css(c.secondaryContainer),
            "--on-tonal" to css(c.onSecondaryContainer),
            "--outline" to css(c.outline),
            "--outline-variant" to css(c.outlineVariant),
            "--error" to css(c.error),
            "--inverse" to css(c.inverseSurface),
            "--on-inverse" to css(c.inverseOnSurface),
            "--dim" to css(p.dimColor ?: Color.Transparent),
        )
        val cards = p.cardColors
        return WebPalette(
            colors = colors,
            headingWeight = p.headingWeight.weight,
            dim = if (p.dimColor != null) SCENE_DIM else 0f,
            cardText = css(cards.text),
            cardFills = (cards as? CardColors.Palette)?.colors.orEmpty().map(::css),
            cardSaturation = (cards as? CardColors.Hue)?.saturation ?: 0f,
            cardValue = (cards as? CardColors.Hue)?.value ?: 0f,
        )
    }

    /** CSS hex: #RRGGBB, or #RRGGBBAA when see-through. */
    fun css(color: Color): String {
        val argb = color.toArgb()
        val rgb = "#%06X".format(argb and 0xFFFFFF)
        val alpha = (argb ushr 24) and 0xFF
        return if (alpha == 0xFF) rgb else rgb + "%02X".format(alpha)
    }
}
