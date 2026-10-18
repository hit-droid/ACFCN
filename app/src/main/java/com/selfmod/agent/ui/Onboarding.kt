package com.selfmod.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary

/**
 * Onboarding is a one-line strip by default: a full card at the top of the
 * chat tab swallowed half of the visible message area before the user even
 * typed anything. Steps only expand on request, and "知道了" hides the strip
 * for good (persisted in SettingsStore).
 */
@Composable
fun OnboardingCard(
    onOpenApi: () -> Unit,
    onOpenOffline: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(SurfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("第一次用？", color = TextPrimary, fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "看引导", fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("知道了", fontSize = 13.sp)
            }
        }

        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("1. 在 API 页选云端预设并粘贴 Key，或扫描本机 Ollama / llama.cpp。", color = TextSecondary, fontSize = 13.sp)
                Text("2. 可选：在离线页导入 GGUF，用本机服务加载后接入。", color = TextSecondary, fontSize = 13.sp)
                Text("3. 回到智能体发一条消息。浏览器页与智能体共用同一个 WebView。", color = TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenApi) { Text("去接入 API") }
                    OutlinedButton(onClick = onOpenOffline) { Text("去离线") }
                }
                Text("密钥用系统密钥库加密保存。", color = AccentBlue, fontSize = 12.sp)
            }
        }
    }
}
