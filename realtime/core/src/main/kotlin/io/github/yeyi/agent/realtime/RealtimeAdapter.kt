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
    public fun cancelResponseFrame(): ProtocolFrame
    public fun injectAndRespondFrame(text: String): ProtocolFrame

    public suspend fun handleIncomingFrame(frame: ProtocolFrame): List<ProtocolFrame>
}

public data class ProtocolFrame(val payload: JsonObject)
