package com.trackrecord.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.BuildConfig
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.newgame.PlaybackEditor
import com.trackrecord.app.newgame.TIMELINE_PRESET_ORDER
import com.trackrecord.app.newgame.label
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.Stepper
import com.trackrecord.app.ui.components.SwitchRow
import com.trackrecord.app.ui.components.TextButton
import com.trackrecord.app.ui.theme.label
import com.trackrecord.data.musicbrainz.AcoustIdKeyState
import com.trackrecord.data.musicbrainz.AcoustIdKeyStatus
import com.trackrecord.data.settings.AppSettings
import com.trackrecord.data.settings.FULL_RADIUS
import com.trackrecord.data.settings.ThemeChoice
import com.trackrecord.engine.ClosestYearPreset
import com.trackrecord.engine.CommonSettings
import com.trackrecord.engine.reveal.AutoReveal
import com.trackrecord.engine.year.YearPreference
import kotlinx.coroutines.launch

/** App-wide defaults: default preset, MusicBrainz and contact email, playback defaults, language. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalContext.current.appContainer
    val repo = container.settings
    val settings by repo.appSettings.collectAsStateWithLifecycle(initialValue = null)
    val keyStatus by container.fingerprints.keyStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val update = { t: (AppSettings) -> AppSettings -> scope.launch { repo.update(t) }; Unit }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.settings_default_preset), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_default_timeline), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            for (preset in TIMELINE_PRESET_ORDER.filter { it.settings != null }) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = s.defaultPreset == preset, role = Role.RadioButton) {
                        update { it.copy(defaultPreset = preset) }
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.defaultPreset == preset, onClick = null)
                    Text(stringResource(preset.label), modifier = Modifier.padding(start = 8.dp))
                }
            }
            // Closest Year has its own default preset (feedback round 13).
            Text(stringResource(R.string.settings_default_closest), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            for (preset in ClosestYearPreset.entries.filter { it.settings != null }) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = s.defaultClosestPreset == preset, role = Role.RadioButton) {
                        update { it.copy(defaultClosestPreset = preset) }
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.defaultClosestPreset == preset, onClick = null)
                    Text(stringResource(preset.label), modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(stringResource(R.string.settings_default_preset_hint), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // Playback defaults reuse the game editor; MusicBrainz has its own section (feedback round 12).
            PlaybackEditor(
                CommonSettings(autoPlay = s.autoPlay, startOffsetSeconds = s.startOffsetSeconds, clipLimitSeconds = s.clipLimitSeconds),
                includeMusicBrainz = false,
            ) { c ->
                update { it.copy(autoPlay = c.autoPlay, startOffsetSeconds = c.startOffsetSeconds, clipLimitSeconds = c.clipLimitSeconds) }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(stringResource(R.string.settings_musicbrainz_section), style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                stringResource(R.string.settings_musicbrainz),
                s.musicBrainzEnabled,
                { on -> update { it.copy(musicBrainzEnabled = on) } },
                hint = stringResource(R.string.settings_musicbrainz_hint),
            )
            // When tags and MusicBrainz name different years (feedback round 28).
            Text(stringResource(R.string.settings_year_preference), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            for (preference in YearPreference.entries) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = s.sourcesDisagree == preference, role = Role.RadioButton) {
                        update { it.copy(sourcesDisagree = preference) }
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.sourcesDisagree == preference, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(preference.label))
                        Text(stringResource(preference.hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            // Only when the app has no contact address or key of its own (D-177).
            if (BuildConfig.MUSICBRAINZ_CONTACT.isBlank()) ContactEmailField(s.contactEmail) { email -> update { it.copy(contactEmail = email) } }
            if (BuildConfig.ACOUSTID_KEY.isBlank()) AcoustIdKeyField(s.acoustIdKey, keyStatus) { key -> update { it.copy(acoustIdKey = key) } }
            SwitchRow(
                stringResource(R.string.settings_load_covers),
                s.loadCovers,
                { on -> update { it.copy(loadCovers = on) } },
                hint = stringResource(R.string.settings_load_covers_hint),
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(stringResource(R.string.settings_placing), style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                stringResource(R.string.settings_confirm_twice),
                s.confirmByTappingTwice,
                { on -> update { it.copy(confirmByTappingTwice = on) } },
                hint = stringResource(R.string.settings_confirm_twice_hint) + " " + stringResource(R.string.settings_confirm_twice_guests),
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            RevealSection(s, update)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            AppearanceSection(s, update)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            UpdatesSection(s, update)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = true, onClick = null)
                Text(stringResource(R.string.language_english), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** The app's version, the check at start and "Check now" (feedback round 47). */
