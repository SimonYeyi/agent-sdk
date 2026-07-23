package io.github.yeyi.agent.realtime.audio

import kotlinx.coroutines.flow.Flow

public interface MicrophoneAdapter {
    public suspend fun start(format: AudioFormat)
    public fun capture(): Flow<ByteArray>
    public suspend fun close()
}
