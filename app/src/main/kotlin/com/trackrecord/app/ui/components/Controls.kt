package com.trackrecord.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R

/** A labelled value with large − and + buttons. [format] renders the value (e.g. "Off" for null). */
@Composable
fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    hint: String? = null,
    format: @Composable (Int) -> String = { it.toString() },
    /** For word values ("Instant"): a smaller style and wider slot, so the value stays between the buttons. */
    wordValue: Boolean = false,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        val decrease = stringResource(R.string.stepper_decrease, label)
        val increase = stringResource(R.string.stepper_increase, label)
        FilledTonalIconButton(
            onClick = { onChange((value - step).coerceAtLeast(range.first)) },
            enabled = value > range.first,
            modifier = Modifier.size(48.dp).semantics { contentDescription = decrease },
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            format(value),
            style = if (wordValue) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = if (wordValue) Modifier.width(88.dp) else Modifier.widthIn(min = 64.dp),
        )
        FilledTonalIconButton(
            onClick = { onChange((value + step).coerceAtMost(range.last)) },
            enabled = value < range.last,
            modifier = Modifier.size(48.dp).semantics { contentDescription = increase },
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * Stepper for an optional value: below [range].first is "Off" (null).
 */
@Composable
fun OptionalStepper(label: String, value: Int?, range: IntRange, onChange: (Int?) -> Unit, step: Int = 1, hint: String? = null, unit: @Composable (Int) -> String = { it.toString() }) {
    val off = range.first - step
    Stepper(
        label = label,
        value = value ?: off,
        range = off..range.last,
        step = step,
        hint = hint,
        onChange = { onChange(if (it < range.first) null else it) },
        format = { if (it < range.first) stringResourceOff() else unit(it) },
    )
}

@Composable
private fun stringResourceOff() = stringResource(R.string.value_off)

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    // The whole row toggles: large touch target for a phone passed around the table.
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Keeps the screen on while the calling composable is shown (game screens). */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
