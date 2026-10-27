package io.github.yeyi.agent.demo.team.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.demo.team.BuildConfig
import io.github.yeyi.agent.demo.team.voice.SeedAsrEngine
import io.github.yeyi.agent.demo.team.voice.SeedAsrEngine.State
import io.github.yeyi.agent.realtime.audio.android.AndroidMicrophoneAdapter
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/**
 * 按住说话语音输入按钮：长按开始火山 Seed-ASR 流式识别，中间结果实时回填
 * 输入框；松开结束本轮推流并等待定稿，最终结果回填输入框并触发 [onSend]。
 *
 * 自采集 AudioRecord 16kHz PCM 直推 WebSocket，完全绕开系统 SpeechRecognizer：
 * - 服务端 `end_window_size=10s` 长静音容忍，按住期间自然停顿不判停；
 * - 无续接重启、无系统滴滴提示音、无转写结果不完整问题。
 *
 * 按下时自动检查并申请 RECORD_AUDIO 运行时权限，未授权则提示先授权。
 * 依赖火山 ASR 凭证（local.properties 中 VOLC_API_KEY=appid:token）。
 */
@Composable
fun PressToTalkButton(
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var listening by remember { mutableStateOf(false) }

    // 回调经 rememberUpdatedState 取最新值，避免引擎闭包捕获过期回调。
    val currentOnInputChange by rememberUpdatedState(onInputChange)
    val currentOnSend by rememberUpdatedState(onSend)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) "已授权，请重新长按开始语音输入" else "未授权录音权限，语音输入不可用",
            Toast.LENGTH_SHORT
        ).show()
    }

    val client = remember {
        HttpClient(OkHttp) {
            install(WebSockets) {
                // 长按住静默期保活：服务端 end_window_size=10s，空闲不超过 10s，
                // ping 间隔取 20s 避免频繁探测。
                pingIntervalMillis = 20_000
            }
        }
    }
    DisposableEffect(client) { onDispose { client.close() } }

    val engine = remember(scope, client) {
        SeedAsrEngine(
            scope = scope,
            client = client,
            microphone = AndroidMicrophoneAdapter(),
            appKey = BuildConfig.VOLC_API_KEY,
        ).apply {
            onStateChange = { state ->
                // Finalizing 阶段保持"按住中"视觉，直到定稿/出错回落 Idle
                listening = state == State.Listening || state == State.Finalizing
            }
            onInterimResult = { text -> currentOnInputChange(text) }
            onFinalResult = { text ->
                currentOnInputChange(text)
                if (text.isNotBlank()) {
                    currentOnSend()
                }
            }
            onError = { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    DisposableEffect(engine) { onDispose { engine.close() } }

    val containerColor = if (listening) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (listening) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(containerColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (listening) {
                            // 上一轮尚未结束（含定稿等待中），忽略本次按压
                            tryAwaitRelease()
                            return@detectTapGestures
                        }
                        // 自采集同样需要录音权限：未授权先请求，授权后重新长按即可开始
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            tryAwaitRelease()
                            return@detectTapGestures
                        }
                        listening = true
                        engine.start()
                        val released = tryAwaitRelease()
                        if (released) {
                            // 正常松手：结束推流，等待定稿后由 onFinalResult 回填并发送
                            engine.stop()
                        } else {
                            // 手势取消（被其他手势抢占等）：中止本轮，不发送
                            engine.cancel()
                            listening = false
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = if (listening) "松开结束语音输入" else "长按语音输入",
            tint = contentColor
        )
    }
}
