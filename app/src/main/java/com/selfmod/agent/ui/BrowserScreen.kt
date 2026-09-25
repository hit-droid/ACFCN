package com.selfmod.agent.ui

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.selfmod.agent.browser.BrowserController
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.SurfaceDark
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextSecondary

@Composable
fun PersistentWebView(vm: AgentViewModel, visible: Boolean) {
    val browser = vm.browser()
    AndroidView(
        factory = { ctx ->
            WebView(ctx).also { wv -> browser.attach(wv) }
        },
        modifier = if (visible) Modifier.fillMaxSize() else Modifier.size(1.dp),
        update = { wv -> browser.attach(wv) },
    )
}

@Composable
fun BrowserChrome(vm: AgentViewModel) {
    val browser = vm.browser()
    val url by browser.url.collectAsState()
    val loading by browser.loading.collectAsState()
    val progress by browser.progress.collectAsState()
    val title by browser.title.collectAsState()
    val desktop by browser.desktop.collectAsState()
    var bar by remember { mutableStateOf(url.ifBlank { BrowserController.HOME }) }
    LaunchedEffect(url) {
        if (url.isNotBlank() && url != bar) bar = url
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { browser.goBack() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "后退")
            }
            IconButton(onClick = { browser.goForward() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "前进")
            }
            IconButton(onClick = { browser.reload() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { browser.navigate(BrowserController.HOME); bar = BrowserController.HOME }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Home, contentDescription = "主页")
            }
            OutlinedTextField(
                value = bar,
                onValueChange = { bar = it },
                modifier = Modifier.weight(1f).height(52.dp),
                singleLine = true,
                placeholder = { Text("网址或搜索", color = TextSecondary, fontSize = 13.sp) },
            )
            TextButton(onClick = { vm.navigateBrowser(bar) }) { Text("前往") }
        }
        if (loading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = AccentBlue,
            )
        }
        Row(
            Modifier.fillMaxWidth().background(SurfaceVariant).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (title.ifBlank { url }).take(48),
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { browser.setDesktop(!desktop) }) {
                Text(if (desktop) "桌面 UA" else "手机 UA", fontSize = 11.sp)
            }
        }
        Text(
            "用户与智能体共用此浏览器。智能体可用 browser_open / snapshot / click / type。",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun BrowserScreen(vm: AgentViewModel) {
    Column(Modifier.fillMaxSize()) {
        BrowserChrome(vm)
        Box(Modifier.weight(1f).fillMaxWidth())
    }
}
