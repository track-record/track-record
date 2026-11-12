package com.trackrecord.app.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The host's popups about guests' devices (feedback round 60). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionNoticesTest {
    private val grace = ConnectionNotices.LOST_GRACE_MS

    @Test
    fun `a connection is told at once, a lost one only after the grace time`() = runTest {
        val notices = ConnectionNotices(backgroundScope)
        val seen = mutableListOf<ConnectionNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { notices.events.collect { seen += it } }

        notices.update(mapOf("anna" to true))
        assertEquals(listOf(ConnectionNotice("anna", true)), seen)
        notices.update(mapOf("anna" to true, "ben" to false))
        assertEquals(1, seen.size, "a player claimed but not connected says nothing")

        notices.update(mapOf("anna" to false, "ben" to false))
        advanceTimeBy(grace - 1)
        runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(ConnectionNotice("anna", false), seen.last())

        notices.update(mapOf("anna" to true, "ben" to false))
        assertEquals(ConnectionNotice("anna", true), seen.last(), "back after a lost connection")
        assertEquals(3, seen.size)
    }

    @Test
    fun `a short gap, a player the host disconnected, or remote play stopping shows nothing`() = runTest {
        val notices = ConnectionNotices(backgroundScope)
        val seen = mutableListOf<ConnectionNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { notices.events.collect { seen += it } }
        notices.update(mapOf("anna" to true, "ben" to true))
        seen.clear()

        // A reload: away and back within the grace time.
        notices.update(mapOf("anna" to false, "ben" to true))
        advanceTimeBy(grace / 2)
        notices.update(mapOf("anna" to true, "ben" to true))
        // Ben disconnected by the host: no longer claimed.
        notices.update(mapOf("anna" to true, "ben" to false))
        notices.update(mapOf("anna" to true))
        // Anna away, then remote play stops.
        notices.update(mapOf("anna" to false))
        notices.reset()
        advanceTimeBy(grace * 2)
        runCurrent()
        assertEquals(emptyList<ConnectionNotice>(), seen)
    }
}
