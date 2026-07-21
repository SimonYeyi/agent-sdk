package io.github.yeyi.agent.realtime.audio

data class AudioFormat(
    val sampleRateHz: Int,
    val channels: Int,
    val sampleBits: Int,
    val encoding: Encoding,
) {
    enum class Encoding { PCM_SIGNED_LE, PCM_OPUS, PCM_FLOAT_LE }
}