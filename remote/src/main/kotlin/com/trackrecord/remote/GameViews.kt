package com.trackrecord.remote

import com.trackrecord.engine.Card
import com.trackrecord.engine.GameAction
import com.trackrecord.engine.GameEngine
import com.trackrecord.engine.GameMode
import com.trackrecord.engine.GameState
import com.trackrecord.engine.Phase
import com.trackrecord.engine.PlayerState
import com.trackrecord.engine.ReduceResult
import com.trackrecord.engine.Slots
import com.trackrecord.engine.TimelineOutcome

/**
 * Builds the guests' view of a game. The current song's title, artist, year and id appear only
 * after reveal, and the pool never (DECISIONS.md D-106).
 */
object GameViews {
    /** Song ids the guests may see covers of: every card in a timeline or collection, the revealed song, a bought or skipped card. */
    fun publicSongs(s: GameState): Set<Long> = buildSet {
        (s.players + s.removed).forEach { p -> p.timeline.forEach { add(it.songId) }; p.collection.forEach { add(it.songId) } }
        if (s.phase == Phase.REVEALED) s.round?.song?.songId?.let(::add)
        s.lastPurchase?.card?.songId?.let(::add)
        s.lastSkip?.card?.songId?.let(::add)
    }

    /** Scores after this round's Next (cards or wins), highest first, like the host's scoreboard card. */
    private fun scoreboard(before: GameState): List<ScoreView> {
        val after = (GameEngine.reduce(before, GameAction.Next) as? ReduceResult.Accepted)?.state ?: before
        fun score(st: GameState, id: String) = st.player(id)?.let { GameEngine.score(st, it) } ?: 0
        return before.players.map {
            val next = after.player(it.id)
            ScoreView(it.id, score(after, it.id), score(after, it.id) - score(before, it.id), next?.vetoes ?: 0, next?.skips ?: 0)
        }
            .sortedByDescending { it.score } +
            // Removed players last, greyed out (feedback round 36).
            before.removed.map { ScoreView(it.id, GameEngine.score(before, it), 0, it.vetoes, it.skips, removed = true) }
    }

    /** Who may be marked as having named song and artist, as on the host's result screen, or null when off. */
    private fun naming(s: GameState): NamingView? {
        val round = s.round ?: return null
        return when (s.mode) {
            GameMode.TIMELINE -> if (s.settings.timeline.namingBonus) NamingView(GameEngine.namingCandidates(s), round.namedById, s.settings.timeline.namingCallers.name) else null
            GameMode.CLOSEST_YEAR -> if (s.settings.closestYear.namingEarnsSkip) NamingView(round.guesses.map { it.playerId }, round.namedById) else null
        }
    }

