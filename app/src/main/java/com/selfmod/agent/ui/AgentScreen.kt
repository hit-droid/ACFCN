package com.selfmod.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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

    // Forward tool/UI events into the trace so the user sees agent side effects.
    LaunchedEffect(Unit) {
        vm.uiEventsFlow.collect { e -> vm.ingestUiEvent(e) }
    }
    LaunchedEffect(trace.size) {
        if (trace.isNotEmpty()) listState.animateScrollToItem(trace.lastIndex)
    }

    val cfg = remember { vm.config() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "SelfMod Agent",
                    color = TextPrimary,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                Text(
                    "模型: ${cfg.model.ifEmpty { "(未配置)" }}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = { vm.reset() }) {
                Icon(Icons.Filled.Clear, contentDescription = "清空")
                Spacer(Modifier.width(4.dp))
                Text("清空")
            }
        }
        androidx.compose.material3.HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(trace) { entry -> TraceCard(entry) }
            if (busy) item { ThinkingIndicator() }
        }

        Row(
            Modifier.fillMaxWidth().background(SurfaceDark).padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { vm.onInputTextChange(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("让智能体做点什么…", color = TextSecondary) },
                maxLines = 5,
                enabled = !busy,
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { vm.send() },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun TraceCard(e: TraceEntry) {
    val bg = when (e.kind) {
        "user" -> SurfaceVariant
        "answer" -> AccentBlue.copy(alpha = 0.18f)
        "error" -> Danger.copy(alpha = 0.18f)
        "thought" -> SurfaceVariant
        "action" -> AccentGreen.copy(alpha = 0.16f)
        "observation" -> AccentAmber.copy(alpha = 0.12f)
        "ui" -> AccentPurple.copy(alpha = 0.16f)
        else -> SurfaceVariant
    }
    val onColor = when (e.kind) {
        "answer" -> AccentBlue
        "error" -> Danger
        "action" -> AccentGreen
        "observation" -> AccentAmber
        "ui" -> AccentPurple
        else -> TextSecondary
    }
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text(e.title, color = onColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        if (e.body.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(e.body, color = TextPrimary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentBlue)
        Spacer(Modifier.width(8.dp))
        Text("思考中…", color = TextSecondary, fontSize = 13.sp)
    }
}
