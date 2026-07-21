package io.github.yeyi.agent.realtime.audio

import kotlinx.coroutines.flow.Flow

interface MicrophoneAdapter {
    val inputFormat: AudioFormat
    fun capture(): Flow<ByteArray>
    suspend fun start()
    suspend fun close()
}
