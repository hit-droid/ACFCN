package com.selfmod.agent.ui

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.selfmod.agent.browser.BrowserController
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.AccentGreen
import com.selfmod.agent.ui.theme.SurfaceDark
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
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
    val canBack by browser.canBack.collectAsState()
    val history by browser.history.collectAsState()
    var bar by remember { mutableStateOf(url.ifBlank { BrowserController.HOME }) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(url, title) {
        if (url.isNotBlank() && url != "about:blank") bar = url
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { browser.goBack() }, enabled = canBack, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "后退")
            }
            IconButton(onClick = { browser.goForward() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "前进")
            }
            OutlinedTextField(
                value = bar,
                onValueChange = { bar = it },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 44.dp),
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                placeholder = { Text("搜索或输入网址", color = TextSecondary, fontSize = 13.sp) },
            )
            IconButton(onClick = { browser.reload() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            TextButton(onClick = { vm.navigateBrowser(bar) }) { Text("前往", fontSize = 13.sp) }
        }

        if (loading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = AccentBlue,
            )
        }

        Row(
            Modifier.fillMaxWidth().background(SurfaceVariant).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { browser.goHome() }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Home, contentDescription = "主页", modifier = Modifier.size(18.dp))
            }
            Text(
                (title.ifBlank { url }).take(60),
                color = TextPrimary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            TextButton(onClick = { showHistory = !showHistory }) { Text("历史", fontSize = 11.sp) }
            TextButton(onClick = { browser.setDesktop(!desktop) }) {
                Text(if (desktop) "桌面" else "手机", fontSize = 11.sp, color = if (desktop) AccentGreen else TextSecondary)
            }
        }

        AnimatedVisibility(visible = showHistory) {
            Column(
                Modifier.fillMaxWidth().background(SurfaceDark).heightIn(max = 220.dp),
            ) {
                if (history.isEmpty()) {
                    Text("暂无历史", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                } else {
                    history.take(20).forEach { h ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { browser.navigate(h); bar = h; showHistory = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(h, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        Text(
            "用户与智能体共用此浏览器。智能体可 browser_open / snapshot / click / type，操作时页面会高亮标记。",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
