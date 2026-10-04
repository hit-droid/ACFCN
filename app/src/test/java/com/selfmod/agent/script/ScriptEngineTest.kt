package com.selfmod.agent.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L11 — a script must not be able to hang the agent thread. These run the real
 * Rhino engine (pure JVM, interpreter mode) so the timeout/recursion caps are
 * exercised end-to-end, not mocked away.
 */
class ScriptEngineTest {

    /** Records everything the script sends through `api`/the prelude globals. */
    private class RecordingApi : ScriptApi {
        val logs = mutableListOf<String>()
        val memory = mutableMapOf<String, String>()
        val toasts = mutableListOf<String>()
        val notices = mutableListOf<Pair<String, String>>()
        var httpCalls = 0

        override fun log(msg: String) { logs += msg }
        override fun toast(msg: String) { toasts += msg }
        override fun httpGet(url: String, headers: String): String { httpCalls++; return "{}" }
        override fun httpPost(url: String, body: String, headers: String): String { httpCalls++; return "{}" }
        override fun llm(prompt: String): String = "echo:$prompt"
        override fun llmChat(messagesJson: String): String = "chat"
        override fun readScript(name: String): String? = null
        override fun writeScript(name: String, content: String): Boolean = true
        override fun listScripts(): String = "[]"
        override fun loadPlugin(name: String): Boolean = true
        override fun listPlugins(): String = "[]"
        override fun memoryGet(key: String): String? = memory[key]
        override fun memorySet(key: String, value: String) { memory[key] = value }
        override fun uiNotify(action: String, payload: String) { notices += action to payload }
        override fun now(): Long = 1234L
    }

    private val engine = ScriptEngine()

    @Test
    fun infiniteLoopIsStoppedByTheDeadline() {
        val start = System.currentTimeMillis()
        val res = engine.run("while(true){}", RecordingApi(), "loop.js", timeoutMs = 300)
        val elapsed = System.currentTimeMillis() - start
        assertNotNull("expected the loop to be killed", res.error)
        assertTrue("error should say TIMEOUT, got ${res.error}", res.error!!.startsWith("TIMEOUT"))
        assertTrue("should stop near the budget, took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun runawayRecursionTerminatesWithAnErrorInsteadOfHanging() {
        val start = System.currentTimeMillis()
        val res = engine.run("function r(){return r()+1;} r();", RecordingApi(), "rec.js", timeoutMs = 300)
        val elapsed = System.currentTimeMillis() - start
        assertNotNull("recursion must produce an error", res.error)
        assertTrue("must not hang, took ${elapsed}ms", elapsed < 8_000)
    }

    @Test
    fun aNormalScriptStillRunsAndReturnsItsValue() {
        val res = engine.run(
            "var s = 0; for (var k = 0; k < 5000; k++) { s += k; } s;",
            RecordingApi(), "sum.js", timeoutMs = 2_000,
        )
        assertNull("legit work must not be killed: ${res.error}", res.error)
        // 0+1+...+4999 = 12_497_500
        assertEquals(12_497_500.0, (res.value as Number).toDouble(), 0.0)
    }

    @Test
    fun timeoutZeroMeansNoDeadline() {
        // Heavy-but-legit loop: with no deadline it must finish, not time out.
        val res = engine.run(
            "var s = 0; for (var k = 0; k < 300000; k++) { s += k; } s;",
            RecordingApi(), "heavy.js", timeoutMs = 0,
        )
        assertNull("timeoutMs=0 disables the clock: ${res.error}", res.error)
    }

    @Test
    fun preludeApisReachTheInjectedApiAndLogsAreCaptured() {
        val api = RecordingApi()
        val res = engine.run(
            "log('hello'); store.set('a','1'); ui.notify('refresh', '{}'); toast('done'); 1 + 1;",
            api, "api.js", timeoutMs = 2_000,
        )
        assertNull(res.error)
        assertEquals(2.0, (res.value as Number).toDouble(), 0.0)
        assertTrue("script log() must forward to api.log", api.logs.contains("hello"))
        assertEquals("1", api.memory["a"])
        assertTrue("toast() must forward", api.toasts.contains("done"))
        assertTrue("ui.notify must forward", api.notices.contains("refresh" to "{}"))
        // ScriptResult.stdout keeps the collected log lines.
        assertTrue("stdout should carry the log line", res.stdout.contains("hello"))
    }

    @Test
    fun jsErrorsAreReportedNotThrown() {
        val res = engine.run("JSON.parse('{bad');", RecordingApi(), "err.js", timeoutMs = 2_000)
        assertNotNull(res.error)
        assertNull(res.value)
    }
}
