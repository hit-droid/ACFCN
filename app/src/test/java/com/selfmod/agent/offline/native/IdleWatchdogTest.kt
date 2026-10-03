package com.selfmod.agent.offline.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class IdleWatchdogTest {

    @Test
    fun returnsWorkerResultWhenFinished() {
        val aborted = AtomicBoolean(false)
        val last = AtomicLong(System.currentTimeMillis())
        val abortCalls = AtomicInteger(0)
        val code = IdleWatchdog.run(
            idleMs = 5_000,
            lastTokenAt = last,
            aborted = aborted,
            onAbort = { abortCalls.incrementAndGet() },
            pollMs = 40,
            abortJoinMs = 200,
        ) { 12 }
        assertEquals(12, code)
        assertEquals(0, abortCalls.get())
        assertEquals(false, aborted.get())
    }

    @Test
    fun idleTimeoutAbortsAndMapsCancelledToTimeout() {
        val aborted = AtomicBoolean(false)
        val last = AtomicLong(System.currentTimeMillis())
        val abortCalls = AtomicInteger(0)
        val started = CountDownLatch(1)
        val code = IdleWatchdog.run(
            idleMs = 80,
            lastTokenAt = last,
            aborted = aborted,
            onAbort = { abortCalls.incrementAndGet() },
            pollMs = 30,
            abortJoinMs = 2_000,
        ) {
            started.countDown()
            val until = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < until && !aborted.get()) {
                Thread.sleep(20)
            }
            IdleWatchdog.CANCELLED
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(IdleWatchdog.TIMEOUT, code)
        assertTrue(abortCalls.get() >= 1)
        assertTrue(aborted.get())
    }

    @Test
    fun tokenActivityExtendsIdleWindow() {
        val aborted = AtomicBoolean(false)
        val last = AtomicLong(System.currentTimeMillis())
        val abortCalls = AtomicInteger(0)
        val code = IdleWatchdog.run(
            idleMs = 180,
            lastTokenAt = last,
            aborted = aborted,
            onAbort = { abortCalls.incrementAndGet() },
            pollMs = 40,
            abortJoinMs = 200,
        ) {
            repeat(6) {
                Thread.sleep(50)
                last.set(System.currentTimeMillis())
            }
            7
        }
        assertEquals(7, code)
        assertEquals(0, abortCalls.get())
    }
}
