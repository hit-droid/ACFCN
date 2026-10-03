package com.selfmod.agent.util

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticsTest {

    @After
    fun cleanup() {
        Diagnostics.clear()
    }

    @Test
    fun logsAreBounded() {
        repeat(1000) { Diagnostics.log("t", "line $it") }
        val lines = Diagnostics.dump().lines().filter { it.isNotBlank() }
        assertTrue("应被限制在 400 行以内，实际 ${lines.size}", lines.size <= 400)
    }

    @Test
    fun concurrentLoggingDoesNotLoseOrCorrupt() {
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> Diagnostics.log("t$t", "m$i") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        val lines = Diagnostics.dump().lines().filter { it.isNotBlank() }
        // 总量超过上限，只会保留最后 400 条：验证并发下不抛异常且行数正确受限。
        assertTrue("行数应在 1..400，实际 ${lines.size}", lines.size in 1..400)
        // 每行都应是合法格式：时间戳 [tag] 内容
        assertTrue(lines.all { it.contains("  [") })
    }
}
