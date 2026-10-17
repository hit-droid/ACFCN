package com.selfmod.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary

@Composable
fun OnboardingCard(
    onOpenApi: () -> Unit,
    onOpenOffline: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(SurfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("开始使用", color = TextPrimary, fontSize = 16.sp)
        Text("1. 在 API 页选云端预设并粘贴 Key，或扫描本机 Ollama / llama.cpp。", color = TextSecondary, fontSize = 13.sp)
        Text("2. 可选：在离线页导入 GGUF，用本机服务加载后接入。", color = TextSecondary, fontSize = 13.sp)
        Text("3. 回到智能体发一条消息。浏览器页与智能体共用同一个 WebView。", color = TextSecondary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenApi) { Text("去接入 API") }
            OutlinedButton(onClick = onOpenOffline) { Text("去离线") }
            OutlinedButton(onClick = onDismiss) { Text("知道了") }
        }
        Text("密钥用系统密钥库加密保存。", color = AccentBlue, fontSize = 12.sp)
    }
}
