package io.github.yeyi.agent.realtime

import kotlinx.coroutines.flow.Flow

interface RealtimeSession : AutoCloseable {
    suspend fun connect(config: SessionConfig)
    override fun close()

    suspend fun sendAudio(pcm: ByteArray)
    suspend fun commitInput()
    suspend fun cancelResponse()
    suspend fun injectAndRespond(text: String)

    val events: Flow<RealtimeEvent>
}