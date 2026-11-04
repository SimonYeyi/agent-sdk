package io.github.yeyi.agent.demo.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.yeyi.agent.demo.agent.ui.ChatScreen
import io.github.yeyi.agent.demo.agent.ui.SessionScreen
import io.github.yeyi.agent.demo.agent.vm.ChatViewModel

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startGatewayService()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainApp(
                        chatViewModel = chatViewModel
                    )
                }
            }
        }
    }

    private fun startGatewayService() {
        val intent = Intent().apply {
            setPackage("io.github.yeyi.agent.gateway.app")
            setAction("io.github.yeyi.agent.gateway.app.START")
        }
        startForegroundService(intent)
    }
}

@Composable
private fun MainApp(
    chatViewModel: ChatViewModel
) {
    var currentScreen by remember { mutableStateOf("chat") }

    when (currentScreen) {
        "chat" -> {
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateToSession = { currentScreen = "session" }
            )
        }

        "session" -> {
            // SessionViewModel 由 SessionScreen 组合内自建（生命周期 = 屏幕生命周期），
            // 不再由 Activity 创建长驻。
            SessionScreen(
                onBack = { currentScreen = "chat" }
            )
        }
    }
}