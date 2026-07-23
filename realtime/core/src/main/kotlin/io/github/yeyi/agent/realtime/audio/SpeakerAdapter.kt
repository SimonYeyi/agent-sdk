package io.github.yeyi.agent.realtime.audio

public interface SpeakerAdapter {
    public suspend fun play(pcm: ByteArray)
    public suspend fun stopPlayback()
    public suspend fun start()
    public suspend fun close()
}
