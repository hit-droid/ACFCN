package com.selfmod.agent

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L16 — the UI event queue must stop swallowing events silently. */
class UiEventQueueTest {

    @Test
    fun capacityMatchesTheDocumentedSize() {
        assertEquals(64, UiEventQueue.UI_QUEUE_CAPACITY)
        assertEquals(64, UiEventQueue().offeredUntilFull())
    }

    @Test
    fun eventsEmittedWhileNobodyIsListeningAreKeptForTheNextCollector() = runBlocking {
        val q = UiEventQueue(capacity = 4)
        // AgentScreen is on another tab here: no collector, three notifier calls.
        repeat(3) { assertTrue(q.offer("toast", "msg$it")) }
        val backlog = withTimeout(2_000) { q.events.take(3).toList() }
        assertEquals(listOf("msg0", "msg1", "msg2"), backlog.map { it.payload })
    }

    @Test
    fun backlogKeepsTheOrderItWasOfferedIn() = runBlocking {
        val q = UiEventQueue(capacity = 8)
        listOf("a", "b", "c", "d").forEach { q.offer("ui_notify", it) }
        val got = withTimeout(2_000) { q.events.take(4).toList() }
        assertEquals(listOf("a", "b", "c", "d"), got.map { it.payload })
        assertEquals(listOf("ui_notify", "ui_notify", "ui_notify", "ui_notify"), got.map { it.action })
    }

    @Test
    fun overflowRefusesTheNewEventAndCountsItInsteadOfIgnoring() {
        val q = UiEventQueue(capacity = 2)
        assertTrue(q.offer("toast", "1"))
        assertTrue(q.offer("toast", "2"))
        assertEquals(0, q.dropped.value)
        assertFalse("third offer must be refused", q.offer("toast", "3"))
        assertEquals(1, q.dropped.value)
        assertFalse(q.offer("toast", "4"))
        assertEquals(2, q.dropped.value)
    }

    @Test
    fun offerNeverThrowsWhenTheQueueIsFull() {
        val q = UiEventQueue(capacity = 1)
        q.offer("toast", "1")
        val r = runCatching { repeat(50) { q.offer("toast", "x") } }
        assertTrue("offer must not block or throw: ${r.exceptionOrNull()}", r.isSuccess)
        assertEquals(50, q.dropped.value)
    }

    @Test
    fun drainedQueueAcceptsNewEventsAgain() = runBlocking {
        val q = UiEventQueue(capacity = 2)
        q.offer("toast", "1")
        q.offer("toast", "2")
        assertFalse(q.offer("toast", "3"))
        val first = withTimeout(2_000) { q.events.take(2).toList() }
        assertEquals(listOf("1", "2"), first.map { it.payload })
        // Space is back, so a later notification is deliverable again.
        assertTrue(q.offer("toast", "4"))
        assertEquals("4", withTimeout(2_000) { q.events.take(1).toList().single().payload })
    }

    @Test
    fun dropCountIsReportedOnceThenReset() {
        val q = UiEventQueue(capacity = 1)
        assertEquals(0, q.acknowledgeDropped())
        q.offer("toast", "1")
        q.offer("toast", "2")
        q.offer("toast", "3")
        assertEquals(2, q.dropped.value)
        assertEquals(2, q.acknowledgeDropped())
        assertEquals(0, q.dropped.value)
        // The same drops must not be shown twice, otherwise every later event repeats the warning.
        assertEquals(0, q.acknowledgeDropped())
    }

    @Test
    fun aCollectorDrainingTheQueueDoesNotCloseItForLaterEvents() = runBlocking {
        val q = UiEventQueue(capacity = 4)
        val seen = mutableListOf<String>()
        val pump = launch { q.events.take(2).collect { seen.add(it.payload) } }
        q.offer("toast", "early")
        q.offer("toast", "late")
        withTimeout(2_000) { pump.join() }
        assertEquals(listOf("early", "late"), seen)
        assertTrue(q.offer("toast", "after"))
    }

    /** Fills the queue until an offer is refused and returns how many got in. */
    private fun UiEventQueue.offeredUntilFull(): Int {
        var n = 0
        while (offer("probe", "$n")) n++
        return n
    }
}
