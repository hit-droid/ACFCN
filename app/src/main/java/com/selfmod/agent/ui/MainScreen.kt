package com.selfmod.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.selfmod.agent.ui.glass.GlassBackground
import com.selfmod.agent.ui.glass.GlassBottomBar
import com.selfmod.agent.ui.glass.GlassTab

private val tabs = listOf(
    GlassTab("智能体", Icons.Filled.Chat),
    GlassTab("浏览器", Icons.Filled.Language),
    GlassTab("离线", Icons.Filled.CloudOff),
    GlassTab("脚本", Icons.Filled.Code),
    GlassTab("插件", Icons.Filled.Extension),
    GlassTab("API", Icons.Filled.Settings),
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

    Box(Modifier.fillMaxSize()) {
        GlassBackground(Modifier.fillMaxSize())

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = { GlassBottomBar(tabs, tab, onSelect = { tab = it }) },
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
                    // The shared WebView stays composed for its whole lifetime so
                    // the agent can drive it from any tab. It is only *shown* on
                    // the browser tab (1dp otherwise); the remaining screens are
                    // transparent, letting the glass wallpaper show through.
                    PersistentWebView(vm, visible = browserVisible)
                    if (!browserVisible) {
                        Box(Modifier.fillMaxSize()) {
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
}
