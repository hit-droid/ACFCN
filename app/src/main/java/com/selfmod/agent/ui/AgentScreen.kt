package com.selfmod.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.ui.theme.AccentAmber
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.AccentGreen
import com.selfmod.agent.ui.theme.AccentPurple
import com.selfmod.agent.ui.theme.Danger
import com.selfmod.agent.ui.theme.SurfaceDark
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary

@Composable
fun AgentScreen(vm: AgentViewModel) {
    val trace by vm.trace.collectAsState()
    val busy by vm.busy.collectAsState()
    val input by vm.inputText.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.uiEventsFlow.collect { e -> vm.ingestUiEvent(e) } }
    LaunchedEffect(trace.size) {
        if (trace.isNotEmpty()) listState.animateScrollToItem(trace.lastIndex)
    }

    val offline by vm.offlineMode.collectAsState()
    val cfg = vm.config()
    val endpointHint = when {
        offline || cfg.isLocalHost() -> "离线 · ${cfg.model.ifEmpty { "(未配置)" }}"
        else -> "云端 · ${cfg.model.ifEmpty { "(未配置)" }}"
    }

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(endpointHint, busy, onClear = { vm.reset() }, onRetry = { vm.retryLast() })

        if (trace.isEmpty()) {
            EmptyChat()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(trace, key = { it.id }) { entry ->
                    ChatMessageRow(entry)
                }
                if (busy && trace.lastOrNull()?.kind != "stream") {
                    item { TypingIndicator() }
                }
            }
        }

        if (trace.isEmpty()) Spacer(Modifier.weight(1f))

        ChatInputBar(
            input = input,
            busy = busy,
            onInputChange = { vm.onInputTextChange(it) },
            onSend = { vm.send() },
            onStop = { vm.stop() },
        )
    }
}

@Composable
private fun ChatTopBar(hint: String, busy: Boolean, onClear: () -> Unit, onRetry: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .background(AccentBlue.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("ACFCN", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text(hint, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
            if (!busy) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重试", modifier = Modifier.size(18.dp))
                }
            }
            TextButton(onClick = onClear) {
                Icon(Icons.Filled.Clear, contentDescription = "清空", modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.material3.HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)
    }
}

@Composable
private fun EmptyChat() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(AccentBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(32.dp))
        }
        Text("我能做什么？", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "让我改脚本、装插件、操作内置浏览器，或离线跑本机模型。\n试试下面的例子：",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        listOf(
            "打开 example.com 并读出标题",
            "写一个 hello.js，打印斐波那契前 10 项",
            "帮我看看当前页面有哪些可点的元素",
        ).forEach { s ->
            Text(
                s,
                color = AccentBlue,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            )
        }
    }
}


@Composable
private fun ChatMessageRow(e: TraceEntry) {
    val clipboard = LocalClipboardManager.current
    when (e.kind) {
        "user" -> UserBubble(e.body)
        "answer" -> AssistantBubble(e.body, accent = AccentBlue, onCopy = { clipboard.setText(AnnotatedString(e.body)) })
        "stream" -> AssistantBubble(e.body, accent = AccentBlue, streaming = true, onCopy = null)
        "thought" -> TraceRow("思考", e.body, AccentPurple, "💭")
        "action" -> TraceRow("行动 · ${e.title}", e.body, AccentGreen, "⚙️")
        "observation" -> TraceRow("观察", e.body, AccentAmber, "👁")
        "error" -> AssistantBubble(e.body, accent = Danger, onCopy = { clipboard.setText(AnnotatedString(e.body)) })
        "ui" -> TraceRow(e.title, e.body, AccentPurple, "🔔")
        else -> TraceRow(e.title.ifBlank { e.kind }, e.body, TextSecondary, "•")
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(AccentBlue)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}


@Composable
private fun AssistantBubble(
    text: String,
    accent: Color,
    streaming: Boolean = false,
    onCopy: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(SurfaceDark)
                .padding(14.dp),
        ) {
            Column {
                MarkdownText(if (text.isBlank() && streaming) "…" else text, baseColor = TextPrimary)
                if (onCopy != null && text.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "复制",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp).clickable { onCopy() },
                        )
                    }
                }
                if (streaming) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(24.dp).height(2.dp).background(accent.copy(alpha = 0.6f)))
                }
            }
        }
    }
}

@Composable
private fun TraceRow(title: String, body: String, color: Color, icon: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceVariant.copy(alpha = 0.5f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", color = TextSecondary, fontSize = 11.sp)
        }
        val preview = body.replace("\n", " ").take(80)
        Text(
            if (expanded) body else preview,
            color = TextSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TypingIndicator() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(SurfaceDark)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val transition = rememberInfiniteTransition(label = "typing")
            (0..2).forEach { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = i * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$i",
                )
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(7.dp)
                        .graphicsLayer { this.alpha = alpha }
                        .clip(CircleShape)
                        .background(AccentBlue),
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    busy: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("给 ACFCN 发消息…", color = TextSecondary) },
            maxLines = 5,
            enabled = !busy,
            shape = RoundedCornerShape(22.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() && !busy) onSend() }),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { if (busy) onStop() else onSend() },
            enabled = busy || input.isNotBlank(),
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                if (busy) Icons.Filled.Stop else Icons.Filled.Send,
                contentDescription = if (busy) "停止" else "发送",
            )
        }
    }
}
