package com.selfmod.agent.llm

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LlmClientTimeoutTest {

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
    fun readTimeoutUsesConfigAndMapsTo504() {
        server.enqueue(
            MockResponse()
                .setBodyDelay(8, TimeUnit.SECONDS)
                .setBody("""{"choices":[{"message":{"content":"late"},"finish_reason":"stop"}]}""")
                .addHeader("Content-Type", "application/json"),
        )
        val client = LlmClient()
        val cfg = cfg(timeoutSeconds = 5)
        val t0 = System.nanoTime()
        val ex = runCatching {
            client.chat(cfg, listOf(ChatMessage("user", "hi")))
        }.exceptionOrNull() as LlmException
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertEquals(504, ex.code)
        assertTrue(ex.message!!.contains("超时"))
        assertTrue("应在配置的 5s 左右失败，实际 ${elapsedMs}ms", elapsedMs in 4000..7000)
    }

    @Test
    fun cancelAbortsInFlightHttpCall() {
        val hit = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                hit.countDown()
                return MockResponse()
                    .setBodyDelay(30, TimeUnit.SECONDS)
                    .setBody("""{"choices":[{"message":{"content":"late"},"finish_reason":"stop"}]}""")
                    .addHeader("Content-Type", "application/json")
            }
        }
        val client = LlmClient()
        val cfg = cfg(timeoutSeconds = 60)
        val error = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                client.chat(cfg, listOf(ChatMessage("user", "hi")))
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue("请求应到达服务端", hit.await(3, TimeUnit.SECONDS))
        client.cancel()
        assertTrue("cancel 应立刻打断请求", done.await(3, TimeUnit.SECONDS))
        val ex = error.get() as LlmException
        assertEquals(499, ex.code)
    }

    @Test
    fun isTimeoutDetectsSocketAndInterruptedTimeouts() {
        assertTrue(LlmClient.isTimeout(SocketTimeoutException("read timed out")))
        assertTrue(LlmClient.isTimeout(InterruptedIOException("timeout")))
        assertTrue(LlmClient.isTimeout(RuntimeException(SocketTimeoutException("nested"))))
    }

    private fun cfg(timeoutSeconds: Long) = LlmConfig(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test",
        model = "m",
        timeoutSeconds = timeoutSeconds,
        kind = LlmConfig.KIND_CLOUD,
        supportsNativeTools = false,
    )
}
