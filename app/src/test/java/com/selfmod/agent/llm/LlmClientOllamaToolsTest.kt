package com.selfmod.agent.llm

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** L14: Ollama 回落路径必须带上工具并能解析 tool_calls。 */
class LlmClientOllamaToolsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun ollamaFallbackSendsToolsAndParsesToolCalls() {
        var ollamaBody: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("/v1/chat/completions")) {
                    MockResponse().setResponseCode(404).setBody("""{"error":"no openai endpoint"}""")
                } else {
                    ollamaBody = request.body.readUtf8()
                    MockResponse()
                        .setBody(
                            """{"message":{"content":"","tool_calls":""" +
                                """[{"function":{"name":"browser_open","arguments":{"url":"https://x.com"}}}]},"done":true}""",
                        )
                        .addHeader("Content-Type", "application/json")
                }
            }
        }

        val client = LlmClient()
        val cfg = cfg(supportsNativeTools = true)
        val tools = listOf(ToolSpec("browser_open", "打开页面", """{"type":"object","properties":{}}"""))

        val result = client.chat(cfg, listOf(ChatMessage("user", "打开 x.com")), tools)

        assertEquals(1, result.toolCalls.size)
        assertEquals("browser_open", result.toolCalls[0].function.name)
        assertTrue(result.toolCalls[0].function.arguments.contains("https://x.com"))
        assertEquals("tool_calls", result.finishReason)

        val sent = JSONObject(ollamaBody!!)
        val sentTools = sent.getJSONArray("tools")
        assertEquals("browser_open", sentTools.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("带工具时应走非流式，保证 tool_calls 完整", false, sent.getBoolean("stream"))
    }

    @Test
    fun ollamaFallbackStringArgumentsAlsoAccepted() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("/v1/chat/completions")) {
                    MockResponse().setResponseCode(404).setBody("""{"error":"no"}""")
                } else {
                    MockResponse()
                        .setBody(
                            """{"message":{"content":"","tool_calls":""" +
                                """[{"function":{"name":"t","arguments":"{\"a\":1}"}}]},"done":true}""",
                        )
                        .addHeader("Content-Type", "application/json")
                }
            }
        }
        val client = LlmClient()
        val result = client.chat(
            cfg(supportsNativeTools = true),
            listOf(ChatMessage("user", "go")),
            listOf(ToolSpec("t", "test", "{}")),
        )
        assertEquals("t", result.toolCalls[0].function.name)
        assertEquals("{\"a\":1}", result.toolCalls[0].function.arguments)
    }

    @Test
    fun ollamaFallbackWithoutToolsStillStreams() {
        var ollamaBody: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("/v1/chat/completions")) {
                    MockResponse().setResponseCode(404).setBody("""{"error":"no"}""")
                } else {
                    ollamaBody = request.body.readUtf8()
                    MockResponse()
                        .setBody("{\"message\":{\"content\":\"Hi\"},\"done\":false}\n{\"message\":{\"content\":\"!\"},\"done\":true}\n")
                        .addHeader("Content-Type", "application/x-ndjson")
                }
            }
        }
        val client = LlmClient()
        val collected = StringBuilder()
        val result = client.chat(
            cfg(supportsNativeTools = false),
            listOf(ChatMessage("user", "hi")),
            onDelta = { collected.append(it) },
        )
        assertEquals("Hi!", collected.toString())
        assertEquals("Hi!", result.content)
        assertEquals("无工具时应保持流式", true, JSONObject(ollamaBody!!).getBoolean("stream"))
    }

    private fun cfg(supportsNativeTools: Boolean) = LlmConfig(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "",
        model = "llama3.2",
        timeoutSeconds = 10,
        kind = LlmConfig.KIND_LOCAL,
        supportsNativeTools = supportsNativeTools,
    )
}
