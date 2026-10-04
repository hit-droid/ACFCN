package com.selfmod.agent

import android.content.Intent
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

        // If the previous run crashed, surface the report instead of pretending
        // everything is fine. crashStore is constructed before everything else
        // and never throws in practice.
        val app = application as App
        if (app.crashStore.read() != null) {
            startActivity(
                Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                },
            )
            finish()
            return
        }

        // Restore the shared browser after a process death (M12). The WebView is
        // created later by Compose, so this only queues the state for now.
        runCatching { app.browser.restoreState(savedInstanceState) }

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

    override fun onSaveInstanceState(outState: Bundle) {
        // super must run first so the WebView's own view state is already in the
        // bundle before the page state is added (M12).
        super.onSaveInstanceState(outState)
        runCatching { (application as App).browser.saveState(outState) }
    }

    override fun onDestroy() {
        // The shared WebView outlives the tabs, so it must be released here or the
        // activity leaks. This also wakes any agent call waiting on the page.
        runCatching { (application as App).browser.destroy() }
        super.onDestroy()
    }
}