    fun build(game: HostGame, version: Long, remote: Set<String>): GameView {
        val s = game.state
        val round = s.round
        val timelineMode = s.mode == GameMode.TIMELINE
        val info = game.players.associateBy { it.id }
        fun player(p: PlayerState): PlayerView {
            val i = info[p.id]
            return PlayerView(
                id = p.id,
                name = i?.name ?: p.id,
                initials = i?.initials ?: "?",
                color = i?.color ?: "#888888",
                onColor = i?.onColor ?: "#000000",
                photo = i?.photo == true,
                vetoes = p.vetoes,
                cards = p.timeline.size,
                wins = p.wins,
                remote = p.id in remote,
                skips = p.skips,
                points = p.points,
                score = GameEngine.score(s, p),
                photoKey = i?.photoKey,
            )
        }
        fun card(c: Card) = game.songs[c.songId].let { CardView(c.year, it?.title.orEmpty(), it?.artist, c.songId) }

        val active = if (timelineMode && s.phase != Phase.GAME_OVER) s.activePlayer else null
        val revealed = if (s.phase == Phase.REVEALED && round != null) {
            val song = game.songs[round.song.songId]
            val (outcome, who) = if (timelineMode) {
                when (val o = round.timelineOutcome) {
                    is TimelineOutcome.Kept -> "kept" to o.playerId
                    is TimelineOutcome.Stolen -> "stolen" to o.playerId
                    TimelineOutcome.Discarded, null -> "discarded" to null
                }
            } else {
                if (round.winnerIds.isNotEmpty()) "won" to round.winnerIds.first() else "none" to null
            }
            RevealedView(
                song?.title.orEmpty(), song?.artist, round.song.year, outcome, who, round.winnerIds, round.song.songId, song?.link,
                album = song?.album,
                tagYear = song?.tagYear,
                musicBrainzYear = song?.musicBrainzYear,
                corrected = round.yearCorrected,
            )
        } else {
            null
        }
        val vetoing = s.phase == Phase.VETOING
        return GameView(
            version = version,
            mode = s.mode.name,
            phase = s.phase.name,
            round = round?.number,
            target = GameEngine.target(s),
            players = s.players.map(::player),
            removed = s.removed.map(::player),
            activeId = active?.id,
            cards = (s.players + s.removed).associate { p -> p.id to (if (timelineMode) p.timeline else p.collection).map(::card) },
            gaps = active?.timeline?.let { t -> (0..t.size).filter { Slots.isSelectable(t, it) } }.orEmpty(),
            selectedSlot = round?.selectedSlot,
            lockedSlot = round?.lockedSlot,
            vetoes = round?.vetoes.orEmpty().map { VetoView(it.playerId, it.slot) },
            vetoEligible = if (vetoing) GameEngine.vetoEligible(s) else emptyList(),
            vetoWindow = if (vetoing) game.reveal else null,
            guessOrder = round?.guessOrder.orEmpty(),
            guesses = round?.guesses.orEmpty().map { GuessView(it.playerId, it.year) },
            currentGuesser = if (s.phase == Phase.GUESSING) round?.currentGuesserId else null,
            waitingFor = if (s.phase == Phase.GUESSING) round?.waitingFor.orEmpty() else emptyList(),
            hiddenGuesses = !timelineMode && s.settings.closestYear.hiddenGuesses,
            scoring = s.settings.closestYear.scoring.name,
            minYear = s.settings.closestYear.lowestGuessableYear,
            maxYear = s.currentYear,
            distinctGuesses = s.settings.closestYear.guessesDistinct,
            revealed = revealed,
            audio = game.audio,
            skipCost = if (timelineMode) s.settings.timeline.skipCost else null,
            buyCost = if (timelineMode) s.settings.timeline.buyCost else s.settings.closestYear.buyCost,
            purchase = s.lastPurchase?.let { PurchaseView(it.playerId, card(it.card)) },
            skipped = s.lastSkip?.let { PurchaseView(it.playerId, card(it.card)) },
            closestSkips = !timelineMode && (s.settings.closestYear.startingSkips > 0 || s.settings.closestYear.namingEarnsSkip),
            scoreboard = if (s.phase == Phase.REVEALED) scoreboard(s) else emptyList(),
            naming = if (s.phase == Phase.REVEALED && round != null) naming(s) else null,
            clipLimit = s.settings.common.clipLimitSeconds,
            startOffset = s.settings.common.startOffsetSeconds,
            endReason = s.result?.reason?.name,
            winners = s.result?.winnerIds.orEmpty(),
            roundWinners = if (s.phase == Phase.REVEALED) round?.winnerIds.orEmpty() else emptyList(),
            roundPoints = if (s.phase == Phase.REVEALED) round?.points.orEmpty() else emptyMap(),
            info = game.info,
        )
    }

    /**
     * [view] for the phone of [viewer]: with hidden guesses, before the reveal, the other players' years
     * are left out, only that they guessed is shown (feedback round 36).
     */
    fun forViewer(view: GameView, viewer: String?): GameView {
        if (!view.hiddenGuesses || view.phase != Phase.GUESSING.name) return view
        return view.copy(guesses = view.guesses.map { if (it.playerId == viewer) it else it.copy(year = null) })
    }
}
