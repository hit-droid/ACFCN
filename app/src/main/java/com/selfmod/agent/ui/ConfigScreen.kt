package com.selfmod.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.llm.LlmConfig
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.AccentGreen
import com.selfmod.agent.ui.theme.Danger
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConfigScreen(vm: AgentViewModel) {
    val initial = remember { vm.config() }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var tempStr by remember { mutableStateOf(initial.temperature.toString()) }
    var maxTokStr by remember { mutableStateOf(initial.maxTokens.toString()) }
    var kind by remember { mutableStateOf(initial.kind) }
    var nativeTools by remember { mutableStateOf(initial.supportsNativeTools) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf(initial.profileName.ifBlank { "我的配置" }) }
    var tick by remember { mutableStateOf(0) }
    val profiles = remember(tick) { vm.profiles() }
    val listed by vm.listedModels.collectAsState()
    val testing by vm.testing.collectAsState()
    val test by vm.testResult.collectAsState()
    val listing by vm.listingModels.collectAsState()

    // 以已存配置为基底再覆盖 UI 暴露的字段，避免把端侧模型路径、上下文、
    // 超时等未在 API 页展示的字段清空（例如点了「测试连接」把 onDeviceModelPath 抹掉）。
    fun currentCfg(): LlmConfig = vm.config().copy(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        temperature = tempStr.trim().toDoubleOrNull() ?: 0.6,
        maxTokens = maxTokStr.trim().toIntOrNull() ?: 2048,
        kind = kind,
        profileName = profileName.trim(),
        supportsNativeTools = nativeTools,
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("API 快速接入", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            "点预设填好 Base URL 和模型，粘贴 Key，一键测试。本机 Ollama / LM Studio / llama.cpp 不需要 Key。",
            color = TextSecondary,
            fontSize = 13.sp,
        )

        Text("云端", color = TextSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LlmConfig.PRESETS.filter { it.config.kind == LlmConfig.KIND_CLOUD }.forEach { p ->
                AssistChip(
                    onClick = {
                        baseUrl = p.config.baseUrl
                        model = p.config.model
                        kind = p.config.kind
                        nativeTools = p.config.supportsNativeTools
                        profileName = p.name
                        saved = false
                    },
                    label = { Text(p.name) },
                )
            }
        }
        Text("本机 / 局域网", color = TextSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LlmConfig.PRESETS.filter { it.config.kind != LlmConfig.KIND_CLOUD }.forEach { p ->
                AssistChip(
                    onClick = {
                        baseUrl = p.config.baseUrl
                        model = p.config.model
                        apiKey = p.config.apiKey
                        kind = p.config.kind
                        nativeTools = p.config.supportsNativeTools
                        profileName = p.name
                        saved = false
                    },
                    label = { Text(p.name) },
                )
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; saved = false },
            label = { Text("Base URL") },
            supportingText = { Text("例如 https://api.openai.com/v1 或 http://127.0.0.1:11434/v1") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false },
            label = { Text("API Key（本机可留空）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        )
        Row {
            OutlinedButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏 Key" else "显示 Key") }
        }
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; saved = false },
            label = { Text("模型名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = tempStr,
                onValueChange = { tempStr = it; saved = false },
                label = { Text("temperature") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = maxTokStr,
                onValueChange = { maxTokStr = it; saved = false },
                label = { Text("max_tokens") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = nativeTools,
                onClick = { nativeTools = !nativeTools; saved = false },
                label = { Text(if (nativeTools) "原生 tools" else "ReAct 解析") },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (nativeTools) "发给 /chat/completions 的 tools 字段" else "从文本解析 <tool_call> / Action:",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                vm.setConfig(currentCfg())
                saved = true
            }) { Text("保存") }
            OutlinedButton(onClick = {
                vm.setConfig(currentCfg())
                vm.testConnection()
            }, enabled = !testing) { Text(if (testing) "测试中…" else "测试连接") }
            OutlinedButton(onClick = {
                vm.setConfig(currentCfg())
                vm.refreshModels()
            }, enabled = !listing) { Text(if (listing) "拉取中…" else "列出模型") }
        }
        if (saved) Text("已保存", color = AccentGreen, fontSize = 13.sp)
        val enc by vm.keysEncrypted.collectAsState()
        Text(
            if (enc) "API Key 使用系统密钥库加密存储。" else "警告：本机不支持加密存储，Key 以明文保存。",
            color = if (enc) TextSecondary else Danger,
            fontSize = 12.sp,
        )
        test?.let { r ->
            Text(
                buildString {
                    append(r.message)
                    if (r.models.isNotEmpty()) append(" · 发现 ${r.models.size} 个模型")
                    if (r.sample.isNotBlank()) append("\n试聊: ${r.sample.take(120)}")
                },
                color = if (r.ok) AccentGreen else Danger,
                fontSize = 12.sp,
            )
        }
        if (listed.isNotEmpty()) {
            Text("可用模型（点选填入）", color = TextSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listed.take(24).forEach { id ->
                    AssistChip(onClick = { model = id; saved = false }, label = { Text(id.take(40)) })
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("配置档案", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = profileName,
            onValueChange = { profileName = it },
            label = { Text("档案名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedButton(onClick = {
            vm.setConfig(currentCfg())
            vm.saveProfile(profileName)
            tick++
        }) { Text("保存为档案") }

        if (profiles.isEmpty()) {
            Text("还没有档案。测通后保存，下次一键切换。", color = TextSecondary, fontSize = 12.sp)
        } else {
            profiles.forEach { p ->
                Column(
                    Modifier.fillMaxWidth().background(SurfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, color = TextPrimary, modifier = Modifier.weight(1f))
                        p.lastOk?.let { ok ->
                            Text(
                                if (ok) "${p.lastLatencyMs ?: 0}ms" else "失败",
                                color = if (ok) AccentGreen else Danger,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Text("${p.config.baseUrl} · ${p.config.model}", color = TextSecondary, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            vm.applyProfile(p.id)
                            val c = vm.config()
                            baseUrl = c.baseUrl; apiKey = c.apiKey; model = c.model
                            tempStr = c.temperature.toString(); maxTokStr = c.maxTokens.toString()
                            kind = c.kind; nativeTools = c.supportsNativeTools
                            profileName = p.name
                            saved = true
                        }) { Text("应用") }
                        OutlinedButton(onClick = { vm.deleteProfile(p.id); tick++ }) { Text("删除") }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("记忆 (store.get/set)", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        val keys = remember(tick) { vm.settings().memoryKeys() }
        if (keys.isEmpty()) {
            Text("（空）", color = TextSecondary, fontSize = 13.sp)
        } else {
            keys.forEach { k ->
                val v = vm.settings().memoryGet(k)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("$k = ", color = AccentGreen, fontSize = 13.sp)
                    Text((v ?: "").take(80), color = TextPrimary, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("存储空间", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        val usage = remember(tick) { vm.storageUsage() }
        val total = remember(tick) { vm.storageTotal() }
        val free = remember(tick) { vm.storageFree() }
        Column(
            Modifier.fillMaxWidth().background(SurfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text("App 占用", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(com.selfmod.agent.util.StorageStats.human(total), color = AccentBlue, fontSize = 13.sp)
            }
            Text("设备剩余 ${com.selfmod.agent.util.StorageStats.human(free)}", color = TextSecondary, fontSize = 12.sp)
            usage.filter { it.bytes > 0 }.forEach { u ->
                Row(Modifier.fillMaxWidth()) {
                    Text(u.label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(com.selfmod.agent.util.StorageStats.human(u.bytes), color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.clearCache(); tick++ }) { Text("清理缓存", fontSize = 12.sp) }
            OutlinedButton(onClick = { vm.clearModelCopies(); tick++ }) { Text("删除模型副本", fontSize = 12.sp) }
            OutlinedButton(onClick = { vm.clearScriptVersions(); tick++ }) { Text("清理版本", fontSize = 12.sp) }
        }
        val storageMsg by vm.storageMsg.collectAsState()
        if (storageMsg.isNotBlank()) {
            Text(storageMsg, color = AccentGreen, fontSize = 12.sp)
        }
    }
}
