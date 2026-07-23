package io.github.yeyi.agent.realtime.audio

import kotlinx.coroutines.flow.Flow

public interface MicrophoneAdapter {
    public val inputFormat: AudioFormat
    public fun capture(): Flow<ByteArray>
    public suspend fun start()
    public suspend fun close()
}
