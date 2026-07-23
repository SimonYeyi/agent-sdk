package io.github.yeyi.agent.demo.s2s

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    val context = LocalContext.current
    var status by remember { mutableStateOf("Idle") }
    var transcript by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf<RealtimeAppliance?>(null) }
    var httpClient by remember { mutableStateOf<HttpClient?>(null) }

    fun startBridge() {
        val client = HttpClient(CIO) { install(WebSockets) }
        val session: RealtimeSession = VolcRealtimeSession(client)
        val mic = AndroidMicrophoneAdapter()
        val speaker = AndroidSpeakerAdapter()
        val b = RealtimeAppliance(
            session = session,
            mic = mic,
            speaker = speaker,
            delegation = BossDelegation(boss),
            sessionConfig = SessionConfig(
                apiKey = apiKey,
                endpoint = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue",
                model = "1.2.6.0",
                instructions = buildInstructions(),
                voice = "saturn_zh_female_wumeiyujie_tob",
                inputFormat = mic.inputFormat,
                outputFormat = speaker.outputFormat,
            ),
        )
        httpClient = client
        scope.launch { b.start() }
        bridge = b
        status = "Listening"
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startBridge()
        } else {
            status = "需要麦克风权限"
        }
    }

    Column(modifier.padding(16.dp)) {
        Text("S2S 语音模式（手动开启）", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(status)
        Spacer(Modifier.height(8.dp))
        Text("转写: $transcript", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Button(onClick = {
            if (bridge == null) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    startBridge()
                } else {
                    recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                val old = bridge
                bridge = null
                scope.launch { old?.close() }
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
    你是一个智能家居助手. 用自然、口语化的中文回答用户.
    不要提及"作为 AI"或"我没有物理设备"等限制 — 你就是这个家庭里的助手.
""".trimIndent()
