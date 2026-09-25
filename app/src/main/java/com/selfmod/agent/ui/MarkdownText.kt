package com.selfmod.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val index: String, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
}

private object MarkdownParser {
    private val fence = Regex("^```\\s*([A-Za-z0-9_+\\-]*)")

    fun parse(md: String): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0
        val para = StringBuilder()

        fun flushPara() {
            if (para.isNotBlank()) blocks += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            val fenceMatch = fence.find(line.trim())
            if (fenceMatch != null) {
                flushPara()
                val lang = fenceMatch.groupValues[1]
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                i++
                continue
            }
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flushPara()
                trimmed.startsWith("### ") -> { flushPara(); blocks += MdBlock.Heading(3, trimmed.drop(4)) }
                trimmed.startsWith("## ") -> { flushPara(); blocks += MdBlock.Heading(2, trimmed.drop(3)) }
                trimmed.startsWith("# ") -> { flushPara(); blocks += MdBlock.Heading(1, trimmed.drop(2)) }
                trimmed.startsWith("> ") -> { flushPara(); blocks += MdBlock.Quote(trimmed.drop(2)) }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                    flushPara(); blocks += MdBlock.Bullet(trimmed.drop(2))
                }
                Regex("^\\d+[.)]\\s+.*").containsMatchIn(trimmed) -> {
                    flushPara()
                    val idx = trimmed.takeWhile { it.isDigit() }
                    blocks += MdBlock.Numbered(idx, trimmed.drop(idx.length).trimStart('.', ')', ' '))
                }
                else -> para.append(line).append('\n')
            }
            i++
        }
        flushPara()
        return blocks
    }

    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = text.length
        while (i < n) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = SurfaceVariant, color = AccentBlue)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                text.startsWith("*", i) || text.startsWith("_", i) -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier, baseColor: Color = TextPrimary) {
    val blocks = remember(md) { MarkdownParser.parse(md) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    MarkdownParser.inline(block.text),
                    color = baseColor,
                    fontSize = when (block.level) { 1 -> 20.sp; 2 -> 17.sp; else -> 15.sp },
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Paragraph -> Text(
                    MarkdownParser.inline(block.text),
                    color = baseColor,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text("•", color = AccentBlue, fontSize = 15.sp, modifier = Modifier.width(16.dp))
                    Text(MarkdownParser.inline(block.text), color = baseColor, fontSize = 15.sp, lineHeight = 21.sp)
                }
                is MdBlock.Numbered -> Row(Modifier.fillMaxWidth()) {
                    Text("${block.index}.", color = AccentBlue, fontSize = 15.sp, modifier = Modifier.width(22.dp))
                    Text(MarkdownParser.inline(block.text), color = baseColor, fontSize = 15.sp, lineHeight = 21.sp)
                }
                is MdBlock.Quote -> Row(
                    Modifier.fillMaxWidth().background(SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                ) {
                    Box(Modifier.width(3.dp).padding(vertical = 4.dp).background(AccentBlue))
                    Text(
                        MarkdownParser.inline(block.text),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                is MdBlock.Code -> CodeBlock(block.lang, block.code)
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF0B0F14), RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lang.ifBlank { "code" },
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制", tint = TextSecondary)
            }
        }
        Text(
            code,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}
