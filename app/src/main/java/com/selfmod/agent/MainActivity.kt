package com.selfmod.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfmod.agent.ui.AgentViewModel
import com.selfmod.agent.ui.MainScreen
import com.selfmod.agent.ui.theme.SelfModTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SelfModTheme {
                val vm: AgentViewModel = viewModel()
                MainScreen(vm)
            }
        }
    }
}
