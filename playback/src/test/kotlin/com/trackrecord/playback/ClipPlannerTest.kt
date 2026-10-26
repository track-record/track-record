package com.trackrecord.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class ClipPlannerTest {
    @Test
    fun `no offset, no limit`() = assertEquals(ClipWindow(0, null), ClipPlanner.window(0, null, 200_000))

    @Test
    fun `offset and limit`() = assertEquals(ClipWindow(30_000, 60_000), ClipPlanner.window(30_000, 30_000, 200_000))

    @Test
    fun `limit past the end plays to the end`() = assertEquals(ClipWindow(30_000, null), ClipPlanner.window(30_000, 120_000, 100_000))

    @Test
    fun `offset past the end starts from the beginning`() = assertEquals(ClipWindow(0, 10_000), ClipPlanner.window(60_000, 10_000, 45_000))

    @Test
    fun `unknown duration keeps offset and limit`() = assertEquals(ClipWindow(5_000, 15_000), ClipPlanner.window(5_000, 10_000, 0))
}
