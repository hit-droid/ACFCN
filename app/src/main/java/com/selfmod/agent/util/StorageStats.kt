package com.selfmod.agent.util

import android.content.Context
import java.io.File

data class DirUsage(val label: String, val bytes: Long)

/** Reports and clears the app's on-device storage usage. */
class StorageStats(private val context: Context) {

    fun usage(): List<DirUsage> {
        val files = context.filesDir
        return listOf(
            DirUsage("模型副本", sizeOf(File(files, "models"))),
            DirUsage("脚本", sizeOf(File(files, "scripts"))),
            DirUsage("脚本版本", sizeOf(File(files, "versions"))),
            DirUsage("插件", sizeOf(File(files, "plugins"))),
            DirUsage("插件缓存", sizeOf(File(files, "odex"))),
            DirUsage("缓存", sizeOf(context.cacheDir)),
        )
    }

    fun total(): Long = usage().sumOf { it.bytes }

    fun freeDisk(): Long {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return stat.availableBytes
    }

    /** @return bytes freed. */
    fun clearCache(): Long {
        val before = sizeOf(context.cacheDir)
        context.cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        return before
    }

    /** @return bytes freed. */
    fun clearModelCopies(): Long {
        val dir = File(context.filesDir, "models")
        val before = sizeOf(dir)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return before
    }

    /** @return bytes freed. */
    fun clearScriptVersions(): Long {
        val dir = File(context.filesDir, "versions")
        val before = sizeOf(dir)
        dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        return before
    }

    private fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        fun human(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
