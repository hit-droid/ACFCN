package com.selfmod.agent.offline.native

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal object IdleWatchdog {
    const val CANCELLED = -98
    const val TIMEOUT = -99
    const val WORKER_DIED = -97

    fun run(
        idleMs: Long,
        lastTokenAt: AtomicLong,
        aborted: AtomicBoolean,
        onAbort: () -> Unit,
        pollMs: Long = 2_000,
        abortJoinMs: Long = 15_000,
        block: () -> Int,
    ): Int {
        val result = AtomicInteger(Int.MIN_VALUE)
        val worker = Thread {
            runCatching { result.set(block()) }
        }
        worker.isDaemon = true
        worker.start()
        val hardCap = (idleMs * 8).coerceAtMost(10 * 60_000L)
        val started = System.currentTimeMillis()
        val poll = pollMs.coerceAtLeast(20)
        while (worker.isAlive) {
            worker.join(poll)
            if (!worker.isAlive) break
            val idle = System.currentTimeMillis() - lastTokenAt.get()
            val elapsed = System.currentTimeMillis() - started
            if (idle >= idleMs || elapsed >= hardCap) {
                aborted.set(true)
                runCatching { onAbort() }
                com.selfmod.agent.util.Diagnostics.log(
                    "engine",
                    "chat: 空闲超时 idle=${idle}ms elapsed=${elapsed}ms，已发 native 取消",
                )
                worker.join(abortJoinMs.coerceAtLeast(0))
                val code = result.get()
                if (code == Int.MIN_VALUE) return TIMEOUT
                return if (code == CANCELLED) TIMEOUT else code
            }
        }
        val code = result.get()
        if (code == Int.MIN_VALUE) return WORKER_DIED
        return code
    }
}
