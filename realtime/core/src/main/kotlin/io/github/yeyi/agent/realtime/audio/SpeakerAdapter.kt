package io.github.yeyi.agent.realtime.audio

public interface SpeakerAdapter {
    public suspend fun start(format: AudioFormat)
    public suspend fun play(pcm: ByteArray)
    public suspend fun stopPlayback()
    public suspend fun close()
}
