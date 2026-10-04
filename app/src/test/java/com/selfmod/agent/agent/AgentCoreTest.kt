package com.selfmod.agent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.selfmod.agent.browser.BrowserController
import com.selfmod.agent.llm.ChatMessage
import com.selfmod.agent.llm.LlmClient
import com.selfmod.agent.llm.LlmConfig
import com.selfmod.agent.offline.LocalModelStore
import com.selfmod.agent.plugin.PluginRegistry
import com.selfmod.agent.repo.CodeRepository
import com.selfmod.agent.script.ScriptApi
import com.selfmod.agent.script.ScriptEngine
import com.selfmod.agent.store.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * M15: `AgentCore` had no tests at all, even though it is the piece that decides
 * what the model is shown after every tool call. These drive the real loop —
 * `AgentCore` → real `LlmClient` → MockWebServer — so history shape (H4), the
 * repeat-call guard, the iteration ceiling, cancellation, error mapping and the
 * observation trim are all pinned by what actually reaches the wire, not by a stub.
 */
@RunWith(AndroidJUnit4::class)
class AgentCoreTest {

    private lateinit var server: MockWebServer
    private lateinit var app: Context
    private lateinit var core: AgentCore
    private lateinit var settings: SettingsStore
    private lateinit var repo: CodeRepository

    private val steps = Collections.synchronizedList(mutableListOf<AgentStep>())
    private val uiEvents = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    private var responses: MutableList<String> = mutableListOf()
    private var calls = 0
    private var cancelOnRequest = false
    private var failNextWith = 0

    /** Script host that records instead of touching the network or the real UI. */
    private inner class FakeScriptApi : ScriptApi {
        override fun log(msg: String) {}
        override fun toast(msg: String) {}
        override fun httpGet(url: String, headers: String): String = "FAKE-GET($url)"
        override fun httpPost(url: String, body: String, headers: String): String = "FAKE-POST($url)"
        override fun llm(prompt: String): String = "FAKE-LLM"
        override fun llmChat(messagesJson: String): String = "FAKE-CHAT"
        override fun readScript(name: String): String? =
            if (repo.scriptExists(name)) repo.readScript(name) else null
        override fun writeScript(name: String, content: String): Boolean {
            repo.writeScript(name, content); return true
        }
        override fun listScripts(): String = JSONArray(repo.listScripts()).toString()
        override fun loadPlugin(name: String): Boolean = false
        override fun listPlugins(): String = "[]"
        override fun memoryGet(key: String): String? = settings.memoryGet(key)
        override fun memorySet(key: String, value: String) = settings.memorySet(key, value)
        override fun uiNotify(action: String, payload: String) { uiEvents += action to payload }
        override fun now(): Long = System.currentTimeMillis()
    }

    // ------------------------------------------------------------- SSE fixtures
    // AgentCore always passes an onDelta callback, so LlmClient requests a stream
    // and parses SSE — the fixture has to speak the same protocol.

    private fun sse(vararg lines: String): String =
        lines.joinToString(separator = "\n\n") { "data: $it" } + "\n\ndata: [DONE]\n"

