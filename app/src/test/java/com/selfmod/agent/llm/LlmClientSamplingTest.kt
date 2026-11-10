package com.selfmod.agent.llm

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M2: remote chat bodies must carry the sampling knobs from LlmConfig. */
class LlmClientSamplingTest {

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
    fun cloudOpenAiOmitsTopKAndNegativeSeed() {
        val sent = SamplingPayload.openAi(
            LlmConfig(
                baseUrl = "https://api.openai.com/v1",
                kind = LlmConfig.KIND_CLOUD,
                topP = 0.8,
                topK = 20,
                seed = -1L,
                presencePenalty = 0.4,
                frequencyPenalty = 0.2,
            ),
        )
        assertEquals(0.8, sent.getDouble("top_p"), 0.0)
        assertFalse("云端 OpenAI 兼容接口不发 top_k", sent.has("top_k"))
        assertFalse("seed=-1 表示随机，不应写入请求", sent.has("seed"))
        assertEquals(0.4, sent.getDouble("presence_penalty"), 0.0)
        assertEquals(0.2, sent.getDouble("frequency_penalty"), 0.0)
        assertEquals(0.6, sent.getDouble("temperature"), 0.0)
        assertEquals(512, sent.getInt("max_tokens"))
    }

    @Test
    fun cloudOpenAiIncludesPositiveSeed() {
        val sent = SamplingPayload.openAi(
            LlmConfig(baseUrl = "https://api.openai.com/v1", kind = LlmConfig.KIND_CLOUD, seed = 42L),
        )
        assertEquals(42L, sent.getLong("seed"))
    }

    @Test
    fun localOpenAiCompatIncludesTopK() {
        val sent = SamplingPayload.openAi(
            LlmConfig(baseUrl = "http://127.0.0.1:11434/v1", kind = LlmConfig.KIND_LOCAL, topK = 12),
        )
        assertEquals(12, sent.getInt("top_k"))
        assertTrue(sent.has("top_p"))
    }

    @Test
    fun ollamaOptionsCarryPenaltiesAndSeed() {
        val opts = SamplingPayload.ollama(
            LlmConfig(
                topP = 0.7,
                topK = 15,
                seed = 7L,
                repeatPenalty = 1.15,
                presencePenalty = 0.1,
                frequencyPenalty = 0.05,
            ),
        )
        assertEquals(0.7, opts.getDouble("top_p"), 0.0)
        assertEquals(15, opts.getInt("top_k"))
        assertEquals(7L, opts.getLong("seed"))
        assertEquals(1.15, opts.getDouble("repeat_penalty"), 0.0)
        assertEquals(0.1, opts.getDouble("presence_penalty"), 0.0)
        assertEquals(0.05, opts.getDouble("frequency_penalty"), 0.0)
        assertEquals(512, opts.getInt("num_predict"))
    }

    @Test
    fun llamaCppCompletionCarriesSampling() {
        val body = SamplingPayload.llamaCpp(LlmConfig(topK = 8, seed = 3L, repeatPenalty = 1.2))
        assertEquals(8, body.getInt("top_k"))
        assertEquals(3L, body.getLong("seed"))
        assertEquals(1.2, body.getDouble("repeat_penalty"), 0.0)
        assertEquals(512, body.getInt("n_predict"))
    }

    @Test
    fun openAiHttpBodyUsesSamplingPayload() {
        var body: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                body = request.body.readUtf8()
                return MockResponse()
                    .setBody("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
                    .addHeader("Content-Type", "application/json")
            }
        }
        LlmClient().chat(
            LlmConfig(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "test",
                model = "m",
                timeoutSeconds = 10,
                kind = LlmConfig.KIND_CLOUD,
                supportsNativeTools = false,
                topP = 0.8,
                seed = 42L,
                presencePenalty = 0.4,
            ),
            listOf(ChatMessage("user", "hi")),
        )
        val sent = JSONObject(body!!)
        assertEquals(0.8, sent.getDouble("top_p"), 0.0)
        assertEquals(42L, sent.getLong("seed"))
        assertEquals(0.4, sent.getDouble("presence_penalty"), 0.0)
        assertEquals("m", sent.getString("model"))
    }

    @Test
    fun ollamaFallbackSendsSamplingOptions() {
        var ollamaBody: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("/v1/chat/completions")) {
                    MockResponse().setResponseCode(404).setBody("""{"error":"no openai"}""")
                } else {
                    ollamaBody = request.body.readUtf8()
                    MockResponse()
                        .setBody("""{"message":{"content":"hi"},"done":true}""")
                        .addHeader("Content-Type", "application/json")
                }
            }
        }
        LlmClient().chat(
            LlmConfig(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "",
                model = "m",
                timeoutSeconds = 10,
                kind = LlmConfig.KIND_LOCAL,
                supportsNativeTools = false,
                topP = 0.7,
                topK = 15,
                seed = 7L,
                repeatPenalty = 1.15,
            ),
            listOf(ChatMessage("user", "hi")),
        )
        val opts = JSONObject(ollamaBody!!).getJSONObject("options")
        assertEquals(0.7, opts.getDouble("top_p"), 0.0)
        assertEquals(15, opts.getInt("top_k"))
        assertEquals(7L, opts.getLong("seed"))
        assertEquals(1.15, opts.getDouble("repeat_penalty"), 0.0)
    }
}
