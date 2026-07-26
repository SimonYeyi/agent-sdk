package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.coroutines.flow.Flow

/**
 * RealtimeSession - 公开 API
 */
public interface RealtimeSession : AutoCloseable {
    public suspend fun connect(config: SessionConfig)
    public override fun close()

    public suspend fun sendAudio(pcm: ByteArray)
    public suspend fun commitInput()
    public suspend fun cancelResponse()
    public suspend fun injectAndRespond(text: String)

    public val inputAudioFormat: AudioFormat
    public val outputAudioFormat: AudioFormat
    public val events: Flow<RealtimeEvent>
}
