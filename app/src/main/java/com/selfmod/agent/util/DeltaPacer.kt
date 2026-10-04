package com.selfmod.agent.util

/**
 * Paces UI updates for token streams (H9).
 *
 * applyDelta used to rebuild the trace StateFlow on EVERY token (20-60/s on
 * device), saturating the main thread — jank and ANRs on Android 16 — and
 * stealing CPU from the decode thread. The pacer lets at most one emit per
 * interval through; the first call always emits so a stream shows up instantly.
 * Pending text is flushed when a final step (thought/answer/…) arrives.
 */
class DeltaPacer(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastEmit = NEVER

    /** true when an interval has elapsed since the last emit; always true the first time. */
    fun ready(): Boolean {
        val now = clock()
        if (now - lastEmit >= intervalMs) {
            lastEmit = now
            return true
        }
        return false
    }

    /** Next ready() emits immediately (new stream started / buffer flushed). */
    fun reset() {
        lastEmit = NEVER
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 100L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
