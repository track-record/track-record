package com.trackrecord.app.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trackrecord.app.LocalAppContainer
import com.trackrecord.app.resources.Res
import com.trackrecord.app.resources.acoustid_rejected_warning
import com.trackrecord.app.resources.action_back
import com.trackrecord.app.resources.ic_stop
import com.trackrecord.app.resources.lookup_button
import com.trackrecord.app.resources.lookup_no_match
import com.trackrecord.app.resources.lookup_offline
import com.trackrecord.app.resources.lookup_running
import com.trackrecord.app.resources.lookup_running_fingerprint
import com.trackrecord.app.resources.missing_year
import com.trackrecord.app.resources.playback_open_deezer
import com.trackrecord.app.resources.result_cover
import com.trackrecord.app.resources.song_checked_fingerprint_no_match
import com.trackrecord.app.resources.song_checked_name_no_match
import com.trackrecord.app.resources.song_checked_old_version_no_match
import com.trackrecord.app.resources.song_deezer_info
import com.trackrecord.app.resources.song_detail_title
import com.trackrecord.app.resources.song_edit_year
import com.trackrecord.app.resources.song_effective_year
import com.trackrecord.app.resources.song_exclude
import com.trackrecord.app.resources.song_exclude_hint
import com.trackrecord.app.resources.song_file_info
import com.trackrecord.app.resources.song_manual_year
import com.trackrecord.app.resources.song_musicbrainz_year
import com.trackrecord.app.resources.song_preview
import com.trackrecord.app.resources.song_preview_stop
import com.trackrecord.app.resources.song_read_failed
import com.trackrecord.app.resources.song_reset_year
import com.trackrecord.app.resources.song_tag_original_year
import com.trackrecord.app.resources.song_tag_year
import com.trackrecord.app.resources.song_verification_none
import com.trackrecord.app.resources.song_verified_fingerprint
import com.trackrecord.app.resources.song_verified_name
import com.trackrecord.app.resources.song_verified_old_version
import com.trackrecord.app.resources.song_via_fingerprint
import com.trackrecord.app.resources.song_year_source
import com.trackrecord.app.resources.unknown_artist
import com.trackrecord.app.resources.year_none
import com.trackrecord.app.resources.year_source_manual
import com.trackrecord.app.resources.year_source_musicbrainz
import com.trackrecord.app.resources.year_source_tag
import com.trackrecord.app.resources.year_source_tag_original
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.Card
import com.trackrecord.app.ui.components.FilledTonalButton
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.app.ui.components.PanelGap
import com.trackrecord.app.ui.components.YearEditorInline
import com.trackrecord.app.ui.components.formatDuration
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.app.ui.components.rememberSongCover
import com.trackrecord.app.ui.theme.LocalComponentShape
import com.trackrecord.app.ui.theme.caution
import com.trackrecord.data.db.SongSource
import com.trackrecord.data.library.LibrarySong
import com.trackrecord.data.library.Verification
import com.trackrecord.data.musicbrainz.AcoustIdKeyState
import com.trackrecord.data.musicbrainz.AcoustIdKeyStatus
import com.trackrecord.engine.year.YearSource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailScreen(songId: Long, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: SongDetailViewModel = viewModel(
        key = "song-$songId",
        factory = viewModelFactory {
            initializer { SongDetailViewModel(container.library, container.musicBrainz, container.platform.previewPlayer(), songId, container.currentYear(), container.audioResolver) }
        },
    )
    val song by vm.song.collectAsStateWithLifecycle()
    val previewing by vm.previewing.collectAsStateWithLifecycle()
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val keyStatus by container.fingerprints.keyStatus.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(vm) { onDispose { vm.stopPreview() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.song_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val s = song ?: return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            // The song, its year and what can be done with it, each in a panel.
            verticalArrangement = Arrangement.spacedBy(PanelGap),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cover = rememberSongCover(s.id)
                    if (cover != null) {
                        Image(cover, contentDescription = stringResource(Res.string.result_cover), contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(LocalComponentShape.current))
                    }
                    Text(s.song.title, style = MaterialTheme.typography.headlineMedium)
                    Text(s.song.artist ?: stringResource(Res.string.unknown_artist), style = MaterialTheme.typography.titleMedium)
                    s.song.album?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    Text(
                        if (s.song.source == SongSource.DEEZER) stringResource(Res.string.song_deezer_info, formatDuration(s.song.durationMs))
                        else stringResource(Res.string.song_file_info, s.song.relativePath, formatDuration(s.song.durationMs)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s.song.readFailed) {
                        Text(stringResource(Res.string.song_read_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    YearSourcesTable(s)
                    LookupSection(lookup, keyStatus, onLookUp = { vm.lookUp() })
                    // Edited in place, like at the reveal (D-091): no dialog window over the screen.
                    if (editing) YearEditorInline(
                        initialYear = s.resolved.year,
                        currentYear = vm.currentYear,
                        onCancel = { editing = false },
                        onConfirm = { y -> vm.setManualYear(y); editing = false },
                    ) else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { editing = true }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.song_edit_year))
                        }
                        if (s.edited) {
                            OutlinedButton(onClick = { vm.resetToAutomatic() }) { Text(stringResource(Res.string.song_reset_year)) }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.song_exclude), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(Res.string.song_exclude_hint), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = s.excluded, onCheckedChange = { vm.setExcluded(it) })
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = vm::togglePreview) {
                            if (previewing) {
                                Icon(painterResource(Res.drawable.ic_stop), contentDescription = null)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (previewing) Res.string.song_preview_stop else Res.string.song_preview))
                        }
                        // A streamed song: the whole track in Deezer, as on the reveal screen.
                        val deezerId = s.song.deezerId
                        if (s.song.source == SongSource.DEEZER && deezerId != null) {
                            val uriHandler = LocalUriHandler.current
                            // Filled like Preview: an outlined button nearly vanished on the backdrop.
                            FilledTonalButton(onClick = {
                                if (previewing) vm.togglePreview()
                                runCatching { uriHandler.openUri("https://www.deezer.com/track/$deezerId") }
                            }) { Text(stringResource(Res.string.playback_open_deezer)) }
                        }
                    }
                }
            }
        }
    }
}

