package io.github.yeyi.agent.realtime

import kotlinx.coroutines.flow.Flow

public interface RealtimeSession : AutoCloseable {
    public suspend fun connect(config: SessionConfig)
    public override fun close()

    public suspend fun sendAudio(pcm: ByteArray)
    public suspend fun commitInput()
    public suspend fun cancelResponse()
    public suspend fun injectAndRespond(text: String)

    public val events: Flow<RealtimeEvent>
}
