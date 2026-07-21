package io.github.yeyi.agent.realtime.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioFormatTest {
    @Test
    fun `AudioFormat stores its properties`() {
        val f = AudioFormat(
            sampleRateHz = 16000,
            channels = 1,
            sampleBits = 16,
            encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
        )
        assertEquals(16000, f.sampleRateHz)
        assertEquals(1, f.channels)
        assertEquals(16, f.sampleBits)
        assertEquals(AudioFormat.Encoding.PCM_SIGNED_LE, f.encoding)
    }

    @Test
    fun `Encoding has three values`() {
        val values = AudioFormat.Encoding.entries.toSet()
        assertEquals(
            setOf(
                AudioFormat.Encoding.PCM_SIGNED_LE,
                AudioFormat.Encoding.PCM_OPUS,
                AudioFormat.Encoding.PCM_FLOAT_LE,
            ),
            values,
        )
    }
}