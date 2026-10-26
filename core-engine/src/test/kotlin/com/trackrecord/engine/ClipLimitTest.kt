package com.trackrecord.engine

import com.trackrecord.engine.GameAction.SetClipLimit
import com.trackrecord.engine.GameAction.SetStartOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** Changing the clip length mid-game (feedback round 25; D-191). */
class ClipLimitTest {
    private val game = timelineGame(listOf("Anna" to listOf(1970), "Ben" to listOf(1990)), song = 1985)

    @Test
    fun `the clip length changes mid-game within the setting's range`() {
        assertEquals(30, game.act(SetClipLimit(30)).settings.common.clipLimitSeconds)
        assertEquals(null, game.act(SetClipLimit(30)).act(SetClipLimit(null)).settings.common.clipLimitSeconds)
        assertEquals(RejectReason.INVALID_SETTINGS, game.rejectReason(SetClipLimit(5)))
        assertEquals(RejectReason.INVALID_SETTINGS, game.rejectReason(SetClipLimit(200)))
        assertEquals(15, game.act(SetClipLimit(15)).settings.common.clipLimitSeconds, "5 s steps (feedback round 34)")
    }

    @Test
    fun `the start offset changes mid-game within the setting's range`() {
        assertEquals(20, game.act(SetStartOffset(20)).settings.common.startOffsetSeconds)
        assertEquals(0, game.act(SetStartOffset(20)).act(SetStartOffset(0)).settings.common.startOffsetSeconds)
        assertEquals(RejectReason.INVALID_SETTINGS, game.rejectReason(SetStartOffset(61)))
        assertEquals(game.round, game.act(SetStartOffset(20)).round, "the song playing now is left alone")
    }
}
