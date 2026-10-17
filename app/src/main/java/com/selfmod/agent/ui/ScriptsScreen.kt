package com.selfmod.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.repo.Version
import com.selfmod.agent.ui.theme.AccentAmber
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.AccentGreen
import com.selfmod.agent.ui.theme.Danger
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(vm: AgentViewModel) {
    var names by remember { mutableStateOf(vm.listScripts()) }
    var currentName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showVersions by remember { mutableStateOf(false) }
    val output by vm.scriptOutput.collectAsState()
    val running by vm.scriptRunning.collectAsState()

    fun refresh() { names = vm.listScripts() }
    fun load(name: String) {
        currentName = name
        code = vm.readScript(name)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("脚本编辑器", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            "这里的脚本会被智能体用 write_script 工具修改、用 execute_js 工具立即执行。" +
                "脚本沙箱内可用 api/log/toast/http/llm/store/scripts/plugins/ui/now。",
            color = TextSecondary, fontSize = 12.sp,
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = currentName,
                onValueChange = { currentName = it },
                label = { Text("脚本名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (currentName.isNotBlank()) {
                    if (vm.scriptExists(currentName)) load(currentName) else code = "// $currentName.js\n"
                }
            }) { Text("加载") }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(names) { n ->
                AssistChip(
                    onClick = { load(n); currentName = n },
                    label = { Text(n) },
                )
            }
        }

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("JavaScript") },
            modifier = Modifier.fillMaxWidth().height(280.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (currentName.isNotBlank()) {
                        vm.saveScript(currentName, code)
                        refresh()
                    }
                },
                enabled = currentName.isNotBlank() && code.isNotBlank(),
            ) { Text("保存") }

            OutlinedButton(
                onClick = { vm.runScript(code, "${currentName.ifBlank { "manual" }}.js") },
                enabled = code.isNotBlank() && !running,
            ) { if (running) Text("运行中…") else Text("运行") }

            OutlinedButton(onClick = { showVersions = true }, enabled = currentName.isNotBlank()) {
                Text("历史")
            }

            OutlinedButton(
                onClick = {
                    if (currentName.isNotBlank()) {
                        vm.deleteScript(currentName)
                        currentName = ""
                        code = ""
                        refresh()
                    }
                },
                enabled = currentName.isNotBlank(),
            ) { Text("删除") }
        }

        output?.let { o ->
            Surface(color = SurfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("输出", color = AccentBlue, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    o.value?.let { Text("值: $it", color = AccentGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
                    if (o.logs.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(o.logs, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    o.error?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("错误: $it", color = Danger, fontSize = 12.sp)
                    }
                }
            }
            TextButton(onClick = { vm.clearScriptOutput() }) { Text("清除输出") }
        }

        if (running) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp, color = AccentBlue)
                Spacer(Modifier.width(8.dp))
                Text("执行中…", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }

    if (showVersions) {
        val versions = remember(currentName, showVersions) { vm.versionsOf(currentName) }
        AlertDialog(
            onDismissRequest = { showVersions = false },
            title = { Text("版本回滚: $currentName") },
            text = {
                if (versions.isEmpty()) {
                    Text("无历史版本")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        versions.forEach { v: Version ->
                            TextButton(onClick = {
                                code = vm.rollbackScript(currentName, v.id)
                                showVersions = false
                            }) { Text(v.displayName(), color = AccentAmber) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVersions = false }) { Text("关闭") } },
        )
    }
}
