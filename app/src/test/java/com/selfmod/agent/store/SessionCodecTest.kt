package com.selfmod.agent.store

import com.selfmod.agent.llm.ChatMessage
import com.selfmod.agent.llm.ToolCall
import com.selfmod.agent.llm.ToolFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCodecTest {

    @Test
    fun roundTripsAssistantToolCalls() {
        val conv = listOf(
            ChatMessage("user", "打开页面"),
            ChatMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    ToolCall("call_1", "function", ToolFunction("browser_open", "{\"url\":\"https://x\"}")),
                ),
            ),
            ChatMessage("tool", "loaded", name = "browser_open", toolCallId = "call_1"),
        )
        val restored = SessionCodec.decode(SessionCodec.encode(conv))
        assertEquals(3, restored.size)
        val asst = restored[1]
        assertEquals(1, asst.toolCalls.size)
        assertEquals("call_1", asst.toolCalls[0].id)
        assertEquals("browser_open", asst.toolCalls[0].function.name)
        assertEquals("{\"url\":\"https://x\"}", asst.toolCalls[0].function.arguments)
        assertEquals("call_1", restored[2].toolCallId)
        assertEquals("browser_open", restored[2].name)
    }

    @Test
    fun dropsSystemMessages() {
        val out = SessionCodec.decode(
            SessionCodec.encode(listOf(ChatMessage("system", "prompt"), ChatMessage("user", "hi"))),
        )
        assertEquals(1, out.size)
        assertEquals("user", out[0].role)
    }

    @Test
    fun capsContentLength() {
        val out = SessionCodec.decode(SessionCodec.encode(listOf(ChatMessage("user", "x".repeat(20_000)))))
        assertEquals(SessionCodec.MAX_CONTENT, out[0].content.length)
    }

    @Test
    fun capsMessageCount() {
        val messages = ArrayList<ChatMessage>()
        repeat(200) { messages += ChatMessage("user", "u$it") }
        val out = SessionCodec.decode(SessionCodec.encode(messages))
        assertEquals(SessionCodec.MAX_MESSAGES, out.size)
        assertEquals("u120", out.first().content)
        assertEquals("u199", out.last().content)
    }

    @Test
    fun truncationDropsLeadingOrphanTool() {
        // 81 messages: the 80-message window drops the assistant(tool_calls) at
        // index 0 and would start with its now-orphan `tool` result -> drop it.
        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage(
            "assistant", "", toolCalls = listOf(ToolCall("c", function = ToolFunction("t", "{}"))),
        )
        messages += ChatMessage("tool", "r", toolCallId = "c")
        repeat(39) {
            messages += ChatMessage("user", "u$it")
            messages += ChatMessage("assistant", "a$it")
        }
        messages += ChatMessage("user", "tail")
        assertEquals(81, messages.size)

        val out = SessionCodec.decode(SessionCodec.encode(messages))
        assertEquals(79, out.size)
        assertEquals("user", out.first().role)
        assertTrue("孤儿 tool 应被丢弃", out.none { it.role == "tool" })
    }

    @Test
    fun decodeDropsOrphanToolPrefix() {
        val raw = """[{"role":"tool","content":"r","toolCallId":"c"},{"role":"user","content":"hi"}]"""
        val out = SessionCodec.decode(raw)
        assertEquals(1, out.size)
        assertEquals("user", out[0].role)
    }

    @Test
    fun malformedJsonYieldsEmpty() {
        assertTrue(SessionCodec.decode("not json").isEmpty())
        assertTrue(SessionCodec.decode("[]").isEmpty())
    }
}
