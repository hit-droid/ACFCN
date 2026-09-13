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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.ui.theme.AccentGreen
import com.selfmod.agent.ui.theme.AccentPurple
import com.selfmod.agent.ui.theme.Danger
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary

@Composable
fun PluginsScreen(vm: AgentViewModel) {
    var available by remember { mutableStateOf(vm.availablePlugins()) }
    var loaded by remember { mutableStateOf(vm.loadedPlugins()) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Dex 插件", color = TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                available = vm.availablePlugins(); loaded = vm.loadedPlugins()
            }) { Text("刷新") }
        }
        Text(
            "插件是运行时通过 DexClassLoader 加载的 .dex 文件，需实现 com.selfmod.agent.plugin.SelfModPlugin。" +
                "智能体可用 install_plugin 工具从 base64 安装并 load_plugin 调用，从而把新功能模块热接入应用。",
            color = TextSecondary, fontSize = 12.sp,
        )

        if (available.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(SurfaceVariant, RoundedCornerShape(8.dp)).padding(16.dp),
            ) {
                Text("尚无已安装插件。", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "提示：在「智能体」页让智能体编写一个实现 SelfModPlugin 的 Kotlin 类，编译为 dex 后通过 install_plugin 安装。",
                    color = TextSecondary, fontSize = 11.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(available) { name ->
                    val isLoaded = loaded.contains(name)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = SurfaceVariant),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(name, color = TextPrimary, modifier = Modifier.weight(1f), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                Text(
                                    if (isLoaded) "已加载" else "未加载",
                                    color = if (isLoaded) AccentGreen else TextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    vm.loadPlugin(name); loaded = vm.loadedPlugins()
                                }, enabled = !isLoaded) { Text("加载") }
                                OutlinedButton(onClick = {
                                    vm.unloadPlugin(name); loaded = vm.loadedPlugins()
                                }, enabled = isLoaded) { Text("卸载") }
                                OutlinedButton(onClick = {
                                    vm.deletePlugin(name); available = vm.availablePlugins(); loaded = vm.loadedPlugins()
                                }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("提示：插件由智能体动态安装。", color = AccentPurple, fontSize = 11.sp)
    }
}
