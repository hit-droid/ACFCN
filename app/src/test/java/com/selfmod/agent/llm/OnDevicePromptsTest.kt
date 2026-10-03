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
}
