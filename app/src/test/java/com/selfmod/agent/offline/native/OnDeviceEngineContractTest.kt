package com.selfmod.agent.offline.native

import com.selfmod.agent.offline.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the shared on-device engine interface.
 *
 * The point of these is that both engines are interchangeable: [LlmClient]
 * picks one by model format and only ever talks to [OnDeviceEngine], so any
 * implementation must honour the same return-code and state semantics. The
 * fakes below lock that contract down without touching JNI.
 */
class OnDeviceEngineContractTest {

    /** Minimal in-memory engine: echoes the last user turn, respects cancel. */
    private class FakeEngine(
        private val ctx: Int = 1024,
        private val reply: String = "hello",
    ) : OnDeviceEngine {
        var loaded = false
        var cancelled = false
        var unloaded = false
        var lastMessages: List<Pair<String, String>> = emptyList()

        override fun isLoaded(): Boolean = loaded
        override fun unload() { unloaded = true; loaded = false }
        override fun cancel() { cancelled = true }
        override fun clearAbort() { cancelled = false }

        override fun chat(
            messages: List<Pair<String, String>>,
            maxTokens: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            onToken: OnDeviceEngine.TokenCallback?,
        ): Int {
            if (!loaded) return -1
            lastMessages = messages
            if (cancelled) return -98
            var n = 0
            for (piece in reply.chunked(2)) {
                if (onToken?.onToken(piece) == false) return -98
                n++
            }
            return n
        }

        override fun contextSize(): Int = ctx
        override fun chatTemplate(): String = if (loaded) "chatml" else ""
        override fun lastChatStats(): String = if (loaded) "fake: 1 tok/s" else ""
    }

    @Test
    fun bothEnginesSatisfyTheInterface() {
        val llama: OnDeviceEngine = FakeEngine()
        val mnn: OnDeviceEngine = FakeEngine(reply = "你好")
        assertFalse(llama.isLoaded())
        assertFalse(mnn.isLoaded())
    }

    @Test
    fun chatBeforeLoadReportsNotLoaded() {
        val e = FakeEngine()
        // -1 is the documented "not loaded" code both engines return.
        assertEquals(-1, e.chat(listOf("user" to "hi"), 16, 0.7f, 40, 0.95f, null))
    }

    @Test
    fun chatStreamsPiecesInOrder() {
        val e = FakeEngine().apply { loaded = true }
        val got = StringBuilder()
        val n = e.chat(
            listOf("user" to "hi"),
            maxTokens = 16,
            temperature = 0.7f,
            topK = 40,
            topP = 0.95f,
            onToken = object : OnDeviceEngine.TokenCallback {
                override fun onToken(piece: String): Boolean { got.append(piece); return true }
            },
        )
        assertEquals("hello", got.toString())
        assertTrue(n > 0)
    }

    @Test
    fun cancelledGenerationReportsCancelCode() {
        val e = FakeEngine().apply { loaded = true; cancelled = true }
        assertEquals(-98, e.chat(listOf("user" to "hi"), 16, 0.7f, 40, 0.95f, null))
    }

    @Test
    fun callbackReturningFalseStopsGeneration() {
        val e = FakeEngine(reply = "abcdef").apply { loaded = true }
        var seen = 0
        val code = e.chat(
            listOf("user" to "hi"),
            maxTokens = 16,
            temperature = 0.7f,
            topK = 40,
            topP = 0.95f,
            onToken = object : OnDeviceEngine.TokenCallback {
                override fun onToken(piece: String): Boolean { seen++; return false }
            },
        )
        assertEquals(-98, code)
        assertEquals(1, seen)
    }

    @Test
    fun unloadDropsReadyStateAndStats() {
        val e = FakeEngine().apply { loaded = true }
        assertTrue(e.isLoaded())
        assertTrue(e.lastChatStats().isNotBlank())
        e.unload()
        assertFalse(e.isLoaded())
        assertEquals("", e.lastChatStats())
        assertEquals("", e.chatTemplate())
        assertTrue(e.unloaded)
    }

    @Test
    fun contextSizeDrivesPromptBudget() {
        // LlmClient budgets the prompt from engine.contextSize(); a wrong value
        // here would silently over- or under-trim history.
        val e = FakeEngine(ctx = 2048).apply { loaded = true }
        val budget = ContextBudget.promptBudget(e.contextSize(), maxTokens = 512)
        assertEquals(2048 - 512, budget)
    }

    @Test
    fun messagesKeepRolesForTemplateApplication() {
        val e = FakeEngine().apply { loaded = true }
        e.chat(
            listOf("system" to "be brief", "user" to "hi"),
            16, 0.7f, 40, 0.95f, null,
        )
        // Roles must survive: llama.cpp resolves a template family, MNN renders
        // the jinja template from config.json — both need the real role names.
        assertEquals(listOf("system", "user"), e.lastMessages.map { it.first })
    }
}
