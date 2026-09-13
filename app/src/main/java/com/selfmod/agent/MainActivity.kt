package com.selfmod.agent

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.selfmod.agent.ui.AgentViewModel
import com.selfmod.agent.ui.MainScreen
import com.selfmod.agent.ui.theme.SelfModTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge() 内部调用 Window.setDecorFitsSystemWindows() 是 API 30+，
        // Android 10(API 29) 上直接调 enableEdgeToEdge 会崩。
        // 这里用 WindowInsetsControllerCompat 做版本安全的沉浸式设置。
        val content: View = findViewById(android.R.id.content)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, content).let { controller ->
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }

        setContent {
            SelfModTheme {
                val vm: AgentViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                MainScreen(vm)
            }
        }
    }
}
