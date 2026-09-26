package com.selfmod.agent.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic log so users can capture what happened without adb.
 * Bounded to the last N lines; readable/copyable from the Offline screen.
 */
object Diagnostics {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())}  [$tag]  $msg"
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
