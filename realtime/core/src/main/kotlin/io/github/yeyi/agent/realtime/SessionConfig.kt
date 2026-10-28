package io.github.yeyi.agent.realtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public data class SessionConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val instructions: String,
    val voice: String,
    val tools: List<Tool> = emptyList(),
    val turnDetection: TurnDetection = TurnDetection.ServerVad(),
    /**
     * S2S 会话 id：null 表示新建会话（provider 生成新 id）；
     * 传入已建立的 id 用于接续历史对话，服务端按该 id 恢复上下文。
     */
    val sessionId: String? = null,
)

/**
 * S2S 实时会话里的工具声明. 由调用方在 [SessionConfig.tools] 里传给 provider; FC 路径下
 * RealtimeSession 内部用此接口执行 tool 并回传结果给 S2S 模型.
 */
public interface Tool {
    public val name: String
    public val description: String
    public val parametersSchema: JsonObject

    public suspend fun execute(arguments: JsonElement): String
}

public sealed interface TurnDetection {
    /** 服务端 VAD 判定说话结束; thresholdMs 为静音判定窗口(毫秒)。 */
    public data class ServerVad(val thresholdMs: Int = 500) : TurnDetection

    /** 屏蔽服务端 VAD, 由调用方 commitAudio() 告知说话结束。 */
    public data object Manual : TurnDetection
}
