package com.selfmod.agent.store

import com.selfmod.agent.llm.ChatMessage
import com.selfmod.agent.llm.ToolCall
import com.selfmod.agent.llm.ToolFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExporterTest {

    private val at = 1_700_000_000_000L

    @Test
    fun rendersUserAssistantAndToolTurns() {
        val md = SessionExporter.toMarkdown(
            "测试会话",
            listOf(
                ChatMessage("system", "hidden"),
                ChatMessage("user", "帮我打开网页"),
                ChatMessage(
                    role = "assistant",
                    content = "好的，已打开",
                    toolCalls = listOf(ToolCall("c1", "function", ToolFunction("browser_open", "{}"))),
                ),
                ChatMessage("tool", "loaded url=https://x", name = "browser_open", toolCallId = "c1"),
            ),
            at,
        )
        assertTrue(md.startsWith("# 测试会话"))
        assertTrue(md.contains("导出自 ACFCN"))
        assertFalse(md.contains("hidden"))
        assertTrue(md.contains("## 你"))
        assertTrue(md.contains("帮我打开网页"))
        assertTrue(md.contains("## ACFCN"))
        assertTrue(md.contains("好的，已打开"))
        // tool call folded into a quote line, tool result kept short
        assertTrue(md.contains("> ⚙️ 调用工具 `browser_open`"))
        assertTrue(md.contains("> 👁 browser_open返回：loaded url=https://x"))
    }

    @Test
    fun emptySessionSaysSo() {
        val md = SessionExporter.toMarkdown("空", emptyList(), at)
        assertTrue(md.contains("（这个会话还没有消息）"))
    }

    @Test
    fun longToolResultIsTruncatedToOneLine() {
        val md = SessionExporter.toMarkdown(
            "t",
            listOf(ChatMessage("tool", "x".repeat(5000), name = "big")),
            at,
        )
        val line = md.lines().first { it.startsWith("> 👁") }
        assertTrue(line.length < 800)
        assertFalse(line.contains("\n"))
    }

    @Test
    fun fileStemSanitizesTitle() {
        val stem = SessionExporter.fileStem("会话/名: 带*危险?字符", at)
        assertFalse(stem.contains("/"))
        assertFalse(stem.contains("*"))
        assertFalse(stem.contains("?"))
        assertTrue(stem.startsWith("acfcn-"))
    }

    @Test
    fun fileStemFallsBackWhenTitleBlank() {
        val stem = SessionExporter.fileStem("   ", at)
        assertTrue(stem.startsWith("acfcn-session-"))
    }
}
