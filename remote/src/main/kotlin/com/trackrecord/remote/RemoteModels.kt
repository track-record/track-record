package com.trackrecord.remote

import com.trackrecord.engine.GameState
import kotlinx.serialization.Serializable

/**
 * The messages between the guest page and the host (SPEC v4). Bump [VERSION] whenever a request, a
 * reply, a view or an action type changes, together with PROTOCOL in assets/remote/p2p-common.js: an
 * internet guest's page that doesn't match is told to reload (DECISIONS.md D-264). ProtocolTest fails
 * until both are done and the new shape is recorded.
 */
object RemoteProtocol {
    const val VERSION = 5

    /** Every action type a page may send to POST /api/action. */
    val ACTIONS: Set<String> = setOf(
        "select", "dragSelect", "lockIn", "vetoStart", "vetoCancel", "veto", "decline", "guess", "skip", "buy", "togglePlay", "replay",
        "hostReveal", "hostNext", "hostUndo", "hostNamed", "hostYear", "hostAdjust", "hostClip", "hostOffset", "hostWhole", "hostRemove", "hostSeek",
    )
}

// ---- What the host app hands to the hub ----

/** Everything the guests may know right now; null [game] means no game is running. */
data class HostSnapshot(
    val game: HostGame?,
    val theme: ThemeInfo,
    /** The host is in the menu (not on a game screen): guests wait and can't act (feedback round 9). */
    val paused: Boolean = false,
    /** The host's "tap a gap twice to confirm"; each guest phone may change it (feedback round 11). */
    val confirmTwice: Boolean = false,
    /** The host's "show cover art"; each guest phone may change it (feedback round 12). */
    val showCovers: Boolean = true,
    /**
     * The players of the game the host is setting up in New game, or null (feedback round 32): guests
     * pick theirs before it starts. While set, it stands in for any earlier game.
     */
    val lobby: List<PlayerInfo>? = null,
)

data class HostGame(
    val state: GameState,
    val players: List<PlayerInfo>,
    /** Title and artist by song id; only songs already public are ever read (DECISIONS.md D-106). */
    val songs: Map<Long, SongText>,
    /** The veto window while vetoing (SPEC v3), else null. */
    val reveal: RevealInfo? = null,
    /** The host's player, for the play and replay buttons on a guest's turn. */
    val audio: AudioInfo = AudioInfo(),
    /** What internet guests play themselves (SPEC v4), or null when nothing is loaded. */
    val listen: ListenInfo? = null,
    /** The info sheet, in the host's language (feedback round 36). */
    val info: InfoView? = null,
)

/**
 * The game's info sheet (feedback round 36), worded by the host app: how the mode being played works,
 * its settings, and the playlists the songs come from.
 */
@Serializable
data class InfoView(
    val rules: List<String>,
    val settings: List<InfoLine>,
    val included: List<String>,
    val excluded: List<String>,
)

@Serializable
data class InfoLine(val label: String, val value: String)

/**
 * What an internet guest plays (SPEC v4; D-181): a Deezer preview from [url], or the host's stream
 * (local files), at file position [positionMs] as of the host's wall clock [atMs], playing or not.
 * Never sent to Wi-Fi guests, who hear the host's speaker.
 */
@Serializable
data class ListenInfo(
    /** "preview" (play [url]) or "stream" (the room's audio track). */
    val kind: String,
    val url: String? = null,
    val playing: Boolean,
    val positionMs: Long,
    val atMs: Long,
)

/**
 * The host's player. [positionMs] is where it was when this was sent; the page moves the progress bar
 * on by itself while [playing], so the host only sends changes (feedback round 11). [error] gives the
 * same note under the bar as on the host (DESIGN-REVIEW LY-05).
 */
@Serializable
data class AudioInfo(
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val ended: Boolean = false,
    val error: Boolean = false,
    /** A Deezer preview plays: a co-host's button reads "Play the whole preview", not "…song". */
    val preview: Boolean = false,
    /** Only part of the song plays (clip limit or start offset): a co-host may play the whole of it after the reveal. */
    val whole: Boolean = false,
)

/**
 * [link]: the song's page on Deezer for streamed songs, shown to guests after reveal (feedback round 21).
 * [album], [tagYear] and [musicBrainzYear] fill the reveal card as on the host (DESIGN-REVIEW IF-01).
 */
