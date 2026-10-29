package com.selfmod.agent.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.offline.LocalModel
import com.selfmod.agent.offline.RecommendedModels
import com.selfmod.agent.ui.theme.AccentAmber
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.AccentGreen
import com.selfmod.agent.ui.theme.Danger
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary

/**
 * Offline tab.
 *
 * Layout order follows what the user does, not how the data is modelled:
 * state first (offline switch, engine, endpoint), then the primary action
 * (load a local model), then the secondary path (external service on the LAN),
 * then reference material (recommended models, usage, diagnostics) collapsed.
 */
@Composable
fun OfflineScreen(vm: AgentViewModel) {
    val models by vm.localModels.collectAsState()
    LaunchedEffect(Unit) { vm.refreshLocalModels() }
    val offline by vm.offlineMode.collectAsState()
    val probe by vm.probeHits.collectAsState()
    val probing by vm.probing.collectAsState()
    val test by vm.testResult.collectAsState()
    val testing by vm.testing.collectAsState()
    val engineStatus by vm.engineStatus.collectAsState()
    val engineBusy by vm.engineBusy.collectAsState()
    val engineReady by vm.engineReady.collectAsState()
    val engineProgress by vm.engineProgress.collectAsState()
    val selfTest by vm.selfTest.collectAsState()
    val selfTesting by vm.selfTesting.collectAsState()
    val diagnostics by vm.diagnostics.collectAsState()
    val cfg = vm.config()
    var note by remember { mutableStateOf<String?>(null) }
    var showRecommended by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showUsage by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { vm.importModel(uri) }
                .onSuccess { m -> note = "已导入 ${m.name}（${m.format} · ${m.sizeLabel()}）" }
                .onFailure { note = "导入失败：${it.message}" }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("离线", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Caption("本机模型、局域网推理服务与端侧引擎")
        }

        // ---- 状态 ----------------------------------------------------------
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("离线模式", color = TextPrimary, fontSize = 14.sp)
                    Caption("优先走本机端点，提示词切到 ReAct 工具格式")
                }
                Switch(checked = offline, onCheckedChange = { vm.setOfflineMode(it) })
            }

            ThinDivider(Modifier.padding(vertical = 4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).background(
                        if (engineReady) AccentGreen else TextSecondary,
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (engineReady) "端侧模型已加载" else "端侧模型未加载",
                    color = if (engineReady) AccentGreen else TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.runEngineSelfTest() }, enabled = engineReady && !selfTesting) {
                    Text(if (selfTesting) "自检中" else "自检", fontSize = 12.sp)
                }
                TextButton(onClick = { vm.unloadOnDevice() }, enabled = engineReady && !engineBusy) {
                    Text("卸载", fontSize = 12.sp)
                }
            }

            if (engineStatus.isNotBlank()) {
                Caption(engineStatus, color = if (engineReady) AccentGreen else AccentAmber)
            }
            if (engineProgress >= 0f) {
                LinearProgressIndicator(
                    progress = { engineProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentBlue,
                )
            }

            ThinDivider(Modifier.padding(vertical = 4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Caption("当前端点", color = AccentBlue)
                    Text(
                        cfg.profileName.ifBlank { cfg.kind },
                        color = TextPrimary,
                        fontSize = 14.sp,
                    )
                    Caption("${cfg.baseUrl} · ${cfg.model}")
                }
                Text(
                    if (cfg.isLocalHost()) "本机" else "云端",
                    color = if (cfg.isLocalHost()) AccentGreen else AccentAmber,
                    fontSize = 12.sp,
                )
            }
        }

        // ---- 已导入模型（主操作）-------------------------------------------
        SectionHeader("已导入模型") {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text("导入", fontSize = 12.sp)
            }
        }
        if (models.isEmpty()) {
            Caption("还没有模型。点右上角「导入」，选择手机里的 .gguf 文件或 MNN 模型 zip。")
        } else {
            models.forEach { m: LocalModel ->
                ModelRow(
                    model = m,
                    busy = engineBusy,
                    onLoad = { vm.loadOnDevice(m) },
                    onUseAsService = {
                        vm.applyOfflineModel(m)
                        note = "已把模型名设为 ${m.name}，请确认本机服务已加载该文件"
                    },
                    onRemove = { vm.removeModel(m.id) },
                )
            }
        }

        if (selfTest.isNotBlank()) {
            InfoCard {
                Text("自检结果", color = AccentBlue, fontSize = 12.sp)
                Text(
                    selfTest,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // ---- 局域网推理服务 ------------------------------------------------
        SectionHeader("局域网推理服务")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.probeLocal() }, enabled = !probing) {
                Text(if (probing) "扫描中…" else "扫描端口", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { vm.testConnection() }, enabled = !testing) {
                Text(if (testing) "测试中…" else "测试端点", fontSize = 13.sp)
            }
        }
        Caption("Ollama 11434 · LM Studio 1234 · llama.cpp 8080 · Jan 1337")

        probe.forEach { hit ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(hit.name, color = TextPrimary, fontSize = 13.sp)
                        Caption("${hit.baseUrl} · ${hit.latencyMs}ms")
                        if (hit.models.isNotEmpty()) {
                            Caption(hit.models.take(4).joinToString(), color = AccentGreen)
                        }
                    }
                    OutlinedButton(onClick = {
                        vm.applyLocalEndpoint(hit.name, hit.baseUrl, hit.models.firstOrNull().orEmpty())
                        note = "已切换到 ${hit.name}"
                    }) { Text("接入", fontSize = 12.sp) }
                }
            }
        }
        if (probe.isEmpty() && !probing) {
            Caption("尚未扫描，或本机没有监听的推理服务。")
        }
        test?.let { r ->
            Text(
                r.message + if (r.sample.isNotBlank()) " · 回复：${r.sample.take(80)}" else "",
                color = if (r.ok) AccentGreen else Danger,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }

        // ---- 推荐模型（默认折叠）-------------------------------------------
        SectionHeader("推荐模型") {
            TextButton(onClick = { showRecommended = !showRecommended }) {
                Text(if (showRecommended) "收起" else "${RecommendedModels.ALL.size} 个", fontSize = 12.sp)
            }
        }
        if (showRecommended) {
            Caption("先下载 .gguf 到手机，再回到上面「导入」。建议从 1.5B / Q4_K_M 起步。")
            RecommendedModels.ALL.forEach { rec ->
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rec.name,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Caption("${rec.quant} · ${rec.sizeLabel}", color = AccentBlue)
                    }
                    Caption("${rec.ramHint} · ${rec.note}")
                    TextButton(
                        onClick = { vm.openInBrowser(rec.url) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text("打开下载页", fontSize = 12.sp) }
                }
            }
        }

        // ---- 诊断日志（默认折叠）-------------------------------------------
        SectionHeader("诊断日志") {
            TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                Text(if (showDiagnostics) "收起" else "查看", fontSize = 12.sp)
            }
        }
        if (showDiagnostics) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(diagnostics)) }) {
                    Text("复制全部", fontSize = 12.sp)
                }
                TextButton(onClick = { vm.refreshDiagnostics() }) {
                    Text("刷新", fontSize = 12.sp)
                }
                TextButton(onClick = { vm.clearDiagnostics() }) {
                    Text("清空", fontSize = 12.sp)
                }
            }
            if (diagnostics.isNotBlank() && diagnostics != "（暂无日志）") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(SurfaceVariant, RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        diagnostics,
                        color = TextPrimary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            } else {
                Caption("还没有日志。加载模型后点「刷新」，把这里的内容复制出来即可定位问题。")
            }
        }

        // ---- 用法（默认折叠）-----------------------------------------------
        SectionHeader("怎么用") {
            TextButton(onClick = { showUsage = !showUsage }) {
                Text(if (showUsage) "收起" else "展开", fontSize = 12.sp)
            }
        }
        AnimatedVisibility(visible = showUsage) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageLine("1", "导入模型", "把 .gguf 或 MNN zip 放到手机，点「导入」选择文件。")
                UsageLine("2", "加载到本机", "点模型卡片里的「加载」，等进度条走完，状态变成已加载。")
                UsageLine("3", "直接对话", "回到「智能体」页提问，推理全部在本机完成，断网可用。")
                UsageLine("4", "或接外部服务", "本机跑 Ollama / llama.cpp 时，用「扫描端口」自动接入。")
            }
        }

        note?.let { msg ->
            InfoCard {
                Text(msg, color = AccentGreen, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ModelRow(
    model: LocalModel,
    busy: Boolean,
    onLoad: () -> Unit,
    onUseAsService: () -> Unit,
    onRemove: () -> Unit,
) {
    InfoCard {
        Text(model.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Caption("${model.format.uppercase()} · ${model.sizeLabel()}")
        if (model.summary.isNotBlank()) {
            Caption(model.summary, color = AccentBlue)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = onLoad, enabled = !busy) {
                Text(if (busy) "加载中…" else "加载", fontSize = 13.sp)
            }
            TextButton(onClick = onUseAsService) {
                Text("设为服务模型", fontSize = 12.sp)
            }
            TextButton(onClick = onRemove) {
                Text("移除", fontSize = 12.sp, color = Danger)
            }
        }
    }
}

@Composable
private fun UsageLine(index: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            index,
            color = AccentBlue,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(14.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextPrimary, fontSize = 13.sp)
            Caption(body)
        }
    }
}
