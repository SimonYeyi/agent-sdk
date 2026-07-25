package io.github.yeyi.agent.demo

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
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yeyi.agent.demo.s2s.SmartHomeS2sScreen
import io.github.yeyi.agent.demo.smartHome.SmartHomeAgent
import io.github.yeyi.agent.demo.ui.DemoScreen
import io.github.yeyi.agent.demo.vm.DemoViewModel
import io.github.yeyi.agent.demo.vm.Scenario

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通过系统属性配置 LLM Provider
        // -DMODEL_PROVIDER=openai -DMODEL_BASE_URL=https://api.openai.com -DMODEL_API_KEY=xxx -DMODEL_NAME=gpt-4
        val llmProvider = LlmProviderFactory.create()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: DemoViewModel = viewModel(
                        factory = DemoViewModel.Factory(llmProvider)
                    )

                    val taskGroups by viewModel.taskGroups.collectAsState()
                    val messages by viewModel.messages.collectAsState()
                    val inputText by viewModel.inputText.collectAsState()
                    val currentScenario by viewModel.currentScenario.collectAsState()

                    var voiceMode by remember { mutableStateOf(false) }
                    val smartHomeBoss = remember { SmartHomeAgent.create(llmProvider) }

                    // S2S 语音模式拦截返回键，先关闭语音模式
                    val onBack: () -> Unit = { voiceMode = false }

                    DemoScreen(
                        taskGroups = taskGroups,
                        messages = messages,
                        inputText = inputText,
                        onInputChange = viewModel::onInputChange,
                        onSend = viewModel::onSend,
                        scenarioName = when (currentScenario) {
                            Scenario.SMART_HOME -> "智能家居"
                            Scenario.SMART_COCKPIT -> "智能座舱"
                        },
                        currentScenario = currentScenario.name,
                        onScenarioSwitch = viewModel::switchScenario,
                        voiceMode = voiceMode,
                        onVoiceToggle = { voiceMode = !voiceMode },
                        s2sContent = if (voiceMode) {
                            {
                                SmartHomeS2sScreen(
                                    apiKey = BuildConfig.VOLC_API_KEY,
                                    boss = smartHomeBoss,
                                    onBack = onBack,
                                )
                            }
                        } else null,
                    )
                }
            }
        }
    }
}
