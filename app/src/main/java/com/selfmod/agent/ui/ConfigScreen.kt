package com.selfmod.agent.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.llm.LlmConfig
import com.selfmod.agent.ui.theme.AccentGreen
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
    var saved by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LLM 配置", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            "用于工具调用的对话模型。OpenAI 兼容接口均可。Key 仅存储在本机应用沙箱内。",
            color = TextSecondary,
            fontSize = 13.sp,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmConfig.PRESETS.forEach { (name, cfg) ->
                AssistChip(
                    onClick = {
                        baseUrl = cfg.baseUrl
                        model = cfg.model
                        saved = false
                    },
                    label = { Text(name) },
                )
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; saved = false },
            label = { Text("Base URL (OpenAI 兼容)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; saved = false },
            label = { Text("模型名 (如 glm-4-plus)") },
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

        Row {
            Button(onClick = {
                val cfg = LlmConfig(
                    baseUrl = baseUrl.trim(),
                    apiKey = apiKey.trim(),
                    model = model.trim(),
                    temperature = tempStr.trim().toDoubleOrNull() ?: 0.6,
                    maxTokens = maxTokStr.trim().toIntOrNull() ?: 2048,
                )
                vm.setConfig(cfg)
                saved = true
            }) {
                Text("保存")
            }
            Spacer(Modifier.width(12.dp))
            if (saved) Text("已保存 ✓", color = AccentGreen, modifier = Modifier.padding(top = 12.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text("记忆 (store.get/set)", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        val keys = remember { vm.settings().memoryKeys() }
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
    }
}
