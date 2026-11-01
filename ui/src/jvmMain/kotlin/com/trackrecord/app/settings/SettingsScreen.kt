package com.trackrecord.app.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.AppConfig
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.newgame.PlaybackEditor
import com.trackrecord.app.newgame.TIMELINE_PRESET_ORDER
import com.trackrecord.app.newgame.label
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.action_back
import com.trackrecord.app.resources.auto_reveal_10s
import com.trackrecord.app.resources.auto_reveal_5s
import com.trackrecord.app.resources.auto_reveal_instant
import com.trackrecord.app.resources.auto_reveal_off
import com.trackrecord.app.resources.language_english
import com.trackrecord.app.resources.settings_acoustid_checking
import com.trackrecord.app.resources.settings_acoustid_key
import com.trackrecord.app.resources.settings_acoustid_key_hint
import com.trackrecord.app.resources.settings_acoustid_rejected
import com.trackrecord.app.resources.settings_acoustid_unreachable
import com.trackrecord.app.resources.settings_acoustid_valid
import com.trackrecord.app.resources.settings_advanced_hide
import com.trackrecord.app.resources.settings_advanced_show
import com.trackrecord.app.resources.settings_appearance
import com.trackrecord.app.resources.settings_auto_reveal
import com.trackrecord.app.resources.settings_auto_reveal_hint
import com.trackrecord.app.resources.settings_button_radius
import com.trackrecord.app.resources.settings_button_radius_full
import com.trackrecord.app.resources.settings_button_radius_hint
import com.trackrecord.app.resources.settings_check_now
import com.trackrecord.app.resources.settings_check_updates
import com.trackrecord.app.resources.settings_check_updates_hint
import com.trackrecord.app.resources.settings_confirm_twice
import com.trackrecord.app.resources.settings_confirm_twice_guests
import com.trackrecord.app.resources.settings_confirm_twice_hint
import com.trackrecord.app.resources.settings_contact_email
import com.trackrecord.app.resources.settings_contact_email_hint
import com.trackrecord.app.resources.settings_contact_email_invalid
import com.trackrecord.app.resources.settings_corner_radius
import com.trackrecord.app.resources.settings_corner_radius_hint
import com.trackrecord.app.resources.settings_default_closest
import com.trackrecord.app.resources.settings_default_preset
import com.trackrecord.app.resources.settings_default_preset_hint
import com.trackrecord.app.resources.settings_default_timeline
import com.trackrecord.app.resources.settings_internet
import com.trackrecord.app.resources.settings_internet_keep_link
import com.trackrecord.app.resources.settings_internet_keep_link_hint
import com.trackrecord.app.resources.settings_language
import com.trackrecord.app.resources.settings_load_covers
import com.trackrecord.app.resources.settings_load_covers_hint
import com.trackrecord.app.resources.settings_musicbrainz
import com.trackrecord.app.resources.settings_musicbrainz_hint
import com.trackrecord.app.resources.settings_musicbrainz_section
import com.trackrecord.app.resources.settings_placing
import com.trackrecord.app.resources.settings_reveal
import com.trackrecord.app.resources.settings_reveal_all_name
import com.trackrecord.app.resources.settings_reveal_all_name_hint
import com.trackrecord.app.resources.settings_reveal_despite_remote
import com.trackrecord.app.resources.settings_reveal_despite_remote_hint
import com.trackrecord.app.resources.settings_show_covers
import com.trackrecord.app.resources.settings_show_covers_hint
import com.trackrecord.app.resources.settings_theme
import com.trackrecord.app.resources.settings_title
import com.trackrecord.app.resources.settings_updates
import com.trackrecord.app.resources.settings_version
import com.trackrecord.app.resources.settings_veto_time
import com.trackrecord.app.resources.settings_veto_time_hint
import com.trackrecord.app.resources.settings_year_preference
import com.trackrecord.app.resources.update_available_short
import com.trackrecord.app.resources.update_checking
import com.trackrecord.app.resources.update_download_version
import com.trackrecord.app.resources.update_downloading
import com.trackrecord.app.resources.update_failed
import com.trackrecord.app.resources.update_install_failed
import com.trackrecord.app.resources.update_install_version
import com.trackrecord.app.resources.update_up_to_date
import com.trackrecord.app.resources.year_preference_lowest
import com.trackrecord.app.resources.year_preference_lowest_hint
import com.trackrecord.app.resources.year_preference_musicbrainz
import com.trackrecord.app.resources.year_preference_musicbrainz_hint
import com.trackrecord.app.resources.year_preference_tags
import com.trackrecord.app.resources.year_preference_tags_hint
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.PanelGap
import com.trackrecord.app.ui.components.SectionCard
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** App-wide defaults: default preset, MusicBrainz and contact email, playback defaults, language. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val repo = container.settings
    val settings by repo.appSettings.collectAsStateWithLifecycle(initialValue = null)
    val keyStatus by container.fingerprints.keyStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val update = { t: (AppSettings) -> AppSettings -> scope.launch { repo.update(t) }; Unit }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        // Each section in its panel (feedback round 58).
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(PanelGap),
        ) {
            SectionCard(stringResource(Res.string.settings_default_preset), spacing = 4.dp) {
                Text(stringResource(Res.string.settings_default_timeline), style = MaterialTheme.typography.bodyLarge)
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
                Text(stringResource(Res.string.settings_default_closest), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
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
                Text(stringResource(Res.string.settings_default_preset_hint), style = MaterialTheme.typography.bodySmall)
            }

            // Playback defaults reuse the game editor; MusicBrainz has its own section (feedback round 12).
            PlaybackEditor(
                CommonSettings(autoPlay = s.autoPlay, startOffsetSeconds = s.startOffsetSeconds, clipLimitSeconds = s.clipLimitSeconds),
                includeMusicBrainz = false,
            ) { c ->
                update { it.copy(autoPlay = c.autoPlay, startOffsetSeconds = c.startOffsetSeconds, clipLimitSeconds = c.clipLimitSeconds) }
            }

            SectionCard(stringResource(Res.string.settings_musicbrainz_section), spacing = 4.dp) {
                SwitchRow(
                    stringResource(Res.string.settings_musicbrainz),
                    s.musicBrainzEnabled,
                    { on -> update { it.copy(musicBrainzEnabled = on) } },
                    hint = stringResource(Res.string.settings_musicbrainz_hint),
                )
                // When tags and MusicBrainz name different years (feedback round 28).
                Text(stringResource(Res.string.settings_year_preference), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
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
                if (AppConfig.current.musicBrainzContact.isBlank()) ContactEmailField(s.contactEmail) { email -> update { it.copy(contactEmail = email) } }
                // Not where songs can't be fingerprinted.
                if (AppConfig.current.acoustIdKey.isBlank() && container.platform.fingerprinter != null) AcoustIdKeyField(s.acoustIdKey, keyStatus) { key -> update { it.copy(acoustIdKey = key) } }
                SwitchRow(
                    stringResource(Res.string.settings_load_covers),
                    s.loadCovers,
                    { on -> update { it.copy(loadCovers = on) } },
                    hint = stringResource(Res.string.settings_load_covers_hint),
                )
            }

            SectionCard(stringResource(Res.string.settings_placing), spacing = 4.dp) {
                SwitchRow(
                    stringResource(Res.string.settings_confirm_twice),
                    s.confirmByTappingTwice,
                    { on -> update { it.copy(confirmByTappingTwice = on) } },
                    hint = stringResource(Res.string.settings_confirm_twice_hint) + " " + stringResource(Res.string.settings_confirm_twice_guests),
                )
            }
            RevealSection(s, update)
            InternetSection(s, update)
            AppearanceSection(s, update)
            UpdatesSection(s, update)
            BackupSection()
            SectionCard(stringResource(Res.string.settings_language), spacing = 4.dp) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = true, onClick = null)
                    Text(stringResource(Res.string.language_english), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** The app's version, the check at start and "Check now" (feedback round 47). */
@Composable
private fun UpdatesSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val updates = LocalAppContainer.current.updates
    val status by updates.status.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    SectionCard(stringResource(Res.string.settings_updates), spacing = 4.dp) {
        SwitchRow(
            stringResource(Res.string.settings_check_updates),
            s.checkForUpdates,
            { on -> update { it.copy(checkForUpdates = on) } },
            hint = stringResource(Res.string.settings_check_updates_hint),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.settings_version, AppConfig.current.versionName), style = MaterialTheme.typography.bodyLarge)
                val note = when (val st = status) {
                    UpdateStatus.Unknown -> null
                    UpdateStatus.Checking -> stringResource(Res.string.update_checking)
                    UpdateStatus.UpToDate -> stringResource(Res.string.update_up_to_date)
                    UpdateStatus.Failed -> stringResource(Res.string.update_failed)
                    is UpdateStatus.Available -> stringResource(Res.string.update_available_short, st.release.versionName)
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = updates::check, enabled = status != UpdateStatus.Checking) { Text(stringResource(Res.string.settings_check_now)) }
        }
        (status as? UpdateStatus.Available)?.let { available ->
            val release = available.release
            Button(
                onClick = { updates.update { runCatching { uriHandler.openUri(it) } } },
                enabled = available.progress == null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(if (updates.installsItself(release)) Res.string.update_install_version else Res.string.update_download_version, release.versionName))
            }
            UpdateProgress(available, Modifier.padding(top = 8.dp))
        }
    }
}

