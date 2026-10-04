package com.selfmod.agent.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaPacerTest {

    @Test
    fun firstEmitIsImmediate() {
        val p = DeltaPacer(intervalMs = 100) { 1000L }
        assertTrue(p.ready())
    }

    @Test
    fun suppressesEmitWithinInterval() {
        var now = 1000L
        val p = DeltaPacer(intervalMs = 100) { now }
        assertTrue(p.ready())
        now = 1050
        assertFalse(p.ready())
        now = 1099
        assertFalse(p.ready())
        now = 1100
        assertTrue("间隔到期应放行一次", p.ready())
    }

    @Test
    fun resetMakesNextEmitImmediate() {
        var now = 1000L
        val p = DeltaPacer(intervalMs = 100) { now }
        assertTrue(p.ready())
        now = 1010
        assertFalse(p.ready())
        p.reset()
        assertTrue(p.ready())
    }

    @Test
    fun longGapsAlwaysEmit() {
        var now = 0L
        val p = DeltaPacer(intervalMs = 100) { now }
        now = 5000
        assertTrue(p.ready())
        now = 6000
        assertTrue(p.ready())
    }
}
