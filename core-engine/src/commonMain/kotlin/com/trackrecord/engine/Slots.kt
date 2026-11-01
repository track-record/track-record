package com.trackrecord.engine

/**
 * Slot rules for a timeline of n cards sorted by year. Slot 0 is before the first card,
 * slot i between card i−1 and card i, slot n after the last.
 */
object Slots {
    /** Slot i is valid for year y iff (i = 0 ∨ T[i−1] ≤ y) ∧ (i = n ∨ y ≤ T[i]). */
    fun isValid(timeline: List<Card>, slot: Int, year: Int): Boolean {
        val n = timeline.size
        if (slot !in 0..n) return false
        return (slot == 0 || timeline[slot - 1].year <= year) &&
            (slot == n || year <= timeline[slot].year)
    }

    /**
     * A slot can be chosen unless it sits between two cards of the same year: any song valid there is
     * also valid in the gaps around that pair, so the gap is not offered (DECISIONS.md D-056).
     */
    fun isSelectable(timeline: List<Card>, slot: Int): Boolean {
        val n = timeline.size
        if (slot !in 0..n) return false
        return slot == 0 || slot == n || timeline[slot - 1].year != timeline[slot].year
    }

    /** Sorted position for a card of [year]: after the last card with year ≤ [year]. */
    fun sortedInsertIndex(timeline: List<Card>, year: Int): Int =
        timeline.indexOfLast { it.year <= year } + 1

    fun insertSorted(timeline: List<Card>, card: Card): List<Card> =
        insertAt(timeline, sortedInsertIndex(timeline, card.year), card)

    fun insertAt(timeline: List<Card>, slot: Int, card: Card): List<Card> =
        timeline.toMutableList().apply { add(slot, card) }
}
