package com.trackrecord.app.game

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackrecord.app.R
import com.trackrecord.app.appContainer
import com.trackrecord.app.remote.RemoteState
import com.trackrecord.app.remote.deviceName
import com.trackrecord.app.remote.remoteDevice
import com.trackrecord.app.ui.components.BottomBarSurface
import com.trackrecord.app.ui.components.Button
import com.trackrecord.app.ui.components.FilledTonalButton
import com.trackrecord.app.ui.components.OutlinedButton
import com.trackrecord.app.ui.components.PlayerAvatar
import com.trackrecord.app.ui.components.PlayerChip
import com.trackrecord.app.ui.components.formatYear
import com.trackrecord.engine.GameAction
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClosestYearRoundScreen(session: GameSession, vm: GameViewModel, snackbar: SnackbarHostState, onLeave: () -> Unit) {
    val state = session.state
    val round = state.round ?: return
    val rules = state.settings.closestYear
    val audio by vm.audio.collectAsStateWithLifecycle()
    var scoreboard by rememberSaveable { mutableStateOf(false) }
    // A connected guest phone enters its own player's guess; the host can't (feedback round 9).
    val remoteState by LocalContext.current.appContainer.remote.state.collectAsStateWithLifecycle()
    val connected = (remoteState as? RemoteState.On)?.clients?.claimed.orEmpty().filterValues { it }.keys
    // Hidden guesses: guest phones guess whenever they like; the players on this phone still take turns,
    // as the phone is passed around anyway (feedback rounds 36, 38).
    val hidden = rules.hiddenGuesses
    val guesser = if (hidden) {
        round.waitingFor.firstOrNull { it !in connected }?.let(session::player)
    } else {
        round.currentGuesserId?.let { session.player(it) }
    }
    val allIn = round.waitingFor.isEmpty()
    val remoteGuess = guesser != null && guesser.id in connected
    // The digits are cleared when the guesser changes; with hidden guesses not when a guest phone guesses
    // meanwhile (REVIEW-2 B-07; DECISIONS.md D-266).
    var digits by rememberSaveable(round.number, if (hidden) null else round.guesses.size, guesser?.id) { mutableStateOf("") }

    Scaffold(
        topBar = { GameTopBar(stringResource(R.string.round_title, round.number), session, onLeave, onScoreboard = { scoreboard = true }, onAction = vm::dispatch) },
        snackbarHost = { GameSnackbarHost(snackbar) },
        bottomBar = {
            BottomBarSurface {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.dispatch(GameAction.UndoGuess) }, enabled = round.guesses.isNotEmpty(), modifier = Modifier.height(60.dp)) {
                        Icon(painterResource(R.drawable.ic_undo), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.closest_undo_guess))
                    }
                    Button(onClick = { vm.dispatch(GameAction.Reveal) }, enabled = allIn, modifier = Modifier.weight(1f).height(60.dp)) {
                        Text(stringResource(R.string.action_reveal), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        },
    ) { padding ->
        // The year entry fixed at the bottom and never cut off; everything above it scrolls in the space
        // left, so new guesses never push the keypad down (feedback rounds 18, 38).
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (guesser != null) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        val skips = state.player(guesser.id)?.skips ?: 0
                        val skipsInPlay = rules.startingSkips > 0 || rules.namingEarnsSkip
                        val label = if (remoteGuess) stringResource(R.string.closest_remote_guess, deviceName(remoteDevice(guesser.id))) else stringResource(R.string.closest_your_guess)
                        PlayerChip(
                            guesser,
                            // The skips left, as guests see theirs (DESIGN-REVIEW WD-02).
                            label = if (skipsInPlay) stringResource(R.string.name_with_detail, label, pluralStringResource(R.plurals.skips, skips, skips)) else label,
                            avatarSize = 52.dp,
                            modifier = Modifier.weight(1f),
                        )
                        // Only the first guesser, before anyone guessed, may skip the song (feedback rounds 11, 36).
                        val canSkip = round.guesses.isEmpty() && skips > 0 && !remoteGuess && guesser.id == round.guessOrder.firstOrNull()
                        val buyCost = rules.buyCost?.takeIf { !remoteGuess }
                        // Skip and Buy wrap together, never one under the other (feedback round 43).
                        if (canSkip || buyCost != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (canSkip) {
                                    OutlinedButton(onClick = { vm.dispatch(GameAction.Skip) }) {
                                        Text(stringResource(R.string.closest_skip))
                                    }
                                }
                                // Buying a card beside Skip, as on guest phones (DESIGN-REVIEW IN-02).
                                if (buyCost != null) {
                                    OutlinedButton(onClick = { vm.dispatch(GameAction.BuyCard(guesser.id)) }, enabled = skips >= buyCost) {
                                        Text(stringResource(R.string.scoreboard_buy, pluralStringResource(R.plurals.skips, buyCost, buyCost)))
                                    }
                                }
                            }
                        }
                    }
                } else if (allIn) {
                    Text(stringResource(R.string.closest_all_guessed), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                }
                // Hidden guesses: who still guesses on their own phone.
                val away = round.waitingFor.filter { it in connected }
                if (hidden && away.isNotEmpty()) {
                    Text(
                        stringResource(R.string.closest_waiting_for, away.joinToString(stringResource(R.string.list_separator)) { session.player(it).name }),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = if (guesser == null) 8.dp else 0.dp),
                    )
                }
                PlaybackControls(audio, vm::togglePlay, vm::replay, preview = session.song(round.song.songId)?.streamed == true)
                UnplayableNotice(
                    replaced = vm.replacedThisRound.collectAsStateWithLifecycle().value,
                    unplayable = vm.unplayable.collectAsStateWithLifecycle().value || (audio.error && session.song(round.song.songId)?.streamed == true),
                    onReplace = vm::replaceUnplayable,
                )
                // The guesser can also tap or drag on the line: the year fills the digits, Guess confirms (feedback round 35).
                // Close under the playback controls (feedback round 38).
                val picking = guesser != null && !remoteGuess
                Column {
                    GuessAxis(
                        session,
                        rules.lowestGuessableYear,
                        state.currentYear,
                        pending = digits.takeIf { picking && it.length == 4 }?.toIntOrNull(),
                        pendingPlayer = guesser,
                        onPick = if (picking) { year -> digits = year.toString() } else null,
                        hideGuesses = hidden,
                    )
                }
                // In a titled card, as on the guests' phones (feedback round 27).
                if (round.guesses.isNotEmpty()) SectionCard(stringResource(R.string.result_guesses)) { GuessList(session, hidden) }
            }
            if (guesser != null && !remoteGuess) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    YearKeypad(
                        digits = digits,
                        hint = stringResource(R.string.closest_range, formatYear(rules.lowestGuessableYear), formatYear(state.currentYear)) +
                            " · " + stringResource(R.string.closest_axis_hint),
                        onDigits = { digits = it },
                        onSubmit = { digits.toIntOrNull()?.let { vm.dispatch(GameAction.SubmitGuess(it, guesser.id)) } },
                    )
                }
            }
        }
    }
    if (scoreboard) ScoreboardSheet(session, onDismiss = { scoreboard = false }, onAction = vm::dispatch)
}

