package io.github.yeyi.agent.app

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
import io.github.yeyi.agent.app.demo.DemoAgentFactory
import io.github.yeyi.agent.app.ui.ChatScreen
import io.github.yeyi.agent.app.ui.SessionScreen
import io.github.yeyi.agent.app.vm.ChatViewModel
import io.github.yeyi.agent.app.vm.ChatViewModelFactory
import io.github.yeyi.agent.app.vm.SessionViewModel
import io.github.yeyi.agent.memory.InMemoryMemory

class MainActivity : ComponentActivity() {

    private val sharedMemory = InMemoryMemory()

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(
            agentFactory = { DemoAgentFactory.create(memory = sharedMemory) },
            memory = sharedMemory
        )
    }

    private val sessionViewModel: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainApp(
                        chatViewModel = chatViewModel,
                        sessionViewModel = sessionViewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun MainApp(
    chatViewModel: ChatViewModel,
    sessionViewModel: SessionViewModel
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
            SessionScreen(
                viewModel = sessionViewModel,
                onBack = { currentScreen = "chat" }
            )
        }
    }
}