@Composable
private fun UpdatesSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val updates = LocalContext.current.appContainer.updates
    val status by updates.status.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.titleMedium)
    SwitchRow(
        stringResource(R.string.settings_check_updates),
        s.checkForUpdates,
        { on -> update { it.copy(checkForUpdates = on) } },
        hint = stringResource(R.string.settings_check_updates_hint),
    )
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
            val note = when (val st = status) {
                UpdateStatus.Unknown -> null
                UpdateStatus.Checking -> stringResource(R.string.update_checking)
                UpdateStatus.UpToDate -> stringResource(R.string.update_up_to_date)
                UpdateStatus.Failed -> stringResource(R.string.update_failed)
                is UpdateStatus.Available -> stringResource(R.string.update_available_short, st.release.versionName)
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = updates::check, enabled = status != UpdateStatus.Checking) { Text(stringResource(R.string.settings_check_now)) }
    }
    (status as? UpdateStatus.Available)?.release?.let { release ->
        Button(onClick = { runCatching { uriHandler.openUri(release.apkUrl ?: release.pageUrl) } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.update_download_version, release.versionName))
        }
    }
}

@Composable
private fun AcoustIdKeyField(saved: String, status: AcoustIdKeyStatus, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(saved) }
    // The status belongs to the saved key; while typing (before the check) the hint shows.
    val state = status.state.takeIf { status.key == text.trim() && text.isNotBlank() }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onSave(it.trim())
        },
        label = { Text(stringResource(R.string.settings_acoustid_key)) },
        supportingText = {
            Text(
                stringResource(
                    when (state) {
                        AcoustIdKeyState.CHECKING -> R.string.settings_acoustid_checking
                        AcoustIdKeyState.VALID -> R.string.settings_acoustid_valid
                        AcoustIdKeyState.REJECTED -> R.string.settings_acoustid_rejected
                        AcoustIdKeyState.UNREACHABLE -> R.string.settings_acoustid_unreachable
                        AcoustIdKeyState.NONE, null -> R.string.settings_acoustid_key_hint
                    },
                ),
            )
        },
        isError = state == AcoustIdKeyState.REJECTED,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun ContactEmailField(saved: String, onSave: (String) -> Unit) {
    // Initialized once: re-keying on the stored value would drop keystrokes while saves catch up.
    var text by rememberSaveable { mutableStateOf(saved) }
    val valid = text.isBlank() || Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(text.trim())
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isBlank() || Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(it.trim())) onSave(it.trim())
        },
        label = { Text(stringResource(R.string.settings_contact_email)) },
        supportingText = { Text(stringResource(if (valid) R.string.settings_contact_email_hint else R.string.settings_contact_email_invalid)) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** Auto-reveal and the remote players' veto time (SPEC v3; DECISIONS.md D-104, D-105). */
@Composable
private fun RevealSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Text(stringResource(R.string.settings_reveal), style = MaterialTheme.typography.titleMedium)
    // − and + like the other values (feedback round 12).
    val options = AutoReveal.entries
    Stepper(
        stringResource(R.string.settings_auto_reveal),
        options.indexOf(s.autoReveal),
        options.indices,
        { i -> update { it.copy(autoReveal = options[i]) } },
        hint = stringResource(R.string.settings_auto_reveal_hint),
        format = { i -> stringResource(options[i].label) },
        wordValue = true,
    )
    Text(stringResource(R.string.settings_veto_time, s.minVetoTimeSeconds), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
    Slider(
        value = s.minVetoTimeSeconds.toFloat(),
        onValueChange = { v -> update { it.copy(minVetoTimeSeconds = v.toInt()) } },
        valueRange = 0f..20f,
        steps = 19,
    )
    Text(stringResource(R.string.settings_veto_time_hint), style = MaterialTheme.typography.bodySmall)
    SwitchRow(
        stringResource(R.string.settings_reveal_despite_remote),
        s.autoRevealDespiteRemote,
        { on -> update { it.copy(autoRevealDespiteRemote = on) } },
        hint = stringResource(R.string.settings_reveal_despite_remote_hint),
    )
}

private val AutoReveal.label: Int
    get() = when (this) {
        AutoReveal.OFF -> R.string.auto_reveal_off
        AutoReveal.INSTANT -> R.string.auto_reveal_instant
        AutoReveal.AFTER_5S -> R.string.auto_reveal_5s
        AutoReveal.AFTER_10S -> R.string.auto_reveal_10s
    }

/** Theme and, under "Advanced", the corner radius of all components (feedback round 6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
    SwitchRow(
        stringResource(R.string.settings_show_covers),
        s.showCovers,
        { on -> update { it.copy(showCovers = on) } },
        hint = stringResource(R.string.settings_show_covers_hint),
    )
    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    for (choice in ThemeChoice.entries) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = s.theme == choice, role = Role.RadioButton) { update { it.copy(theme = choice) } }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = s.theme == choice, onClick = null)
            Text(stringResource(choice.label), modifier = Modifier.padding(start = 8.dp))
        }
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }) {
        Text(stringResource(if (advanced) R.string.settings_advanced_hide else R.string.settings_advanced_show))
    }
    if (advanced) {
        Text(stringResource(R.string.settings_corner_radius, s.cornerRadiusDp), style = MaterialTheme.typography.bodyLarge)
        // Changed while dragging so the effect is visible at once.
        Slider(
            value = s.cornerRadiusDp.toFloat(),
            onValueChange = { v -> update { it.copy(cornerRadiusDp = v.toInt()) } },
            valueRange = 0f..24f,
            steps = 23,
        )
        Text(stringResource(R.string.settings_corner_radius_hint), style = MaterialTheme.typography.bodySmall)
        val buttonRadius = s.buttonCornerRadiusDp
        Text(
            if (buttonRadius >= FULL_RADIUS) stringResource(R.string.settings_button_radius_full) else stringResource(R.string.settings_button_radius, buttonRadius),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = buttonRadius.coerceAtMost(FULL_RADIUS).toFloat(),
            onValueChange = { v -> update { it.copy(buttonCornerRadiusDp = v.toInt()) } },
            valueRange = 0f..FULL_RADIUS.toFloat(),
            steps = FULL_RADIUS - 1,
        )
        Text(stringResource(R.string.settings_button_radius_hint), style = MaterialTheme.typography.bodySmall)
    }
}

private val YearPreference.label: Int
    get() = when (this) {
        YearPreference.LOWEST -> R.string.year_preference_lowest
        YearPreference.MUSICBRAINZ -> R.string.year_preference_musicbrainz
        YearPreference.TAGS -> R.string.year_preference_tags
    }

private val YearPreference.hint: Int
    get() = when (this) {
        YearPreference.LOWEST -> R.string.year_preference_lowest_hint
        YearPreference.MUSICBRAINZ -> R.string.year_preference_musicbrainz_hint
        YearPreference.TAGS -> R.string.year_preference_tags_hint
    }
