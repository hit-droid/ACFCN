package com.selfmod.agent.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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

@Composable
fun OfflineScreen(vm: AgentViewModel) {
    var models by remember { mutableStateOf(vm.localModels()) }
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
    val clipboard = LocalClipboardManager.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { vm.importModel(uri) }
                .onSuccess { m ->
                    models = vm.localModels()
                    note = "已导入 ${m.name} (${m.format} · ${m.sizeLabel()})"
                }
                .onFailure { note = "导入失败：${it.message}" }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("离线智能体", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            "导入本机 GGUF / ONNX 权重，配合 Ollama、llama.cpp 或 LM Studio 的 OpenAI 兼容接口即可完全离线调用工具。",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Text(
            "用法：1) 用 Ollama `ollama create` 或 llama.cpp `-m` 加载导入的 GGUF；2) 扫描并接入本机端口；3) 打开离线模式。",
            color = TextSecondary,
            fontSize = 12.sp,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("离线模式", color = TextPrimary)
                Text("优先走本机端点，提示词切到 ReAct 工具格式", color = TextSecondary, fontSize = 12.sp)
            }
            Switch(checked = offline, onCheckedChange = { vm.setOfflineMode(it) })
        }

        Column(
            Modifier.fillMaxWidth().background(SurfaceVariant, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("当前端点", color = AccentBlue, fontSize = 13.sp)
            Text(cfg.profileName.ifBlank { cfg.kind }, color = TextPrimary, fontSize = 14.sp)
            Text("${cfg.baseUrl}  ·  ${cfg.model}", color = TextSecondary, fontSize = 12.sp)
            Text(
                if (cfg.isLocalHost()) "本机/局域网 · 无需外网" else "云端 · 需要网络与 Key",
                color = if (cfg.isLocalHost()) AccentGreen else AccentAmber,
                fontSize = 12.sp,
            )
        }

        Text("本机推理服务", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        Text("扫描常见端口：Ollama 11434、LM Studio 1234、llama.cpp 8080、Jan 1337。", color = TextSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.probeLocal() }, enabled = !probing) {
                Text(if (probing) "扫描中…" else "扫描本机端口")
            }
            OutlinedButton(onClick = { vm.testConnection() }, enabled = !testing) {
                Text(if (testing) "测试中…" else "测试当前 API")
            }
        }
        probe.forEach { hit ->
            Row(
                Modifier.fillMaxWidth().background(SurfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(hit.name, color = TextPrimary, fontSize = 13.sp)
                    Text("${hit.baseUrl}  ·  ${hit.latencyMs}ms", color = TextSecondary, fontSize = 11.sp)
                    if (hit.models.isNotEmpty()) {
                        Text(hit.models.take(4).joinToString(), color = AccentGreen, fontSize = 11.sp)
                    }
                }
                OutlinedButton(onClick = {
                    vm.applyLocalEndpoint(hit.name, hit.baseUrl, hit.models.firstOrNull().orEmpty())
                    note = "已切换到 ${hit.name}"
                }) { Text("接入") }
            }
        }
        if (probe.isEmpty() && !probing) {
            Text("尚未扫描，或本机没有监听的推理服务。", color = TextSecondary, fontSize = 12.sp)
        }
        test?.let { r ->
            Text(
                r.message + if (r.sample.isNotBlank()) "  回复: ${r.sample.take(80)}" else "",
                color = if (r.ok) AccentGreen else Danger,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text("推荐模型（手机可跑）", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        Text(
            "先到页面下载 .gguf 文件到手机，再回到这里导入。建议从 1.5B / Q4_K_M 起步。",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        RecommendedModels.ALL.forEach { rec ->
            Column(
                Modifier.fillMaxWidth().background(SurfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rec.name, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${rec.quant} · ${rec.sizeLabel}", color = AccentBlue, fontSize = 11.sp)
                }
                Text("${rec.ramHint} · ${rec.note}", color = TextSecondary, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.openInBrowser(rec.url) }) { Text("打开下载页") }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("端侧推理（App 内直接跑）", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        Text(
            "把 GGUF 复制到 App 私有目录并用 llama.cpp 直接加载，无需任何外部服务。需 arm64 设备与足够内存。",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (engineReady) "● 端侧模型已加载" else "○ 未加载端侧模型",
                color = if (engineReady) AccentGreen else TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { vm.runEngineSelfTest() }, enabled = engineReady && !selfTesting) {
                Text(if (selfTesting) "自检中…" else "自检")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.unloadOnDevice() }, enabled = engineReady && !engineBusy) {
                Text("卸载")
            }
        }
        if (engineStatus.isNotBlank()) {
            Text(engineStatus, color = if (engineReady) AccentGreen else AccentAmber, fontSize = 12.sp)
        }
        if (engineProgress >= 0f) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { engineProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = AccentBlue,
            )
        }
        if (selfTest.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth().background(SurfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
            ) {
                Text("端侧自检结果", color = AccentBlue, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(selfTest, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("诊断日志", color = TextPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(diagnostics)) }) { Text("复制全部", fontSize = 12.sp) }
            TextButton(onClick = { vm.refreshDiagnostics() }) { Text("刷新", fontSize = 12.sp) }
            TextButton(onClick = { vm.clearDiagnostics() }) { Text("清空", fontSize = 12.sp) }
        }
        if (diagnostics.isNotBlank() && diagnostics != "（暂无日志）") {
            Column(
                Modifier.fillMaxWidth().height(180.dp).background(SurfaceVariant, RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState()).padding(10.dp),
            ) {
                Text(diagnostics, color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            Text(
                "点「刷新」查看最近日志。加载模型后把这里的内容复制发我，就能定位问题。",
                color = TextSecondary, fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text("已导入模型", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        Button(onClick = {
            picker.launch(arrayOf("*/*"))
        }) { Text("从文件导入 GGUF / ONNX") }

        if (models.isEmpty()) {
            Text("还没有导入模型。把 .gguf 拷到手机后点上方按钮选择即可。", color = TextSecondary, fontSize = 12.sp)
        } else {
            models.forEach { m: LocalModel ->
                Column(
                    Modifier.fillMaxWidth().background(SurfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(m.name, color = TextPrimary, fontSize = 14.sp)
                    Text("${m.format.uppercase()} · ${m.sizeLabel()}", color = TextSecondary, fontSize = 12.sp)
                    if (m.summary.isNotBlank()) {
                        Text(m.summary, color = AccentBlue, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.loadOnDevice(m) },
                            enabled = !engineBusy,
                        ) { Text(if (engineBusy) "加载中…" else "在本机加载") }
                        OutlinedButton(onClick = {
                            vm.applyOfflineModel(m)
                            note = "已把模型名设为 ${m.name}，请确保本机服务已加载该文件"
                        }) { Text("用作外部服务模型") }
                        OutlinedButton(onClick = {
                            vm.removeModel(m.id)
                            models = vm.localModels()
                        }) { Text("移除") }
                    }
                }
            }
        }

        note?.let { Text(it, color = AccentGreen, fontSize = 12.sp) }

        Text(
            "用法：1) 用 Ollama `ollama create` 或 llama.cpp `-m` 加载导入的 GGUF；2) 扫描并接入本机端口；3) 打开离线模式。智能体即可在无外网时调用脚本、插件和内置浏览器。",
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}
