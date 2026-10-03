package com.selfmod.agent.browser

/**
 * Pure staleness tracker for agent element ids (H7).
 *
 * A snapshot records the page epoch it was taken at plus a fingerprint per
 * indexed element. Before an action runs, [reason] decides whether the id can
 * still be trusted, so a click can never land on an element the model was
 * never shown.
 */
class SnapshotGuard {
    private var epoch: Long? = null
    private var fingerprints: Map<Int, String> = emptyMap()

    /** Record the ids handed out by a snapshot taken when the page was at [atEpoch]. */
    fun record(atEpoch: Long, fingerprints: Map<Int, String>) {
        epoch = atEpoch
        this.fingerprints = fingerprints
    }

    /** Forget everything (e.g. the WebView was detached). */
    fun invalidate() {
        epoch = null
        fingerprints = emptyMap()
    }

    /**
     * @param index element id the model wants to act on
     * @param currentEpoch page epoch right now
     * @return null when the id is safe to use, otherwise a message telling the
     *         model to take a fresh snapshot
     */
    fun reason(index: Int, currentEpoch: Long): String? {
        val snapAt = epoch ?: return "ERROR: no snapshot yet — call browser_snapshot first"
        if (snapAt != currentEpoch) return "ERROR: page changed since your last snapshot — call browser_snapshot again"
        if (!fingerprints.containsKey(index)) return "ERROR: #$index was not in the last snapshot — call browser_snapshot again"
        return null
    }

    /** Fingerprint recorded for [index], or null if unknown. */
    fun fingerprintOf(index: Int): String? = fingerprints[index]
}
