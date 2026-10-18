package com.selfmod.agent.store

import com.selfmod.agent.llm.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a conversation as Markdown for the system share sheet.
 *
 * The trace the user sees on screen (思考/行动/观察 rows) is UI state, not
 * history, so it is not available here — this exports the underlying
 * [ChatMessage] list: user turns, assistant turns, and tool results folded
 * into collapsible-ish quote blocks so the export stays readable.
 */
object SessionExporter {

    private const val MAX_TOOL_BLOCK = 600

    fun toMarkdown(title: String, messages: List<ChatMessage>, exportedAt: Long): String {
        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        sb.appendLine("> 导出自 ACFCN · ${formatTime(exportedAt)}")
        sb.appendLine()
        var wroteAny = false
        messages.forEach { m ->
            if (m.role == "system") return@forEach
            wroteAny = true
            when (m.role) {
                "user" -> {
                    sb.appendLine("## 你")
                    sb.appendLine()
                    sb.appendLine(m.content.ifBlank { "（空）" })
                    sb.appendLine()
                }
                "assistant" -> {
                    sb.appendLine("## ACFCN")
                    sb.appendLine()
                    if (m.content.isNotBlank()) {
                        sb.appendLine(m.content)
                        sb.appendLine()
                    }
                    m.toolCalls.forEach { tc ->
                        sb.appendLine("> ⚙️ 调用工具 `${tc.function.name}`")
                    }
                    if (m.toolCalls.isNotEmpty()) sb.appendLine()
                }
                "tool" -> {
                    // Tool results are for debugging, not reading — keep them short.
                    val body = m.content.replace("\n", " ").take(MAX_TOOL_BLOCK)
                    sb.appendLine("> 👁 ${m.name ?: "工具"}返回：$body")
                    sb.appendLine()
                }
            }
        }
        if (!wroteAny) {
            sb.appendLine("（这个会话还没有消息）")
            sb.appendLine()
        }
        return sb.toString()
    }

    /** Filename-safe stem for the exported file/share subject. */
    fun fileStem(title: String, exportedAt: Long): String {
        val safe = title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(40)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(exportedAt))
        return if (safe.isBlank()) "acfcn-session-$stamp" else "acfcn-$safe-$stamp"
    }

    // L6 lesson: SimpleDateFormat is not thread-safe — never share an instance.
    private fun formatTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
