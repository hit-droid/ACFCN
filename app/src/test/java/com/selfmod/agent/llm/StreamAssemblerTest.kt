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

    @Test
    fun separatesCallsWhenProviderOmitsIndex() {
        val a = StreamAssembler()
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"browser_open\",\"arguments\":\"{\\\"url\\\":1\"}}]}}]}")
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"c2\",\"function\":{\"name\":\"browser_type\",\"arguments\":\"{\\\"text\\\":2\"}}]}}]}")
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"c2\",\"function\":{\"arguments\":\"}\"}}]}}]}")
        val r = a.result()
        assertEquals(2, r.toolCalls.size)
        assertEquals("c1", r.toolCalls[0].id)
        assertEquals("browser_open", r.toolCalls[0].function.name)
        assertEquals("c2", r.toolCalls[1].id)
        assertEquals("browser_type", r.toolCalls[1].function.name)
        assertEquals("{\"text\":2}", r.toolCalls[1].function.arguments)
    }

    @Test
    fun doesNotDuplicateNameWhenEveryDeltaResendsIt() {
        val a = StreamAssembler()
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"browser_open\",\"arguments\":\"{\\\"a\\\":\"}}]}}]}")
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"browser_open\",\"arguments\":\"1}\"}}]}}]}")
        val r = a.result()
        assertEquals(1, r.toolCalls.size)
        assertEquals("browser_open", r.toolCalls[0].function.name)
        assertEquals("{\"a\":1}", r.toolCalls[0].function.arguments)
    }

    @Test
    fun continuationWithoutIndexOrIdAppendsToLastCall() {
        val a = StreamAssembler()
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"t1\",\"arguments\":\"1\"}}]}}]}")
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"t2\",\"arguments\":\"2\"}}]}}]}")
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"3\"}}]}}]}")
        val r = a.result()
        assertEquals(2, r.toolCalls.size)
        assertEquals("1", r.toolCalls[0].function.arguments)
        assertEquals("23", r.toolCalls[1].function.arguments)
    }

    @Test
    fun firstIndexlessIdlessChunkStartsCallZero() {
        val a = StreamAssembler()
        a.applyLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}]}}]}")
        val r = a.result()
        assertEquals(1, r.toolCalls.size)
        assertEquals("t", r.toolCalls[0].function.name)
    }
}