data class SongText(
    val title: String,
    val artist: String?,
    val link: String? = null,
    val album: String? = null,
    val tagYear: Int? = null,
    val musicBrainzYear: Int? = null,
)

/** The veto window in whole seconds, so the guest page only updates once a second. */
@Serializable
data class RevealInfo(
    val waitingFor: List<String>,
    val declined: List<String>,
    val lockedSeconds: Int,
    val autoRevealSeconds: Int?,
    /** Remote players picking a gap for their veto (Reveal waits for them). */
    val picking: List<String> = emptyList(),
)

/**
 * The host's look; the guest page starts with it (DECISIONS.md D-107). [palettes] holds every theme's
 * colours by theme name, so a guest who picks another theme gets the host's colours for it too
 * (DESIGN-REVIEW TH-01; D-277).
 */
@Serializable
data class ThemeInfo(val choice: String, val cornerRadius: Int, val buttonRadius: Int, val palettes: Map<String, WebPalette> = emptyMap())

/**
 * A theme as the guest page uses it (D-277): CSS custom properties ([colors], e.g. "--primary" to
 * "#FFC857"), the headings' weight, how strongly the scene is dimmed, and the song cards' colours: one
 * of [cardFills] per song, or a hue per song at [cardSaturation] and [cardValue] (as the app does).
 */
@Serializable
data class WebPalette(
    val colors: Map<String, String>,
    val headingWeight: Int,
    val dim: Float,
    val cardText: String,
    val cardFills: List<String> = emptyList(),
    val cardSaturation: Float = 0f,
    val cardValue: Float = 0f,
)

/** A player as the guest page shows it: colours as CSS hex, always with initials (never colour alone). */
@Serializable
data class PlayerInfo(
    val id: String,
    val name: String,
    val initials: String,
    val color: String,
    val onColor: String,
    val photo: Boolean = false,
    /** Changes with the photo (a new photo is a new file), so pages may keep it (REVIEW.md P-03; D-270). */
    val photoKey: String? = null,
)

/** What a guest plays on, as its page tells (DESIGN-REVIEW WD-01). */
object DeviceKinds {
    const val PHONE = "PHONE"
    const val TABLET = "TABLET"
    const val COMPUTER = "COMPUTER"
    val ALL = setOf(PHONE, TABLET, COMPUTER)
}

/**
 * Which players are controlled from a guest's device and whether it is connected now, and how many
 * guest pages are open at all (also those still picking a player).
 */
data class RemoteClients(
    val claimed: Map<String, Boolean>,
    val open: Int = 0,
    /** Player → the kind of device it is played on ([DeviceKinds]); missing when a page didn't say. */
    val devices: Map<String, String> = emptyMap(),
    /** Phones asking to take over a player whose phone is still connected; the host allows or denies (D-182). */
    val takeovers: List<Takeover> = emptyList(),
    /** The player whose phone has the host controls (co-host, D-189), or null. */
    val coHost: String? = null,
) {
    val players: Set<String> get() = claimed.keys
}

/** A device ([client], of kind [device]) asking to take over [player] from the one that plays it now (D-182). */
data class Takeover(val client: String, val player: String, val device: String? = null)

// ---- What the guest page receives ----

@Serializable
data class ClientView(
    val theme: ThemeInfo,
    val game: GameView?,
    /** No game yet, the host is setting one up: its players, to pick one (feedback round 32). */
    val lobby: List<LobbyPlayer>? = null,
    /** The player this phone controls, if it is in the current game. */
    val me: String?,
    /** Players controlled from other phones. */
    val taken: List<String>,
    /** Player → the kind of device it is played on, for every claimed player ([DeviceKinds], WD-01). */
    val devices: Map<String, String> = emptyMap(),
    /** A game is running but the host is in the menu: show "Game paused". */
    val paused: Boolean = false,
    /** The host's default for "tap a gap twice to confirm". */
    val confirmTwice: Boolean = false,
    /** The host's default for showing album covers. */
    val showCovers: Boolean = true,
    /** After reveal: this phone's outline, "WON", "LOST", "OTHER" or null (feedback rounds 15, 16, 19). */
    val tone: String? = null,
    /** Internet guests only: what to play (SPEC v4). */
    val listen: ListenInfo? = null,
    /** This phone asked to take over that player and waits for the host (D-182). */
    val pendingPlayer: String? = null,
    /** The host didn't allow this phone's last takeover of that player (D-182). */
    val deniedPlayer: String? = null,
    /** The host removed this phone from the game for this session (D-182). */
    val blocked: Boolean = false,
    /** This phone has the host controls (co-host, D-189). */
    val coHost: Boolean = false,
    /** This phone's player was removed from the game: it watches (feedback round 36). */
    val left: Boolean = false,
)

