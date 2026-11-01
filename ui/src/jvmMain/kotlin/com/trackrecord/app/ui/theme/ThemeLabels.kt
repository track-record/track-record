package com.trackrecord.app.ui.theme

import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.theme_dark
import com.trackrecord.app.resources.theme_light
import com.trackrecord.app.resources.theme_low_poly
import com.trackrecord.data.settings.ThemeChoice
import org.jetbrains.compose.resources.StringResource

/** The theme's name in Settings and on the guest page. */
val ThemeChoice.label: StringResource
    get() = when (this) {
        ThemeChoice.LIGHT -> Res.string.theme_light
        ThemeChoice.DARK -> Res.string.theme_dark
        ThemeChoice.LOW_POLY -> Res.string.theme_low_poly
    }
