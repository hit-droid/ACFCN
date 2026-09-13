package com.selfmod.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("智能体", Icons.Filled.Chat),
    Tab("脚本", Icons.Filled.Code),
    Tab("插件", Icons.Filled.Extension),
    Tab("配置", Icons.Filled.Settings),
)

@Composable
fun MainScreen(vm: AgentViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(t.label) },
                        icon = { Icon(t.icon, contentDescription = null) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> AgentScreen(vm)
                1 -> ScriptsScreen(vm)
                2 -> PluginsScreen(vm)
                3 -> ConfigScreen(vm)
            }
        }
    }
}
