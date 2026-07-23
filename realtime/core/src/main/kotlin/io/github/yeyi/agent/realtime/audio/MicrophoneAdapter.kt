package io.github.yeyi.agent.realtime.audio

import kotlinx.coroutines.flow.Flow

public interface MicrophoneAdapter {
    public fun capture(): Flow<ByteArray>
    public suspend fun start()
    public suspend fun close()
}