/** The guesses so far, newest last; [hidden]: only who guessed, not the year (feedback round 36). */
@Composable
private fun GuessList(session: GameSession, hidden: Boolean) {
    val round = session.state.round ?: return
    Column {
        for (g in round.guesses) {
            val p = session.player(g.playerId)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                PlayerAvatar(p, 28.dp)
                Text(p.name, modifier = Modifier.weight(1f))
                if (hidden) Text(stringResource(R.string.closest_guessed), style = MaterialTheme.typography.bodyMedium)
                else Text(formatYear(g.year), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * The year being typed: four separate digit places, the next one underlined in the primary colour, so
 * it reads as an input and not as a bar (feedback round 9).
 */
@Composable
private fun YearDigits(digits: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = digits },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        for (i in 0 until 4) {
            val next = i == digits.length
            Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(digits.getOrNull(i)?.toString() ?: "", style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
                Box(Modifier.fillMaxWidth().height(if (next) 3.dp else 2.dp).background(if (next) colors.primary else colors.outlineVariant))
            }
        }
    }
}

/**
 * The guesses as avatars on a line from [from] to [to], with the end years just under it; after the
 * reveal the song's year is marked ([revealedYear]). Also used on the reveal screen (feedback round 16).
 * With [onPick], tapping or dragging on the line picks a year; [pending] shows it faded with
 * [pendingPlayer]'s avatar until it is confirmed (feedback round 35).
 */
@Composable
fun GuessAxis(
    session: GameSession,
    from: Int,
    to: Int,
    revealedYear: Int? = null,
    pending: Int? = null,
    pendingPlayer: GamePlayer? = null,
    onPick: ((Int) -> Unit)? = null,
    /** Hidden guesses before the reveal: no one's year on the line (feedback round 36). */
    hideGuesses: Boolean = false,
) {
    val round = session.state.round ?: return
    val span = (to - from).coerceAtLeast(1)
    val pick by rememberUpdatedState(onPick)
    val picker = if (onPick == null) Modifier else Modifier.pointerInput(from, to) {
        val markerPx = AXIS_MARKER.toPx()
        // Markers are placed by their left edge across the width minus one marker: the inverse of that.
        fun yearAt(x: Float) = from + (((x - markerPx / 2) / (size.width - markerPx)).coerceIn(0f, 1f) * span).roundToInt()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            pick?.invoke(yearAt(down.position.x))
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                change.consume()
                pick?.invoke(yearAt(change.position.x))
            }
        }
    }
    // Above the end-year labels below it, so a marker at either end covers the label (feedback round 15).
    // 48 dp: just room for the markers, so the line sits close under the playback controls (feedback round 38).
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).zIndex(1f).then(picker)) {
        val marker = AXIS_MARKER
        val usable = maxWidth - marker
        Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center).background(MaterialTheme.colorScheme.outlineVariant))
        if (revealedYear != null) {
            val f = ((revealedYear - from).toFloat() / span).coerceIn(0f, 1f)
            Box(Modifier.offset(x = usable * f + marker / 2 - 2.dp).align(Alignment.CenterStart).size(4.dp, 48.dp).background(MaterialTheme.colorScheme.primary))
        }
        val shown = round.guesses.takeUnless { hideGuesses }.orEmpty()
        shown.forEachIndexed { i, g ->
            val p = session.player(g.playerId)
            val f = ((g.year - from).toFloat() / span).coerceIn(0f, 1f)
            // The same year again (hidden guesses, feedback round 36): a little higher, so both show.
            val stacked = shown.take(i).count { it.year == g.year }
            Box(Modifier.offset(x = usable * f, y = -STACK_STEP * stacked).align(Alignment.CenterStart)) { PlayerAvatar(p, marker) }
        }
        if (pending != null && pendingPlayer != null && pending in from..to) {
            val f = (pending - from).toFloat() / span
            Box(Modifier.offset(x = usable * f).align(Alignment.CenterStart).alpha(PENDING_ALPHA)) { PlayerAvatar(pendingPlayer, marker) }
        }
    }
    // The end years sit under the line, as far below it as on the guests' phones (feedback rounds 14, 27); the
    // offset keeps the layout, so what follows stays put.
    Row(Modifier.fillMaxWidth().offset(y = (-3).dp)) {
        Text(formatYear(from), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(formatYear(to), style = MaterialTheme.typography.bodySmall)
    }
}

