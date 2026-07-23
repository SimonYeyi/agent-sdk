package io.github.yeyi.agent.realtime.audio

public data class AudioFormat(
    val sampleRateHz: Int,
    val encoding: Encoding,
) {
    public enum class Encoding {
        /** 16bit PCM signed LE，16kHz 输入 / 24kHz 输出均用此编码。 */
        PCM_16BIT,
        PCM_32BIT_FLOAT,
        PCM_OPUS,
    }
}