@Serializable
data class GameView(
    /** Changes with every game state change; guest actions must name the version they saw. */
    val version: Long,
    val mode: String,
    val phase: String,
    val round: Int?,
    val target: Int,
    val players: List<PlayerView>,
    /** Players removed mid-game, greyed out after the others (feedback round 36). */
    val removed: List<PlayerView> = emptyList(),
    val activeId: String?,
    /** Timeline: every player's revealed cards; Closest Year: every player's collection (removed players' too). */
    val cards: Map<String, List<CardView>>,
    /** Gaps of the active player's timeline that can be chosen (no gap between two cards of the same year). */
    val gaps: List<Int>,
    val selectedSlot: Int?,
    val lockedSlot: Int?,
    val vetoes: List<VetoView>,
    /** Players who could still veto (Timeline, after Lock in). */
    val vetoEligible: List<String>,
    val vetoWindow: RevealInfo?,
    val guessOrder: List<String>,
    /** With hidden guesses, other players' years are left out before the reveal (feedback round 36). */
    val guesses: List<GuessView>,
    val currentGuesser: String?,
    /** Closest Year: who hasn't guessed yet; with hidden guesses any of them may guess now. */
    val waitingFor: List<String> = emptyList(),
    /** Closest Year: everyone guesses at once without seeing the others' years (feedback round 36). */
    val hiddenGuesses: Boolean = false,
    /** Closest Year scoring: "CLOSEST", "RANK" or "DISTANCE" (feedback round 36). */
    val scoring: String = "CLOSEST",
    val minYear: Int,
    val maxYear: Int,
    val distinctGuesses: Boolean,
    /** Only after reveal (DECISIONS.md D-106). */
    val revealed: RevealedView?,
    val audio: AudioInfo,
    /** Timeline skip cost in vetoes; null when off. */
    val skipCost: Int?,
    /** Buying a card: vetoes (Timeline) or skips (Closest Year, feedback round 27); null when off. */
    val buyCost: Int?,
    /** A card just bought, shown to everyone (it is revealed on the host too). */
    val purchase: PurchaseView?,
    /** A song just skipped, shown to everyone like a bought card (feedback round 27). */
    val skipped: PurchaseView? = null,
    /** Closest Year skips are part of this game (players start with some or can earn them). */
    val closestSkips: Boolean = false,
    /** Game over: "TARGET_REACHED" or "POOL_EMPTY". */
    val endReason: String? = null,
    /** The clip length limit in seconds, or null (a co-host can change it, D-191). */
    val clipLimit: Int? = null,
    /** The start offset in seconds (a co-host can change it, feedback round 34). */
    val startOffset: Int = 0,
    /** After reveal, when the naming bonus is on: who may be marked, and who is (for the co-host, D-189). */
    val naming: NamingView? = null,
    /** After reveal: the scores after this round (feedback round 12). */
    val scoreboard: List<ScoreView> = emptyList(),
    val winners: List<String>,
    /** Closest Year after reveal: the round's winners and, in Rank and Distance, each guess's points. */
    val roundWinners: List<String> = emptyList(),
    val roundPoints: Map<String, Int> = emptyMap(),
    val info: InfoView? = null,
)

@Serializable
data class PlayerView(
    val id: String,
    val name: String,
    val initials: String,
    val color: String,
    val onColor: String,
    val photo: Boolean,
    val vetoes: Int,
    val cards: Int,
    val wins: Int,
    val remote: Boolean,
    /** Closest Year skips left. */
    val skips: Int = 0,
    /** Rank and Distance points (feedback round 36). */
    val points: Int = 0,
    /** What counts towards the target: cards, wins or points. */
    val score: Int = 0,
    /** See [PlayerInfo.photoKey]. */
    val photoKey: String? = null,
)