/** Large year keypad: four digits, the range [hint] under them as on the guests' phones (feedback round 38), backspace, and Guess. */
@Composable
private fun YearKeypad(digits: String, hint: String, onDigits: (String) -> Unit, onSubmit: () -> Unit) {
    YearDigits(digits)
    Text(hint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
    for (row in rows) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (key in row) {
                val modifier = Modifier.weight(1f).height(64.dp)
                when (key) {
                    "⌫" -> {
                        val label = stringResource(R.string.keypad_backspace)
                        FilledTonalButton(onClick = { onDigits(digits.dropLast(1)) }, enabled = digits.isNotEmpty(), modifier = modifier.semantics { contentDescription = label }) {
                            Text(key, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    "OK" -> Button(onClick = onSubmit, enabled = digits.length == 4, modifier = modifier) {
                        Text(stringResource(R.string.closest_guess), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                    }
                    else -> FilledTonalButton(onClick = { if (digits.length < 4) onDigits(digits + key) }, modifier = modifier) {
                        Text(key, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}

private val AXIS_MARKER = 36.dp

/** How much higher each further marker on the same year sits. */
private val STACK_STEP = 16.dp

/** A picked year not guessed yet: the guesser's avatar, faded. */
private const val PENDING_ALPHA = 0.55f
