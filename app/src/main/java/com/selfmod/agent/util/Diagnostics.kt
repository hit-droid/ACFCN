package com.selfmod.agent.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * In-app diagnostic log so users can capture what happened without adb.
 * Bounded to the last N lines; readable/copyable from the Offline screen.
 */
object Diagnostics {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    // DateTimeFormatter 是线程安全的（SimpleDateFormat 不是）；log() 会被 native
    // 回调线程与 IO 协程并发调用，这里必须安全。
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lock = Any()

    fun log(tag: String, msg: String) {
        val line = "${LocalTime.now().format(fmt)}  [$tag]  $msg"
        android.util.Log.i("ACFCN-DIAG", line)
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun dump(): String = synchronized(lock) {
        if (lines.isEmpty()) "（暂无日志）" else lines.joinToString("\n")
    }

    fun clear() = synchronized(lock) { lines.clear() }
}
