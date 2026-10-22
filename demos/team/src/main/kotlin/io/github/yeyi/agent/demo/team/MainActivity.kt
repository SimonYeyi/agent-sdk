package io.github.yeyi.agent.demo.team

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yeyi.agent.demo.team.s2s.CockpitS2sScreen
import io.github.yeyi.agent.demo.team.ui.DemoScreen
import io.github.yeyi.agent.demo.team.vm.DemoViewModel
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

                    val viewModel: DemoViewModel = viewModel(
                        factory = DemoViewModel.Factory(sessionManager)
                    )

                    val boss by viewModel.boss.collectAsState()
                    val taskGroups by viewModel.taskGroups.collectAsState()
                    val messages by viewModel.messages.collectAsState()
                    val inputText by viewModel.inputText.collectAsState()

                    var voiceMode by remember { mutableStateOf(false) }

                    // S2S 语音模式拦截返回键，先关闭语音模式
                    val onBack: () -> Unit = { voiceMode = false }

                    DemoScreen(
                        taskGroups = taskGroups,
                        messages = messages,
                        inputText = inputText,
                        onInputChange = viewModel::onInputChange,
                        onSend = viewModel::onSend,
                        scenarioName = "智能座舱",
                        voiceMode = voiceMode,
                        onVoiceToggle = { voiceMode = !voiceMode },
                        s2sContent = if (voiceMode) {
                            val sharedBoss = boss
                            if (sharedBoss != null) {
                                {
                                    CockpitS2sScreen(
                                        apiKey = BuildConfig.VOLC_API_KEY,
                                        boss = sharedBoss,
                                        onBack = onBack,
                                    )
                                }
                            } else null
                        } else null,
                    )
                }
            }
        }
    }
}
