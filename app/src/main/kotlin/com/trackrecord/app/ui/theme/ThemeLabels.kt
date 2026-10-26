package com.trackrecord.app.ui.theme

import com.trackrecord.app.R
import com.trackrecord.data.settings.ThemeChoice

/** The theme's name in Settings and on the guest page. */
val ThemeChoice.label: Int
    get() = when (this) {
        ThemeChoice.LIGHT -> R.string.theme_light
        ThemeChoice.DARK -> R.string.theme_dark
        ThemeChoice.LOW_POLY -> R.string.theme_low_poly
    }
