package com.selfmod.agent.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the most recent uncaught crash so the next launch can show it.
 * Writes atomically with fsync, then falls back to cache dirs if filesDir fails.
 */
class CrashStore(
    private val primaryDir: File,
    private val fallbackDirs: List<File> = emptyList(),
    private val deviceInfo: () -> String = Companion::defaultDeviceInfo,
    private val clock: () -> Date = { Date() },
    private val logger: (String, Throwable?) -> Unit = { msg, t -> android.util.Log.e(TAG, msg, t) },
) {
    constructor(context: Context) : this(
        primaryDir = context.filesDir,
        fallbackDirs = listOfNotNull(context.cacheDir, context.externalCacheDir),
    )

    fun save(throwable: Throwable): Boolean {
        val body = buildReport(throwable)
        logger("persisting crash report\n$body", throwable)
        var lastError: Throwable? = null
        for (dir in candidateDirs()) {
            try {
                writeAtomically(dir, body)
                return true
            } catch (t: Throwable) {
                lastError = t
                logger("crash report write failed in ${dir.absolutePath}", t)
            }
        }
        logger("crash report lost: all ${candidateDirs().size} locations failed", lastError)
        return false
    }

    fun read(): String? {
        for (dir in candidateDirs()) {
            val file = File(dir, FILE_NAME)
            if (!file.isFile) continue
            val text = runCatching { file.readText() }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    fun clear() {
        for (dir in candidateDirs()) {
            runCatching { File(dir, FILE_NAME).delete() }
            runCatching { File(dir, TMP_NAME).delete() }
        }
    }

    private fun candidateDirs(): List<File> = listOf(primaryDir) + fallbackDirs

    private fun buildReport(throwable: Throwable): String = buildString {
        appendLine("ACFCN crash report")
        appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(clock())}")
        appendLine(deviceInfo())
        appendLine()
        // L15: exception messages can carry auth headers or provider key
        // echoes; the report is user-shareable, so mask secrets at the source.
        appendLine(SecretRedactor.redact(throwable.stackTraceToString()))
    }

    private fun writeAtomically(dir: File, body: String) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("cannot create crash dir ${dir.absolutePath}")
        }
        if (!dir.isDirectory || !dir.canWrite()) {
            throw IOException("crash dir not writable ${dir.absolutePath}")
        }
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, TMP_NAME)
        FileOutputStream(tmp).use { fos ->
            fos.write(body.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            runCatching { tmp.delete() }
            throw IOException("cannot replace ${target.absolutePath}")
        }
        if (!tmp.renameTo(target)) {
            try {
                tmp.copyTo(target, overwrite = true)
                FileOutputStream(target, true).use { fos ->
                    fos.fd.sync()
                }
            } finally {
                tmp.delete()
            }
        }
    }

    companion object {
        private const val TAG = "ACFCN-CRASH"
        internal const val FILE_NAME = "last_crash.txt"
        internal const val TMP_NAME = "last_crash.txt.tmp"

        private fun defaultDeviceInfo(): String = buildString {
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }
}
