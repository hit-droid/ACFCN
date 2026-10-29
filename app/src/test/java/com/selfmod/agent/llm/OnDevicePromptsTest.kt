package com.selfmod.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class OnDevicePromptsTest {

    @Test
    fun keepsSystemUserAssistantAsIs() {
        val out = OnDevicePrompts.fold(
            listOf(
                ChatMessage("system", "sys"),
                ChatMessage("user", "hi"),
                ChatMessage("assistant", "hello"),
            ),
        )
        assertEquals(
            listOf("system" to "sys", "user" to "hi", "assistant" to "hello"),
            out,
        )
    }

    @Test
    fun foldsToolResultIntoMarkedUserTurn() {
        val out = OnDevicePrompts.fold(
            listOf(
                ChatMessage("assistant", "我来看看"),
                ChatMessage("tool", "loaded", name = "browser_open", toolCallId = "c1"),
            ),
        )
        assertEquals(2, out.size)
        assertEquals("user", out[1].first)
        assertEquals("[工具结果 browser_open]\nloaded", out[1].second)
    }

    @Test
    fun mergesAdjacentToolResults() {
        val out = OnDevicePrompts.fold(
            listOf(
                ChatMessage("assistant", ""),
                ChatMessage("tool", "r1", name = "t1", toolCallId = "c1"),
                ChatMessage("tool", "r2", name = "t2", toolCallId = "c2"),
            ),
        )
        assertEquals(2, out.size)
        assertEquals(
            "[工具结果 t1]\nr1\n\n[工具结果 t2]\nr2",
            out[1].second,
        )
    }

    @Test
    fun doesNotMergeToolResultAfterNormalUserTurn() {
        val out = OnDevicePrompts.fold(
            listOf(
                ChatMessage("user", "question"),
                ChatMessage("tool", "r", name = "t"),
            ),
        )
        assertEquals(2, out.size)
        assertEquals("question", out[0].second)
        assertEquals("[工具结果 t]\nr", out[1].second)
    }

    @Test
    fun summarizesAssistantToolCallsWhenContentBlank() {
        val out = OnDevicePrompts.fold(
            listOf(
                ChatMessage(
                    "assistant", "",
                    toolCalls = listOf(ToolCall("c1", "function", ToolFunction("browser_open", "{}"))),
                ),
            ),
        )
        assertEquals("[调用工具 browser_open]", out[0].second)
        assertEquals("assistant", out[0].first)
    }

    @Test
    fun keepsBlankAssistantWithoutToolCallsEmpty() {
        val out = OnDevicePrompts.fold(listOf(ChatMessage("assistant", "")))
        assertEquals("assistant" to "", out[0])
    }

    @Test
    fun unknownRoleFallsBackToUserAndNothingIsDropped() {
        val messages = listOf(
            ChatMessage("system", "s"),
            ChatMessage("user", "u"),
            ChatMessage("assistant", "a"),
            ChatMessage("tool", "t", name = "n"),
            ChatMessage("weird", "w"),
        )
        val out = OnDevicePrompts.fold(messages)
        assertEquals(messages.size, out.size)
        assertEquals("user" to "w", out[4])
    }

    // --- foldCached (incremental fold for the on-device agent loop) ----------

    @Test
    fun foldCachedWithNullCacheEqualsFold() {
        val messages = listOf(
            ChatMessage("system", "s"),
            ChatMessage("user", "u"),
            ChatMessage("assistant", ""),
            ChatMessage("tool", "r", name = "t", toolCallId = "c"),
        )
        val (out, _) = OnDevicePrompts.foldCached(messages, null)
        assertEquals(OnDevicePrompts.fold(messages), out)
    }

    @Test
    fun foldCachedAcrossAppendsEqualsFullFold() {
        val history = mutableListOf<ChatMessage>()
        var cache: OnDevicePrompts.Cache? = null
        history.add(ChatMessage("system", "sys"))
        history.add(ChatMessage("user", "q1"))
        history.add(ChatMessage("assistant", "a1"))
        for (step in 1..10) {
            history.add(ChatMessage("user", "q$step"))
            history.add(ChatMessage("assistant", "a$step"))
            val full = OnDevicePrompts.fold(history.toList())
            val (incr, next) = OnDevicePrompts.foldCached(history.toList(), cache)
            cache = next
            assertEquals("step $step incremental must equal full fold", full, incr)
        }
    }

    @Test
    fun foldCachedMergesAdjacentToolResultsIncrementally() {
        val history = mutableListOf<ChatMessage>()
        var cache: OnDevicePrompts.Cache? = null
        history.add(ChatMessage("assistant", ""))
        history.add(ChatMessage("tool", "r1", name = "t1", toolCallId = "c1"))
        history.add(ChatMessage("tool", "r2", name = "t2", toolCallId = "c2"))
        // Add another assistant + adjacent tool batch (previous tail was a tool).
        history.add(ChatMessage("assistant", ""))
        history.add(ChatMessage("tool", "r3", name = "t3", toolCallId = "c3"))
        val full = OnDevicePrompts.fold(history.toList())
        val (incr, _) = OnDevicePrompts.foldCached(history.toList(), cache)
        assertEquals(full, incr)
    }

    @Test
    fun foldCachedFallsBackOnClearedAndRefilledList() {
        val history = mutableListOf<ChatMessage>()
        var cache: OnDevicePrompts.Cache? = null
        for (k in 0 until 8) history.add(ChatMessage("user", "old$k"))
        val (_, c1) = OnDevicePrompts.foldCached(history.toList(), null)
        cache = c1
        history.add(ChatMessage("user", "old8"))
        val (_, c2) = OnDevicePrompts.foldCached(history.toList(), cache)
        cache = c2
        // In-place clear + refill with DIFFERENT content beyond the old prefix.
        history.clear()
        for (k in 0 until 20) history.add(ChatMessage("user", "new$k"))
        val full = OnDevicePrompts.fold(history.toList())
        val (incr, _) = OnDevicePrompts.foldCached(history.toList(), cache)
        // Must not serve the stale old prefix.
        assertEquals(full, incr)
        assertEquals(20, incr.size)
    }

    @Test
    fun foldCachedFallsBackOnDifferentListInstance() {
        val a = (0 until 8).map { ChatMessage("user", "m$it") }
        val (_, c) = OnDevicePrompts.foldCached(a, null)
        // b has identical content but brand-new message instances.
        val b = a.map { ChatMessage(it.role, it.content, it.name, it.toolCalls, it.toolCallId) }
        val full = OnDevicePrompts.fold(b)
        val (incr, _) = OnDevicePrompts.foldCached(b, c)
        assertEquals(full, incr)
    }
}
