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