/**
 * [callers]: Timeline's "who may call out" (ACTIVE, VETOERS, ALL) for the host's hint below the marks,
 * null in Closest Year (feedback round 26).
 */
/** A player of the game being set up (feedback round 32). */
@Serializable
data class LobbyPlayer(val id: String, val name: String, val initials: String, val color: String, val onColor: String, val photo: Boolean, val photoKey: String? = null)

@Serializable
data class NamingView(val candidates: List<String>, val namedBy: String?, val callers: String? = null)

@Serializable
data class PurchaseView(val playerId: String, val card: CardView)

/** A public card; [songId] lets the page load its cover (only ever for public songs, D-106). */
@Serializable
data class CardView(val year: Int, val title: String, val artist: String?, val songId: Long? = null)

/** Scores as they will be after this round's Next, highest first (the host's scoreboard card). */
@Serializable
data class ScoreView(val playerId: String, val score: Int, val gained: Int, val vetoes: Int = 0, val skips: Int = 0, val removed: Boolean = false)

@Serializable
data class VetoView(val playerId: String, val slot: Int)

/** [year] is null for another player's hidden guess (feedback round 36). */
@Serializable
data class GuessView(val playerId: String, val year: Int?)

@Serializable
data class RevealedView(
    val title: String,
    val artist: String?,
    val year: Int,
    /** "kept", "stolen", "discarded" (Timeline) or "won", "none" (Closest Year). */
    val outcome: String,
    val outcomePlayer: String?,
    /** Closest Year: every round winner (several on a tie that both score, feedback round 36). */
    val outcomePlayers: List<String> = emptyList(),
    val songId: Long? = null,
    /** "Open in Deezer" for streamed songs (feedback round 21). */
    val link: String? = null,
    /** The reveal card's details, as on the host (DESIGN-REVIEW IF-01): the album, and both years when they differ. */
    val album: String? = null,
    val tagYear: Int? = null,
    val musicBrainzYear: Int? = null,
    /** The year was corrected this round ("Year corrected"). */
    val corrected: Boolean = false,
)

// ---- What the guest page sends ----

@Serializable
data class ClaimRequest(val client: String, val player: String, val takeover: Boolean = false, val device: String? = null)

@Serializable
data class ClientRequest(val client: String)

@Serializable
data class ActionRequest(
    val client: String,
    val version: Long,
    /** "select", "dragSelect", "lockIn", "vetoStart", "vetoCancel", "veto", "decline", "guess", "skip", "buy", "togglePlay" or "replay". */
    val type: String,
    val slot: Int? = null,
    val year: Int? = null,
    /** Co-host commands (D-189): the player concerned, a mark on or off, a change by ±1. */
    val player: String? = null,
    val named: Boolean? = null,
    val delta: Int? = null,
    /** "hostSeek": where in the clip to jump to. */
    val positionMs: Long? = null,
    /** The round the phone saw, for actions that only depend on the round ("decline", "vetoStart"). */
    val round: Int? = null,
)

/** What a co-host phone can ask the host app to do (D-189), checked there like the host's own taps. */
sealed interface HostCommand {
    data object Reveal : HostCommand
    data object Next : HostCommand
    /** The last veto (Timeline) or guess (Closest Year). */
    data object Undo : HostCommand
    data class Named(val playerId: String, val named: Boolean) : HostCommand
    data class CorrectYear(val year: Int) : HostCommand
    /** Vetoes (Timeline) or skips (Closest Year) by ±1. */
    data class Adjust(val playerId: String, val delta: Int) : HostCommand

    /** The clip length limit in seconds, or null for none (D-191). */
    data class ClipLimit(val seconds: Int?) : HostCommand

    /** The start offset in seconds, from the next song (feedback round 34). */
    data class StartOffset(val seconds: Int) : HostCommand

    /** After the reveal: the whole song (or Deezer preview) instead of the clip. */
    data object WholeSong : HostCommand

    /** After the reveal: jumps to [positionMs] in what plays, like dragging the host's bar. */
    data class Seek(val positionMs: Long) : HostCommand

    /** Takes a player out of the game, as from the host's scoreboard (feedback round 36). */
    data class Remove(val playerId: String) : HostCommand
}

@Serializable
data class ActionReply(val ok: Boolean, val error: String? = null, val taken: Boolean = false, val pending: Boolean = false)