    private fun sseContent(text: String): String = sse(
        JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))).toString()
    )

    private fun sseToolCalls(vararg calls: Triple<String, String, String>): String {
        val arr = JSONArray()
        calls.forEachIndexed { i, (id, name, args) ->
            arr.put(
                JSONObject().put("index", i).put("id", id).put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", args))
            )
        }
        val first = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", arr))))
            .toString()
        val last = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "tool_calls")))
            .toString()
        return sse(first, last)
    }

    private fun memoryCall(id: String, key: String) = Triple(id, "get_memory", """{"key":"$key"}""")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (cancelOnRequest) { cancelOnRequest = false; core.cancel() }
                if (failNextWith != 0) {
                    val code = failNextWith
                    return MockResponse().setResponseCode(code).setBody("boom")
                }
                val i = calls++
                val body = responses.getOrElse(i) { responses.last() }
                return MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream").setBody(body)
            }
        }
        server.start()
        app = ApplicationProvider.getApplicationContext()
        val dir = File(app.cacheDir, "repo-${System.nanoTime()}")
        repo = CodeRepository(File(dir, "scripts"), File(dir, "plugins"), File(dir, "versions"))
        settings = SettingsStore(app)
        settings.setLlmConfig(
            LlmConfig(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "",
                model = "test-model",
                kind = LlmConfig.KIND_LOCAL,
                supportsNativeTools = true,
            )
        )
        core = AgentCore(
            llmClient = LlmClient(),
            settings = settings,
            scriptEngine = ScriptEngine(),
            scriptHost = FakeScriptApi(),
            repo = repo,
            plugins = PluginRegistry(repo, File(dir, "opt")),
            uiNotifier = { a, p -> uiEvents += a to p },
            browser = BrowserController(),
            models = LocalModelStore(app),
        )
        steps.clear(); uiEvents.clear(); calls = 0; cancelOnRequest = false; failNextWith = 0
        responses = mutableListOf()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun run(text: String = "你好"): Pair<String, MutableList<ChatMessage>> {
        val history = Collections.synchronizedList(mutableListOf(ChatMessage("system", "S")))
        calls = 0
        val out = runBlocking { core.run(history, text) { steps += it } }
        return out to history
    }

    private fun roles(h: List<ChatMessage>) = h.map { it.role }
    private fun actions() = steps.filterIsInstance<AgentStep.Action>().map { it.tool }
    private fun observations() = steps.filterIsInstance<AgentStep.Observation>().map { it.result }
    private fun nonNative() { settings.setLlmConfig(settings.llmConfig().copy(supportsNativeTools = false)) }

    // ------------------------------------------------------------ plain answer

    @Test
    fun `a plain answer ends the loop and appends user then assistant`() {
        responses = mutableListOf(sseContent("这是答案"))
        val (out, history) = run()
        assertEquals("这是答案", out)
        assertEquals(listOf("system", "user", "assistant"), roles(history))
        assertEquals("你好", history[1].content)
        assertEquals(AgentStep.Answer("这是答案"), steps.last())
        assertTrue(steps.first() == AgentStep.Started)
        assertEquals(1, calls)
    }

    @Test
    fun `streamed deltas are forwarded as StreamDelta steps`() {
        responses = mutableListOf(sse(
            """{"choices":[{"delta":{"content":"你好"}}]}""",
            """{"choices":[{"delta":{"content":"，世界"}}]}""",
        ))
        val (out) = run()
        assertEquals("你好，世界", out)
        assertEquals(listOf("你好", "，世界"), steps.filterIsInstance<AgentStep.StreamDelta>().map { it.text })
    }

    @Test
    fun `system prompt advertises the registered tools`() {
        val p = core.systemPrompt()
        assertTrue(p.contains("execute_js"))
        assertTrue(p.contains("get_memory"))
        assertTrue(p.contains("browser_snapshot"))
    }

    // ------------------------------------------------------- native tool loop

    @Test
    fun `native tool call runs, the answer comes next turn, history is OpenAI-shaped`() {
        settings.memorySet("answer", "42")
        responses = mutableListOf(
            sseToolCalls(memoryCall("c1", "answer")),
            sseContent("记忆里的值是 42"),
        )
        val (out, history) = run("读一下 answer")
        assertEquals("记忆里的值是 42", out)
        assertEquals(listOf("get_memory"), actions())
        assertTrue(observations().single().contains("42"))

        assertEquals(listOf("system", "user", "assistant", "tool", "assistant"), roles(history))
        val assistant = history[2]
        assertEquals(1, assistant.toolCalls.size)
        assertEquals("c1", assistant.toolCalls[0].id)
        assertEquals("get_memory", assistant.toolCalls[0].function.name)
        val toolMsg = history[3]
        assertEquals("tool", toolMsg.role)
        assertEquals("c1", toolMsg.toolCallId)
        assertEquals("get_memory", toolMsg.name)
    }

    @Test
    fun `a tool error is reported back to the model instead of failing the run`() {
        responses = mutableListOf(
            sseToolCalls(Triple("c1", "read_script", """{"name":"nope"}""")),
            sseContent("文件不存在"),
        )
        val (out, history) = run()
        assertEquals("文件不存在", out)
        assertTrue("model must see the reason: ${history[3].content}",
            history[3].content.contains("no such script"))
    }

    @Test
    fun `oversized output is trimmed for the UI but sent whole to the model`() {
        settings.memorySet("big", "x".repeat(9000))
        responses = mutableListOf(
            sseToolCalls(memoryCall("c1", "big")),
            sseContent("好了"),
        )
        val (_, history) = run()
        val obs = observations().single()
        assertTrue("UI observation must be truncated (len=${obs.length})", obs.length < 6200)
        assertTrue(obs.endsWith("...(truncated)"))
        assertTrue("model context keeps the full payload", history[3].content.length > 9000)
    }

    @Test
    fun `several calls in one turn all run and each gets its own tool message`() {
        settings.memorySet("a", "1"); settings.memorySet("b", "2")
        responses = mutableListOf(
            sseToolCalls(memoryCall("c1", "a"), memoryCall("c2", "b")),
            sseContent("两个都读到了"),
        )
        val (_, history) = run()
        assertEquals(listOf("get_memory", "get_memory"), actions())
        assertEquals(listOf("system", "user", "assistant", "tool", "tool", "assistant"), roles(history))
        assertEquals("c1", history[3].toolCallId)
        assertEquals("c2", history[4].toolCallId)
        assertTrue(history[3].content.contains("1"))
        assertTrue(history[4].content.contains("2"))
    }

    // ---------------------------------------------------------- ReAct / H4 mode

    @Test
    fun `non-native mode folds observations into one user turn so roles keep alternating`() {
        nonNative()
        settings.memorySet("answer", "42")
        responses = mutableListOf(
            sseContent("Action: get_memory\nAction Input: {\"key\":\"answer\"}"),
            sseContent("值是 42"),
        )
        val (out, history) = run()
        assertEquals("值是 42", out)
        assertEquals(listOf("system", "user", "assistant", "user", "assistant"), roles(history))
        val obs = history[3].content
        assertTrue(obs.contains("Observation from get_memory:"))
        assertTrue(obs.contains("42"))
        assertFalse("no two consecutive user turns allowed",
            roles(history).zipWithNext().any { (a, b) -> a == "user" && b == "user" })
    }

    @Test
    fun `several ReAct calls in one turn still collapse into a single observation message`() {
        nonNative()
        settings.memorySet("a", "1"); settings.memorySet("b", "2")
        responses = mutableListOf(
            sseContent("Action: get_memory\nAction Input: {\"key\":\"a\"}\nAction: get_memory\nAction Input: {\"key\":\"b\"}"),
            sseContent("done"),
        )
        val (_, history) = run()
        assertEquals(2, actions().size)
        assertEquals(listOf("system", "user", "assistant", "user", "assistant"), roles(history))
        assertEquals(2, Regex("Observation from").findAll(history[3].content).count())
        // The assistant turn is re-materialised as ReAct markup the model can re-read.
        assertEquals(2, Regex("^Action: get_memory\$", RegexOption.MULTILINE).findAll(history[2].content).count())
    }

    @Test
    fun `non-native tool requests are not sent over the wire as native tools`() {
        nonNative()
        settings.memorySet("answer", "42")
        responses = mutableListOf(
            sseContent("Action: get_memory\nAction Input: {\"key\":\"answer\"}"),
            sseContent("好了"),
        )
        run()
        val first = server.takeRequest()
        assertFalse("a model without native function calling must not be handed a tools array",
            JSONObject(first.body.readUtf8()).has("tools"))
    }

    // -------------------------------------------------------- repeat guard (H4)

    @Test
    fun `the third identical call is refused and the model is told why`() {
        val one = sseToolCalls(memoryCall("c1", "k"))
        responses = mutableListOf(one, one, one)
        val (out) = run()
        assertTrue("should bail out: $out", out.contains("陷入重复调用"))
        assertEquals(2, actions().size)
        assertEquals(1, observations().count { it.contains("重复调用检测") })
        assertEquals(3, calls)
    }

    @Test
    fun `a turn whose every call is a repeat stops immediately instead of burning iterations`() {
        val two = sseToolCalls(memoryCall("c1", "a"), memoryCall("c2", "b"))
        responses = mutableListOf(two, two, two, two, two, two, two, two, two, two, two, two)
        settings.memorySet("a", "1"); settings.memorySet("b", "2")
        val (out) = run()
        assertTrue(out.contains("陷入重复调用"))
        assertEquals("must stop at the third turn, not the twelfth", 3, calls)
        assertTrue(steps.last() is AgentStep.Error)
    }

    @Test
    fun `whitespace-only argument differences still count as the same call`() {
        val spaced = sseToolCalls(Triple("c1", "get_memory", """{"key": "k" }"""))
        val tight = sseToolCalls(Triple("c1", "get_memory", """{"key":"k"}"""))
        settings.memorySet("k", "v")
        responses = mutableListOf(tight, spaced, spaced, tight)
        val (out) = run()
        assertTrue("canonicalised args must trip the guard: $out", out.contains("陷入重复调用"))
        assertEquals(2, actions().size)
    }

    @Test
    fun `the guard resets between runs`() {
        val one = sseToolCalls(memoryCall("c1", "g"))
        settings.memorySet("g", "v")
        responses = mutableListOf(one, sseContent("第一段结束"))
        run()
        assertEquals(listOf("get_memory"), actions())
        steps.clear(); responses = mutableListOf(one, sseContent("第二段结束"))
        val (out) = run()
        assertEquals("第二段结束", out)
        assertEquals(listOf("get_memory"), actions())
        assertEquals(0, observations().count { it.contains("重复调用检测") })
    }

    // ----------------------------------------------------------- stopping/errors

    @Test
    fun `cancelling mid-request returns the stop marker and an Error step`() {
        cancelOnRequest = true
        responses = mutableListOf(sseContent("永远不会被处理"))
        val (out, history) = run()
        assertEquals("已停止", out)
        assertEquals(AgentStep.Error("已停止"), steps.last())
        assertEquals(listOf("system", "user"), roles(history))
    }

    @Test
    fun `an HTTP error becomes ERROR plus an Error step, never an exception`() {
        failNextWith = 500
        responses = mutableListOf(sseContent("never served"))
        val (out, history) = run()
        assertTrue("expected ERROR prefix, got $out", out.startsWith("ERROR:"))
        assertTrue(steps.last() is AgentStep.Error)
        assertEquals(listOf("system", "user"), roles(history))
    }

    @Test
    fun `the iteration ceiling stops a model that never finishes and says so`() {
        // Each turn issues a *different* call so only MAX_ITERATIONS can end it.
        responses = (1..20).map { i -> sseToolCalls(memoryCall("c$i", "k$i")) }.toMutableList()
        val (out, history) = run()
        assertTrue("should hit the ceiling: $out", out.contains("已达到最大推理步数"))
        assertEquals(12, calls)
        assertTrue(steps.last() is AgentStep.Error)
        assertEquals(2 + 12 * 2, history.size)
    }

    // -------------------------------------------------------------- tool bridge

    @Test
    fun `ui_notify reaches the notifier with action and payload`() {
        responses = mutableListOf(
            sseToolCalls(Triple("u1", "ui_notify", """{"action":"toast","payload":"{\"msg\":\"hi\"}"}""")),
            sseContent("已通知"),
        )
        run()
        assertEquals(1, uiEvents.size)
        assertEquals("toast" to "{\"msg\":\"hi\"}", uiEvents[0])
    }

    @Test
    fun `write_script through the loop really lands in the repository`() {
        responses = mutableListOf(
            sseToolCalls(Triple("w1", "write_script", """{"name":"demo","content":"api.log(1)"}""")),
            sseContent("写好了"),
        )
        run()
        assertEquals("api.log(1)", repo.readScript("demo"))
    }

    @Test
    fun `a tool that throws is still reported as an error string, not propagated`() {
        // install_plugin requires android.util.Base64, which is not real in a unit
        // test: whatever it throws must be swallowed into a tool error for the model.
        responses = mutableListOf(
            sseToolCalls(Triple("i1", "install_plugin", """{"name":"p","entry":"E","dex_b64":"AAA"}""")),
            sseContent("算了"),
        )
        val (out, history) = run()
        assertEquals("算了", out)
        val toolMsg = history.first { it.role == "tool" }
        assertTrue("expected an error envelope, got ${toolMsg.content}",
            toolMsg.content.contains("tool error") || toolMsg.content.contains("\"ok\":false"))
    }
}
