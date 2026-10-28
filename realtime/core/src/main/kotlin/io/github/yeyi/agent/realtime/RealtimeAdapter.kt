package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

public interface RealtimeAdapter {
    public val inputAudioFormat: AudioFormat
    public val outputAudioFormat: AudioFormat
    public val events: Flow<RealtimeEvent>

    public fun getAuthHeaders(config: SessionConfig): Map<String, String>

    public fun registerTools(tools: List<Tool>)

    public fun createSessionFrame(config: SessionConfig): ProtocolFrame
    public fun sendAudioFrame(pcm: ByteArray): ProtocolFrame
    public fun commitAudioFrame(): ProtocolFrame
    public fun commitSpeechTextFrame(text: String): List<ProtocolFrame>
    public fun cancelResponseFrame(): ProtocolFrame

    /**
     * 构造检索历史上下文的请求帧。
     *
     * 不带对话 id（items 为空数组）时，服务端返回最近 20 轮完整上下文，
     * 下行结果映射为 [RealtimeEvent.ConversationHistoryRetrieved]。
     */
    public fun retrieveConversationHistoryFrame(): ProtocolFrame

    public suspend fun handleIncomingFrame(frame: ProtocolFrame): List<ProtocolFrame>
}

public data class ProtocolFrame(val payload: JsonObject)
