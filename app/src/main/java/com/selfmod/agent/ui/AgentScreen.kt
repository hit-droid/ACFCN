package com.selfmod.agent.ui

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    val sessions by vm.sessionList.collectAsState()
    var showSessions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.uiEventsFlow.collect { e -> vm.ingestUiEvent(e) } }
    LaunchedEffect(trace.size) {
        if (trace.isNotEmpty()) listState.animateScrollToItem(trace.lastIndex)
    }

    val offline by vm.offlineMode.collectAsState()
    val cfg = vm.config()
    val activeTitle = sessions.firstOrNull { it.id == vm.activeSessionId() }?.title ?: "新会话"
    val endpointHint = when {
        offline || cfg.isLocalHost() -> "$activeTitle · 离线 · ${cfg.model.ifEmpty { "(未配置)" }}"
        else -> "$activeTitle · 云端 · ${cfg.model.ifEmpty { "(未配置)" }}"
    }

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(
            hint = endpointHint,
            busy = busy,
            onClear = { vm.reset() },
            onRetry = { vm.retryLast() },
            onOpenSessions = { showSessions = true },
        )

        if (trace.isEmpty()) {
            EmptyChat(enabled = !busy, onPick = { vm.sendPrompt(it) })
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

    if (showSessions) {
        SessionDrawer(
            sessions = sessions,
            activeId = vm.activeSessionId(),
            onDismiss = { showSessions = false },
            onSwitch = { id ->
                vm.switchSession(id)
                showSessions = false
            },
            onNew = {
                vm.newSession()
                showSessions = false
            },
            onDelete = { id -> vm.deleteSession(id) },
            onExport = { context ->
                val export = vm.exportCurrentSession()
                if (export == null) {
                    Toast.makeText(context, "当前会话没有消息，无需导出", Toast.LENGTH_SHORT).show()
                } else {
                    val (stem, markdown) = export
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, stem)
                        putExtra(Intent.EXTRA_TEXT, markdown)
                    }
                    context.startActivity(Intent.createChooser(intent, "导出会话"))
                }
            },
        )
    }
}

/**
 * Bottom sheet listing stored sessions (newest first). Rows are tap-to-switch
 * with an inline two-step delete — a stray tap on the trash icon must not
 * silently drop a conversation.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SessionDrawer(
    sessions: List<com.selfmod.agent.store.SessionSummary>,
    activeId: String,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onExport: (android.content.Context) -> Unit,
) {
    val context = LocalContext.current
    val timeFmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceDark) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("会话", color = TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onExport(context) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("导出当前会话", color = AccentBlue, fontSize = 13.sp)
                }
                TextButton(onClick = onNew) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("新建", color = AccentBlue, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (sessions.isEmpty()) {
                Text(
                    "还没有历史会话。开始一段对话后，它会出现在这里。",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sessions, key = { it.id }) { s ->
                        val isActive = s.id == activeId
                        val confirming = confirmDeleteId == s.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isActive) AccentBlue.copy(alpha = 0.15f) else SurfaceVariant.copy(alpha = 0.5f))
                                .clickable { onSwitch(s.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.title,
                                    color = if (isActive) AccentBlue else TextPrimary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${timeFmt.format(java.util.Date(s.updatedAt))} · ${s.messageCount} 条消息",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                            if (confirming) {
                                Text(
                                    "确认删除",
                                    color = Danger,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            onDelete(s.id)
                                            confirmDeleteId = null
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "删除会话",
                                    tint = TextSecondary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .clickable { confirmDeleteId = s.id }
                                        .padding(2.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChatTopBar(
    hint: String,
    busy: Boolean,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onOpenSessions: () -> Unit,
) {
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
                TextButton(onClick = onOpenSessions) {
                    Icon(Icons.Filled.History, contentDescription = "历史会话", modifier = Modifier.size(18.dp))
                }
            }
            TextButton(onClick = onClear) {
                Icon(Icons.Filled.Clear, contentDescription = "清空当前会话", modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.material3.HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)
    }
}

@Composable
private fun EmptyChat(enabled: Boolean, onPick: (String) -> Unit) {
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
            "让我改脚本、装插件、操作内置浏览器，或离线跑本机模型。\n点下面的例子直接开始：",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        SAMPLE_PROMPTS.forEach { s ->
            Text(
                s,
                color = AccentBlue,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceVariant)
                    .clickable(enabled = enabled) { onPick(s) }
                    .padding(12.dp),
            )
        }
    }
}

private val SAMPLE_PROMPTS = listOf(
    "打开 example.com 并读出标题",
    "写一个 hello.js，打印斐波那契前 10 项",
    "帮我看看当前页面有哪些可点的元素",
)


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
