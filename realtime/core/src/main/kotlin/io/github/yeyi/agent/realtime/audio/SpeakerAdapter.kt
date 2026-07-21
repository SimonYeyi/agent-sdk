package io.github.yeyi.agent.realtime.audio

interface SpeakerAdapter {
    val outputFormat: AudioFormat
    suspend fun play(pcm: ByteArray)
    suspend fun stopPlayback()
    suspend fun start()
    suspend fun close()
}
