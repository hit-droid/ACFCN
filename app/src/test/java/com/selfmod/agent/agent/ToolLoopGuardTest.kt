package com.selfmod.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopGuardTest {

    @Test
    fun countsRepeatedCalls() {
        val g = ToolLoopGuard()
        assertEquals(1, g.countOf("browser_snapshot", "{}"))
        assertEquals(2, g.countOf("browser_snapshot", "{}"))
        assertEquals(3, g.countOf("browser_snapshot", "{}"))
    }

    @Test
    fun treatsDifferentArgsAsDifferentCalls() {
        val g = ToolLoopGuard()
        assertEquals(1, g.countOf("browser_click", "{\"index\":1}"))
        assertEquals(1, g.countOf("browser_click", "{\"index\":2}"))
    }

    @Test
    fun ignoresArgumentKeyOrderAndWhitespace() {
        val g = ToolLoopGuard()
        assertEquals(1, g.countOf("t", "{ \"b\": 2, \"a\": 1 }"))
        assertEquals(2, g.countOf("t", "{\"a\":1,\"b\":2}"))
    }

    @Test
    fun fallsBackToNormalizedTextForNonJsonArgs() {
        val g = ToolLoopGuard()
        assertEquals(1, g.countOf("t", "not json   here"))
        assertEquals(2, g.countOf("t", "not json here"))
    }

    @Test
    fun resetClearsCounts() {
        val g = ToolLoopGuard()
        g.countOf("t", "{}")
        g.countOf("t", "{}")
        g.reset()
        assertEquals(1, g.countOf("t", "{}"))
    }

    @Test
    fun collapsesTurnIntoSingleObservationBlock() {
        val body = ToolLoopGuard.formatObservations(
            listOf("browser_open" to "loaded ok", "browser_snapshot" to "3 elements"),
        )
        // Both results live in ONE body, so the caller appends exactly one user
        // message for the whole turn instead of stacking N consecutive ones (H4).
        assertTrue(body.contains("Observation from browser_open:\nloaded ok"))
        assertTrue(body.contains("Observation from browser_snapshot:\n3 elements"))
        assertEquals(2, Regex("Observation from").findAll(body).count())
        assertTrue(body.endsWith("answer without an Action."))
    }
}