/** The update's download while the app installs it itself (the desktop), or that it failed. */
@Composable
fun UpdateProgress(available: UpdateStatus.Available, modifier: Modifier = Modifier) {
    val progress = available.progress
    when {
        progress != null -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.update_downloading), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
        }
        available.failed -> Text(stringResource(Res.string.update_install_failed), style = MaterialTheme.typography.bodySmall, modifier = modifier)
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
        label = { Text(stringResource(Res.string.settings_acoustid_key)) },
        supportingText = {
            Text(
                stringResource(
                    when (state) {
                        AcoustIdKeyState.CHECKING -> Res.string.settings_acoustid_checking
                        AcoustIdKeyState.VALID -> Res.string.settings_acoustid_valid
                        AcoustIdKeyState.REJECTED -> Res.string.settings_acoustid_rejected
                        AcoustIdKeyState.UNREACHABLE -> Res.string.settings_acoustid_unreachable
                        AcoustIdKeyState.NONE, null -> Res.string.settings_acoustid_key_hint
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
        label = { Text(stringResource(Res.string.settings_contact_email)) },
        supportingText = { Text(stringResource(if (valid) Res.string.settings_contact_email_hint else Res.string.settings_contact_email_invalid)) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** Auto-reveal and the remote players' veto time (SPEC v3; DECISIONS.md D-104, D-105). */
@Composable
private fun RevealSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard(stringResource(Res.string.settings_reveal), spacing = 4.dp) {
        // − and + like the other values (feedback round 12).
        val options = AutoReveal.entries
        Stepper(
            stringResource(Res.string.settings_auto_reveal),
            options.indexOf(s.autoReveal),
            options.indices,
            { i -> update { it.copy(autoReveal = options[i]) } },
            hint = stringResource(Res.string.settings_auto_reveal_hint),
            format = { i -> stringResource(options[i].label) },
            wordValue = true,
        )
        Text(stringResource(Res.string.settings_veto_time, s.minVetoTimeSeconds), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        Slider(
            value = s.minVetoTimeSeconds.toFloat(),
            onValueChange = { v -> update { it.copy(minVetoTimeSeconds = v.toInt()) } },
            valueRange = 0f..20f,
            steps = 19,
        )
        Text(stringResource(Res.string.settings_veto_time_hint), style = MaterialTheme.typography.bodySmall)
        SwitchRow(
            stringResource(Res.string.settings_reveal_despite_remote),
            s.autoRevealDespiteRemote,
            { on -> update { it.copy(autoRevealDespiteRemote = on) } },
            hint = stringResource(Res.string.settings_reveal_despite_remote_hint),
        )
        SwitchRow(
            stringResource(Res.string.settings_reveal_all_name),
            s.autoRevealWhenAllName,
            { on -> update { it.copy(autoRevealWhenAllName = on) } },
            hint = stringResource(Res.string.settings_reveal_all_name_hint),
        )
    }
}

/** Internet play: one link for every game when wanted (feedback round 50). */
@Composable
private fun InternetSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard(stringResource(Res.string.settings_internet), spacing = 4.dp) {
        SwitchRow(
            stringResource(Res.string.settings_internet_keep_link),
            s.keepInternetLink,
            { on -> update { it.copy(keepInternetLink = on) } },
            hint = stringResource(Res.string.settings_internet_keep_link_hint),
        )
    }
}

private val AutoReveal.label: StringResource
    get() = when (this) {
        AutoReveal.OFF -> Res.string.auto_reveal_off
        AutoReveal.INSTANT -> Res.string.auto_reveal_instant
        AutoReveal.AFTER_5S -> Res.string.auto_reveal_5s
        AutoReveal.AFTER_10S -> Res.string.auto_reveal_10s
    }

/** Theme and, under "Advanced", the corner radius of all components (feedback round 6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard(stringResource(Res.string.settings_appearance), spacing = 4.dp) {
        SwitchRow(
            stringResource(Res.string.settings_show_covers),
            s.showCovers,
            { on -> update { it.copy(showCovers = on) } },
            hint = stringResource(Res.string.settings_show_covers_hint),
        )
        Text(stringResource(Res.string.settings_theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
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
            Text(stringResource(if (advanced) Res.string.settings_advanced_hide else Res.string.settings_advanced_show))
        }
        if (advanced) {
            Text(stringResource(Res.string.settings_corner_radius, s.cornerRadiusDp), style = MaterialTheme.typography.bodyLarge)
            // Changed while dragging so the effect is visible at once.
            Slider(
                value = s.cornerRadiusDp.toFloat(),
                onValueChange = { v -> update { it.copy(cornerRadiusDp = v.toInt()) } },
                valueRange = 0f..24f,
                steps = 23,
            )
            Text(stringResource(Res.string.settings_corner_radius_hint), style = MaterialTheme.typography.bodySmall)
            val buttonRadius = s.buttonCornerRadiusDp
            Text(
                if (buttonRadius >= FULL_RADIUS) stringResource(Res.string.settings_button_radius_full) else stringResource(Res.string.settings_button_radius, buttonRadius),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = buttonRadius.coerceAtMost(FULL_RADIUS).toFloat(),
                onValueChange = { v -> update { it.copy(buttonCornerRadiusDp = v.toInt()) } },
                valueRange = 0f..FULL_RADIUS.toFloat(),
                steps = FULL_RADIUS - 1,
            )
            Text(stringResource(Res.string.settings_button_radius_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val YearPreference.label: StringResource
    get() = when (this) {
        YearPreference.LOWEST -> Res.string.year_preference_lowest
        YearPreference.MUSICBRAINZ -> Res.string.year_preference_musicbrainz
        YearPreference.TAGS -> Res.string.year_preference_tags
    }

private val YearPreference.hint: StringResource
    get() = when (this) {
        YearPreference.LOWEST -> Res.string.year_preference_lowest_hint
        YearPreference.MUSICBRAINZ -> Res.string.year_preference_musicbrainz_hint
        YearPreference.TAGS -> Res.string.year_preference_tags_hint
    }
