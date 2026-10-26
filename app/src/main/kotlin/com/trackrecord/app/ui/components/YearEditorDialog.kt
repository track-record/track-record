package com.trackrecord.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R
import com.trackrecord.engine.year.MIN_PLAYABLE_YEAR

/** Edit year, 1900 to the current year (library song details). */
@Composable
fun YearEditorDialog(
    initialYear: Int?,
    currentYear: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(initialValue(initialYear)) }
    val year = validYear(value.text, currentYear)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.year_editor_title)) },
        text = { YearField(value, currentYear, onChange = { value = it }, onDone = { year?.let(onConfirm) }) },
        confirmButton = {
            TextButton(onClick = { year?.let(onConfirm) }, enabled = year != null) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The same editor inline, for a bottom sheet (the reveal's year sheet). A dialog opened while the sheet
 * was closing got no working keyboard and let the system bars cover it (feedback round 5, D-088).
 */
@Composable
fun YearEditorInline(initialYear: Int?, currentYear: Int, onCancel: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableStateOf(initialValue(initialYear)) }
    val year = validYear(value.text, currentYear)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        YearField(value, currentYear, onChange = { value = it }, onDone = { year?.let(onConfirm) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = { year?.let(onConfirm) }, enabled = year != null) { Text(stringResource(R.string.action_save)) }
        }
    }
}

/** The year text field: focused with the keyboard open and the year selected, so typing replaces it. */
@Composable
private fun YearField(value: TextFieldValue, currentYear: Int, onChange: (TextFieldValue) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focused) {
            focus.requestFocus()
            keyboard?.show()
            focused = true
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.text.length <= 4 && v.text.all(Char::isDigit)) onChange(v) },
        singleLine = true,
        label = { Text(stringResource(R.string.year_editor_label)) },
        isError = value.text.length == 4 && validYear(value.text, currentYear) == null,
        supportingText = {
            Text(stringResource(R.string.year_editor_range, formatYear(MIN_PLAYABLE_YEAR), formatYear(currentYear)))
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

private fun initialValue(year: Int?): TextFieldValue {
    val text = year?.let(::formatYear).orEmpty()
    return TextFieldValue(text, selection = TextRange(0, text.length))
}

private fun validYear(text: String, currentYear: Int): Int? =
    text.toIntOrNull()?.takeIf { text.length == 4 && it in MIN_PLAYABLE_YEAR..currentYear }
