package com.selfmod.agent.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persists the most recent uncaught crash so the next launch can show it. */
class CrashStore(context: Context) {
    private val file = File(context.filesDir, "last_crash.txt")

    fun save(throwable: Throwable) {
        runCatching {
            val body = buildString {
                appendLine("ACFCN crash report")
                appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine()
                appendLine(throwable.stackTraceToString())
            }
            file.writeText(body)
        }
    }

    fun read(): String? = if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    fun clear() {
        runCatching { file.delete() }
    }
}
