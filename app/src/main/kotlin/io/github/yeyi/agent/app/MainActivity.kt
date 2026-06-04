package io.github.yeyi.agent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.yeyi.agent.app.demo.DemoAgentFactory
import io.github.yeyi.agent.app.ui.ChatScreen
import io.github.yeyi.agent.app.vm.ChatViewModel
import io.github.yeyi.agent.app.vm.ChatViewModelFactory
import io.github.yeyi.agent.core.agent.Agent

class MainActivity : ComponentActivity() {

    private val agent: Agent by lazy { DemoAgentFactory.create() }

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(agent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(viewModel = viewModel)
                }
            }
        }
    }
}
