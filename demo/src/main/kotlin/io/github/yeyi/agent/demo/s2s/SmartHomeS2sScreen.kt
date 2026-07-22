package io.github.yeyi.agent.demo.s2s

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.realtime.RealtimeAppliance
import io.github.yeyi.agent.realtime.RealtimeSession
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.audio.android.AndroidMicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.android.AndroidSpeakerAdapter
import io.github.yeyi.agent.realtime.volc.VolcRealtimeSession
import io.github.yeyi.agent.team.BossAgent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Composable
fun SmartHomeS2sScreen(apiKey: String, boss: BossAgent, modifier: Modifier = Modifier) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    var status by remember { mutableStateOf("Idle") }
    var transcript by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf<RealtimeAppliance?>(null) }
    var httpClient by remember { mutableStateOf<HttpClient?>(null) }

    Column(modifier.padding(16.dp)) {
        Text("S2S 语音模式（手动开启）", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(status)
        Spacer(Modifier.height(8.dp))
        Text("转写: $transcript", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Button(onClick = {
            if (bridge == null) {
                val client = HttpClient(CIO) { install(WebSockets) }
                val session: RealtimeSession = VolcRealtimeSession(client)
                val mic = AndroidMicrophoneAdapter()
                val speaker = AndroidSpeakerAdapter()
                val b = RealtimeAppliance(
                    session = session,
                    mic = mic,
                    speaker = speaker,
                    delegation = BossDelegation(boss),
                    scope = scope,
                )
                httpClient = client
                scope.launch {
                    session.connect(
                        SessionConfig(
                            apiKey = apiKey,
                            endpoint = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue",
                            model = "1.2.6.0",
                            instructions = buildInstructions(),
                            voice = "saturn_zh_female_wumeiyujie_tob",
                            inputFormat = mic.inputFormat,
                            outputFormat = speaker.outputFormat,
                        )
                    )
                    b.start()
                }
                bridge = b
                status = "Listening"
            } else {
                bridge?.close()
                bridge = null
                httpClient?.close()
                httpClient = null
                status = "Idle"
            }
        }) {
            Text(if (bridge == null) "开启全双工" else "关闭全双工")
        }
    }
}

private fun buildInstructions(): String = """
    你是一个智能助手. 区分以下两种情况:
    1. 闲聊（问候 / 聊天 / 知识问答 / 一般咨询）: 直接用自然口语回答.
    2. 需要执行任务（操作设备 / 调用服务 / 多步执行）:
       在 assistant 文本的第一句**必须**以 <|TASK|> 开头,
       后接空行再接你对用户的简短确认.
       这个标记是内部路由信号, **绝对不能**在 TTS 中读出来.
""".trimIndent()