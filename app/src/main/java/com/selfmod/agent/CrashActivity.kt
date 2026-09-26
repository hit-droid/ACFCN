package com.selfmod.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.ui.theme.BgDark
import com.selfmod.agent.ui.theme.Danger
import com.selfmod.agent.ui.theme.SelfModTheme
import com.selfmod.agent.ui.theme.SurfaceVariant
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary

class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as App).crashStore
        val report = store.read() ?: "（没有可用的崩溃日志）"
        setContent {
            SelfModTheme {
                CrashScreen(
                    report = report,
                    onCopy = {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ACFCN crash", report))
                    },
                    onShare = {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "ACFCN 崩溃报告")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        startActivity(Intent.createChooser(i, "分享崩溃报告"))
                    },
                    onContinue = {
                        store.clear()
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            },
                        )
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun CrashScreen(
    report: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(BgDark).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("应用崩溃了", color = Danger, fontSize = 22.sp)
        Text(
            "别慌，这不是你的操作问题。下面是崩溃日志，点击复制或分享给我，我就能定位并修复。",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("复制日志") }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("分享") }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(SurfaceVariant, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(report, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("清除并重新进入")
        }
    }
}
