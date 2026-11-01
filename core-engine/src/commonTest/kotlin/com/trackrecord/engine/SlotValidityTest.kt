package com.trackrecord.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class SlotValidityTest {
    private fun validSlots(timeline: List<Int>, year: Int): List<Int> {
        val t = timeline.map { card(it) }
        return (-1..t.size + 1).filter { Slots.isValid(t, it, year) }
    }

    @Test
    fun `empty timeline has one valid slot`() {
        assertEquals(listOf(0), validSlots(emptyList(), 1984))
    }

    @Test
    fun `slot 0 and slot n edges`() {
        val t = listOf(1970, 1980, 1990)
        assertEquals(listOf(0), validSlots(t, 1960))
        assertEquals(listOf(3), validSlots(t, 2000))
        assertEquals(listOf(1), validSlots(t, 1975))
        assertEquals(listOf(2), validSlots(t, 1985))
    }

    @Test
    fun `one card timeline`() {
        assertEquals(listOf(0), validSlots(listOf(1980), 1975))
        assertEquals(listOf(1), validSlots(listOf(1980), 1985))
        assertEquals(listOf(0, 1), validSlots(listOf(1980), 1980))
    }

    @Test
    fun `equal year counts on either side of the neighboring card`() {
        assertEquals(listOf(1, 2), validSlots(listOf(1980, 1990, 2001), 1990))
        assertEquals(listOf(0, 1), validSlots(listOf(1980, 1990, 2001), 1980))
        assertEquals(listOf(2, 3, 4), validSlots(listOf(1980, 1990, 2001, 2001), 2001))
    }

    @Test
    fun `every slot is valid when all cards share the song year`() {
        assertEquals(listOf(0, 1, 2, 3), validSlots(listOf(1985, 1985, 1985), 1985))
    }

    @Test
    fun `out of range slots are invalid`() {
        val t = cards(1980, 1990)
        assertEquals(false, Slots.isValid(t, -1, 1950))
        assertEquals(false, Slots.isValid(t, 3, 2000))
    }

    @Test
    fun `sorted insert goes after the last card with year at most y`() {
        val t = cards(1980, 1990, 1990, 2001)
        assertEquals(0, Slots.sortedInsertIndex(t, 1975))
        assertEquals(3, Slots.sortedInsertIndex(t, 1990))
        assertEquals(4, Slots.sortedInsertIndex(t, 2010))
        assertEquals(listOf(1980, 1990, 1990, 1995, 2001), Slots.insertSorted(t, card(1995)).map { it.year })
    }
}
