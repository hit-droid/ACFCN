package com.selfmod.agent.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    @Test
    fun estimatesCjkAndLatinDifferently() {
        assertEquals(4, ContextBudget.estimateTokens("你好世界"))
        assertEquals(1, ContextBudget.estimateTokens("abcd"))
        assertTrue(ContextBudget.estimateTokens("你好world") > ContextBudget.estimateTokens("world"))
    }

    @Test
    fun promptBudgetReservesReplySpace() {
        assertEquals(1024 - 256, ContextBudget.promptBudget(1024, 256))
        // Reply larger than the window must not produce a non-positive budget.
        assertTrue(ContextBudget.promptBudget(512, 4096) >= 1)
    }

    @Test
    fun trimKeepsSystemAndNewestTurn() {
        val msgs = listOf(
            "system" to "你是一个助手",
            "user" to "第一轮的问题，很长很长",
            "assistant" to "第一轮的回答，也很长很长",
            "user" to "第二轮的问题，很长很长",
            "assistant" to "第二轮的回答，也很长很长",
            "user" to "最新问题",
        )
        val trimmed = ContextBudget.trim(msgs, budgetTokens = 40)
        assertTrue("应比原文短", trimmed.size < msgs.size)
        assertEquals("system", trimmed.first().first)
        assertEquals("最新问题", trimmed.last().second)
    }

    @Test
    fun trimLeavesShortHistoryUntouched() {
        val msgs = listOf("user" to "hi", "assistant" to "hello")
        assertEquals(msgs, ContextBudget.trim(msgs, budgetTokens = 10_000))
    }

    @Test
    fun trimNeverEmptiesTheHistory() {
        val msgs = listOf("user" to "一个非常非常长的中文问题".repeat(50))
        val trimmed = ContextBudget.trim(msgs, budgetTokens = 1)
        assertTrue("必须保留至少一条", trimmed.isNotEmpty())
    }
}
