package io.github.yeyi.agent.demo.team

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yeyi.agent.demo.team.ui.BossScreen
import io.github.yeyi.agent.demo.team.vm.BossViewModel
import io.github.yeyi.agent.session.SessionManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    // 会话持久化根目录：filesDir/agent/sessions/{accountId}/{sessionId}/
                    val sessionManager = remember { SessionManager(context.filesDir) }

                    val viewModel: BossViewModel = viewModel(
                        factory = BossViewModel.Factory(sessionManager)
                    )

                    val taskGroups by viewModel.taskGroups.collectAsState()
                    val messages by viewModel.messages.collectAsState()
                    val pendingInputs by viewModel.pendingInputs.collectAsState()
                    val inputText by viewModel.inputText.collectAsState()

                    BossScreen(
                        taskGroups = taskGroups,
                        messages = messages,
                        pendingInputs = pendingInputs,
                        inputText = inputText,
                        onInputChange = viewModel::onInputChange,
                        onSend = viewModel::onSend,
                        onReset = viewModel::resetSession,
                        scenarioName = "智能座舱",
                    )
                }
            }
        }
    }
}
