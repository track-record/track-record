package com.trackrecord.app.platform

/** Milliseconds from a clock that only moves forward (Android's elapsedRealtime, for timeouts and idle checks). */
fun monotonicMs(): Long = System.nanoTime() / 1_000_000