/** "Look up" with how the last one went (song details, and the reveal's year card for a song not verified, D-382). */
@Composable
fun LookupSection(state: LookupUi, keyStatus: AcoustIdKeyStatus, onLookUp: () -> Unit) {
    AcoustIdWarning(keyStatus)
    when (state) {
        LookupUi.Running -> Text(
            stringResource(if (keyStatus.fingerprinting) Res.string.lookup_running_fingerprint else Res.string.lookup_running),
            style = MaterialTheme.typography.bodyMedium,
        )
        LookupUi.NoMatch -> Text(stringResource(Res.string.lookup_no_match), style = MaterialTheme.typography.bodyMedium)
        LookupUi.Off -> Text(lookupsOffText(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        LookupUi.Offline -> Text(stringResource(Res.string.lookup_offline), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        is LookupUi.Refused -> Text(serviceUnavailableText(state.service), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        LookupUi.Idle -> Unit
    }
    OutlinedButton(onClick = onLookUp, enabled = state != LookupUi.Running) { Text(stringResource(Res.string.lookup_button)) }
}

/** Effective year with its source and every year source (library detail and the reveal's year sheet). */
@Composable
fun YearSourcesTable(s: LibrarySong) {
    Text(stringResource(Res.string.song_effective_year), style = MaterialTheme.typography.titleMedium)
    val year = s.resolved.year
    Text(
        year?.let(::formatYear) ?: stringResource(Res.string.missing_year),
        style = MaterialTheme.typography.displayMedium,
        color = if (year == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    if (year != null) Text(stringResource(Res.string.song_year_source, stringResource(s.resolved.source.label)))
    // How MusicBrainz checked the song: not yet, by name, or by fingerprint. Checked under older rules, its year still
    // counts but may be wrong: in yellow until it is verified again (D-382).
    val outdated = if (s.checkedByOldRules) MaterialTheme.colorScheme.caution else Color.Unspecified
    Text(stringResource(verificationLabel(s)), style = MaterialTheme.typography.bodySmall, color = outdated)
    YearLine(stringResource(Res.string.song_tag_original_year), s.song.tagOriginalYear)
    YearLine(stringResource(Res.string.song_tag_year), s.song.tagYear)
    val mbYear = s.musicBrainzYear ?: s.lookup?.year
    YearLine(stringResource(Res.string.song_musicbrainz_year), mbYear, if (mbYear != null) outdated else Color.Unspecified)
    if (s.lookup?.let { it.year != null && it.viaFingerprint } == true) {
        Text(stringResource(Res.string.song_via_fingerprint), style = MaterialTheme.typography.bodySmall)
    }
    YearLine(stringResource(Res.string.song_manual_year), s.manualYear)
}

/** Warns while AcoustID rejects the API key (fingerprint matching is then off). */
@Composable
fun AcoustIdWarning(status: AcoustIdKeyStatus) {
    if (status.state == AcoustIdKeyState.REJECTED) {
        Text(stringResource(Res.string.acoustid_rejected_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Fingerprint matching runs for lookups: a key is set and AcoustID hasn't rejected it. */
val AcoustIdKeyStatus.fingerprinting: Boolean get() = key.isNotBlank() && state != AcoustIdKeyState.REJECTED

@Composable
private fun YearLine(label: String, year: Int?, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = color)
        Text(year?.let(::formatYear) ?: stringResource(Res.string.year_none), style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

val YearSource.label: StringResource
    get() = when (this) {
        YearSource.MANUAL -> Res.string.year_source_manual
        YearSource.TAG_ORIGINAL -> Res.string.year_source_tag_original
        YearSource.TAG -> Res.string.year_source_tag
        YearSource.MUSICBRAINZ -> Res.string.year_source_musicbrainz
        YearSource.NONE -> Res.string.year_none
    }

/** How MusicBrainz checked [s], as the year details say it (also on a co-host's phone, D-385). */
fun verificationLabel(s: LibrarySong): StringResource {
    val matched = s.lookup?.year != null
    // Its year still counts: say so rather than "not verified" (Felix).
    if (s.checkedByOldVersion) return if (matched) Res.string.song_verified_old_version else Res.string.song_checked_old_version_no_match
    return when (s.verification) {
        Verification.NONE -> Res.string.song_verification_none
        Verification.NAME -> if (matched) Res.string.song_verified_name else Res.string.song_checked_name_no_match
        Verification.FINGERPRINT -> if (matched) Res.string.song_verified_fingerprint else Res.string.song_checked_fingerprint_no_match
    }
}
