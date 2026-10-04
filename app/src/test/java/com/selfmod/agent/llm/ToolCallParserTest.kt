package com.selfmod.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {
    @Test
    fun parsesXmlToolCall() {
        val text = """
            我先打开页面
            <tool_call>
            {"name":"browser_open","arguments":{"url":"https://example.com"}}
            </tool_call>
        """.trimIndent()
        val calls = ToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("browser_open", calls[0].function.name)
        assertTrue(calls[0].function.arguments.contains("example.com"))
        assertTrue(ToolCallParser.stripMarkup(text).contains("我先打开页面"))
    }

    @Test
    fun parsesReactAction() {
        val text = """
            Thought: search
            Action: execute_js
            Action Input: {"code":"1+1"}
        """.trimIndent()
        val calls = ToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("execute_js", calls[0].function.name)
        assertTrue(calls[0].function.arguments.contains("1+1"))
    }

    @Test
    fun pairsMultipleActionsInOrder() {
        val text = """
            Thought: go
            Action: browser_open
            Action Input: {"url":"https://a.com"}
            Thought: then type
            Action: browser_type
            Action Input: {"text":"hi"}
        """.trimIndent()
        val calls = ToolCallParser.parse(text)
        assertEquals(2, calls.size)
        assertEquals("browser_open", calls[0].function.name)
        assertTrue(calls[0].function.arguments.contains("a.com"))
        assertEquals("browser_type", calls[1].function.name)
        assertTrue(calls[1].function.arguments.contains("hi"))
    }

    @Test
    fun missingInputDoesNotStealNextActionsInput() {
        val text = """
            Thought: hmm
            Action: browser_open
            Thought: need the url first, no input yet
            Action: browser_type
            Action Input: {"text":"hi"}
        """.trimIndent()
        val calls = ToolCallParser.parse(text)
        assertEquals(2, calls.size)
        assertEquals("{}", calls[0].function.arguments)
        assertTrue("第二条应拿到自己的输入", calls[1].function.arguments.contains("hi"))
    }

    @Test
    fun strayInputBeforeFirstActionIsIgnored() {
        val text = """
            Action Input: {"stray":true}
            Action: browser_open
            Action Input: {"url":"https://a.com"}
        """.trimIndent()
        val calls = ToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertTrue("应配对 Action 之后的输入", calls[0].function.arguments.contains("a.com"))
        assertTrue(!calls[0].function.arguments.contains("stray"))
    }

    @Test
    fun inputBelongingToEarlierActionIsNotReusedByLaterOne() {
        val text = """
            Action: browser_open
            Action Input: {"url":"https://a.com"}
            Thought: now again without input
            Action: browser_snapshot
        """.trimIndent()
        val calls = ToolCallParser.parse(text)
        assertEquals(2, calls.size)
        assertTrue(calls[0].function.arguments.contains("a.com"))
        assertEquals("{}", calls[1].function.arguments)
    }

    @Test
    fun parsesBareJson() {
        val calls = ToolCallParser.parse("""{"name":"list_scripts","arguments":{}}""")
        assertEquals("list_scripts", calls[0].function.name)
    }

    @Test
    fun emptyWhenPlainAnswer() {
        assertTrue(ToolCallParser.parse("任务已完成，没有要调用的工具。").isEmpty())
    }
}

class LlmConfigTest {
    @Test
    fun localHostNeedsNoKey() {
        val cfg = LlmConfig(baseUrl = "http://127.0.0.1:11434/v1", apiKey = "", kind = LlmConfig.KIND_LOCAL)
        assertTrue(cfg.isLocalHost())
        assertTrue(cfg.isUsable())
        assertEquals(null, cfg.authHeader())
    }

    @Test
    fun cloudNeedsKey() {
        val cfg = LlmConfig(baseUrl = "https://api.openai.com/v1", apiKey = "")
        assertEquals(false, cfg.isUsable())
        val ok = cfg.copy(apiKey = "sk-test")
        assertTrue(ok.isUsable())
        assertEquals("Bearer sk-test", ok.authHeader())
    }

    // L1：端侧 5-15 tok/s 下，2048 token 默认值意味着单条答复要生成数分钟。
    @Test
    fun maxTokensDefaultIsMobileFriendly() {
        assertEquals(512, LlmConfig().maxTokens)
    }

    @Test
    fun privateRangesAreLocal() {
        // 172.17–172.31 此前被旧的 startsWith("172.16.") 漏判。
        assertTrue(LlmConfig(baseUrl = "http://172.17.0.1:8080/v1").isLocalHost())
        assertTrue(LlmConfig(baseUrl = "http://172.31.255.254:8080/v1").isLocalHost())
        assertTrue(LlmConfig(baseUrl = "http://10.1.2.3:11434/v1").isLocalHost())
        assertTrue(LlmConfig(baseUrl = "http://192.168.1.50:1234/v1").isLocalHost())
        assertTrue(LlmConfig(baseUrl = "http://localhost:11434/v1").isLocalHost())
        assertTrue(LlmConfig(baseUrl = "http://10.0.2.2:11434/v1").isLocalHost())
    }

    @Test
    fun publicRangesAreNotLocal() {
        assertEquals(false, LlmConfig(baseUrl = "https://api.openai.com/v1").isLocalHost())
        assertEquals(false, LlmConfig(baseUrl = "http://172.32.0.1:8080/v1").isLocalHost())
        assertEquals(false, LlmConfig(baseUrl = "http://8.8.8.8:8080/v1").isLocalHost())
    }

    // 端侧配置里的模型路径/上下文不能被 API 页的表单覆盖时清掉。
    @Test
    fun copyPreservesOnDeviceFields() {
        val onDevice = LlmConfig(
            kind = LlmConfig.KIND_ONDEVICE,
            onDeviceModelPath = "/data/models/qwen.gguf",
            onDeviceContext = 1024,
        )
        val afterApiPageSave = onDevice.copy(
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        )
        assertEquals("/data/models/qwen.gguf", afterApiPageSave.onDeviceModelPath)
        assertEquals(1024, afterApiPageSave.onDeviceContext)
    }
}
