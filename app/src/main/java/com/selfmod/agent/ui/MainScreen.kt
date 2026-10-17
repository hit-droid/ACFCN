package com.selfmod.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import com.selfmod.agent.ui.theme.BgDark

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("智能体", Icons.Filled.Chat),
    Tab("浏览器", Icons.Filled.Language),
    Tab("离线", Icons.Filled.CloudOff),
    Tab("脚本", Icons.Filled.Code),
    Tab("插件", Icons.Filled.Extension),
    Tab("API", Icons.Filled.Settings),
)

@Composable
fun MainScreen(vm: AgentViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val showOnboarding by vm.showOnboarding.collectAsState()
    val browser = vm.browser()
    val browserVisible = tab == 1
    val requestedTab by vm.requestedTab.collectAsState()

    LaunchedEffect(requestedTab) {
        if (requestedTab >= 0) {
            tab = requestedTab
            vm.consumeRequestedTab()
        }
    }

    // Physical back: on the browser tab, go back in history first.
    BackHandler(enabled = browserVisible) {
        if (!browser.consumeHistoryBack()) tab = 0
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(t.label, fontSize = 10.sp) },
                        icon = { Icon(t.icon, contentDescription = null) },
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showOnboarding && tab == 0) {
                OnboardingCard(
                    onOpenApi = { tab = 5 },
                    onOpenOffline = { tab = 2 },
                    onDismiss = { vm.dismissOnboarding() },
                )
            }
            if (browserVisible) BrowserChrome(vm)

            Box(Modifier.weight(1f).fillMaxSize()) {
                // The shared WebView stays composed for its whole lifetime so the
                // agent can drive it from any tab. It is only *shown* on the
                // browser tab; otherwise the other screens cover it opaquely.
                PersistentWebView(vm, visible = browserVisible)
                if (!browserVisible) {
                    Box(Modifier.fillMaxSize().background(BgDark)) {
                        when (tab) {
                            0 -> AgentScreen(vm)
                            2 -> OfflineScreen(vm)
                            3 -> ScriptsScreen(vm)
                            4 -> PluginsScreen(vm)
                            5 -> ConfigScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
