package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class SessionConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val instructions: String,
    val voice: String,
    val inputFormat: AudioFormat,
    val outputFormat: AudioFormat,
    val tools: List<Tool> = emptyList(),
    val turnDetection: TurnDetection = TurnDetection.ServerVad(),
)

/**
 * S2S 实时会话里的工具声明. 由调用方在 [SessionConfig.tools] 里传给 provider; FC 路径下
 * RealtimeSession 内部用此接口执行 tool 并回传结果给 S2S 模型.
 */
interface Tool {
    val name: String
    val description: String
    val parametersSchema: JsonObject

    suspend fun execute(arguments: JsonElement): String
}

sealed interface TurnDetection {
    /** 服务端 VAD 判定说话结束; thresholdMs 为静音判定窗口(毫秒)。 */
    data class ServerVad(val thresholdMs: Int = 1500) : TurnDetection

    /** 屏蔽服务端 VAD, 由调用方 commitInput() 告知说话结束。 */
    data object Manual : TurnDetection
}
