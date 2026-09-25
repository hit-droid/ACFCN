package com.selfmod.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAssemblerTest {

    @Test
    fun accumulatesContentDeltas() {
        val a = StreamAssembler()
        assertEquals("Hel", a.applyLine("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}"))
        assertEquals("lo", a.applyLine("data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}"))
        assertNull(a.applyLine("data: [DONE]"))
        val r = a.result()
        assertEquals("Hello", r.content)
    }

    @Test
    fun accumulatesToolCallArguments() {
        val a = StreamAssembler()
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"browser_open\",\"arguments\":\"{\\\"url\\\":\"}}]}}]}")
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"x\\\"}\"}}]}}]}")
        a.applyLine("data: [DONE]")
        val r = a.result()
        assertEquals(1, r.toolCalls.size)
        assertEquals("browser_open", r.toolCalls[0].function.name)
        assertEquals("tool_calls", r.finishReason)
        assertTrue(r.toolCalls[0].function.arguments.contains("x"))
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val a = StreamAssembler()
        assertNull(a.applyLine(": keep-alive"))
        assertNull(a.applyLine(""))
        assertEquals("", a.result().content)
    }

    @Test
    fun parsesLlamaCppTopLevelContent() {
        val a = StreamAssembler()
        assertEquals("hi", a.applyLine("data: {\"content\":\"hi\"}"))
        assertEquals("hi", a.result().content)
    }
}
