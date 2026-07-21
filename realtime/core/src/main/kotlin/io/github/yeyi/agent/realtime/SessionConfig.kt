package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.serialization.json.JsonElement

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

data class SessionConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val instructions: String,
    val voice: String,
    val inputFormat: AudioFormat,
    val outputFormat: AudioFormat,
    val tools: List<ToolDefinition> = emptyList(),
    val turnDetection: TurnDetection = TurnDetection.ServerVad(),
)

sealed interface TurnDetection {
    /** 服务端 VAD 判定说话结束; endSmoothWindowMs 为静音判定窗口(毫秒)。 */
    data class ServerVad(val endSmoothWindowMs: Int = 1500) : TurnDetection

    /** 屏蔽服务端 VAD, 由调用方 commitInput() 告知说话结束。 */
    data object Manual : TurnDetection
}