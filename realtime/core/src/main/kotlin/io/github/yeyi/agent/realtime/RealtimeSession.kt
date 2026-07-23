package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.coroutines.flow.Flow

public interface RealtimeSession : AutoCloseable {
    /** ASR 输入格式。 */
    public val inputAudioFormat: AudioFormat

    /** TTS 输出格式。 */
    public val outputAudioFormat: AudioFormat

    public suspend fun connect(config: SessionConfig)
    public override fun close()

    public suspend fun sendAudio(pcm: ByteArray)
    public suspend fun commitInput()
    public suspend fun cancelResponse()
    public suspend fun injectAndRespond(text: String)

    public val events: Flow<RealtimeEvent>
}
