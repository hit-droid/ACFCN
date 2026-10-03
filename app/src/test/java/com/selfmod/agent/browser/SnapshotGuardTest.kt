package com.selfmod.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotGuardTest {

    @Test
    fun rejectsActingBeforeAnySnapshot() {
        val g = SnapshotGuard()
        val r = g.reason(index = 3, currentEpoch = 0)
        assertTrue(r.orEmpty().contains("no snapshot yet"))
        assertNull(g.fingerprintOf(3))
    }

    @Test
    fun acceptsIndexFromCurrentSnapshot() {
        val g = SnapshotGuard()
        g.record(atEpoch = 7, fingerprints = mapOf(0 to "A", 1 to "B"))
        assertNull(g.reason(0, 7))
        assertNull(g.reason(1, 7))
        assertEquals("A", g.fingerprintOf(0))
    }

    @Test
    fun rejectsAfterPageChanged() {
        val g = SnapshotGuard()
        g.record(atEpoch = 7, fingerprints = mapOf(0 to "A"))
        val r = g.reason(0, currentEpoch = 8)
        assertTrue(r.orEmpty().contains("page changed"))
    }

    @Test
    fun rejectsUnknownIndex() {
        val g = SnapshotGuard()
        g.record(atEpoch = 1, fingerprints = mapOf(0 to "A", 1 to "B"))
        val r = g.reason(9, currentEpoch = 1)
        assertTrue(r.orEmpty().contains("was not in the last snapshot"))
    }

    @Test
    fun invalidateForcesFreshSnapshot() {
        val g = SnapshotGuard()
        g.record(atEpoch = 1, fingerprints = mapOf(0 to "A"))
        g.invalidate()
        assertTrue(g.reason(0, 1).orEmpty().contains("no snapshot yet"))
    }

    @Test
    fun fingerprintLookupSurvivesReRecord() {
        val g = SnapshotGuard()
        g.record(atEpoch = 1, fingerprints = mapOf(0 to "A", 1 to "B"))
        g.record(atEpoch = 2, fingerprints = mapOf(0 to "C"))
        assertEquals("C", g.fingerprintOf(0))
        assertNull(g.fingerprintOf(1))
        assertTrue(g.reason(1, 2).orEmpty().contains("was not in the last snapshot"))
    }
}
