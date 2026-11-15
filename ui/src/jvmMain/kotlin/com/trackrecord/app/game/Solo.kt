package com.trackrecord.app.game

import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.jokers
import com.trackrecord.app.resources.vetoes
import com.trackrecord.engine.GameState
import org.jetbrains.compose.resources.PluralStringResource

/** Song Order's vetoes as counted on screen: jokers when one player plays alone, as nobody can veto (D-380). */
val GameState.vetoWord: PluralStringResource get() = if (solo) Res.plurals.jokers else Res.plurals.vetoes
