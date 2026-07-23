package io.github.yeyi.agent.realtime.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioFormatTest {
    @Test
    fun `AudioFormat stores its properties`() {
        val f = AudioFormat(
            sampleRateHz = 16000,
            encoding = AudioFormat.Encoding.PCM_16BIT,
        )
        assertEquals(16000, f.sampleRateHz)
        assertEquals(AudioFormat.Encoding.PCM_16BIT, f.encoding)
    }

    @Test
    fun `Encoding has three values`() {
        val values = AudioFormat.Encoding.entries.toSet()
        assertEquals(
            setOf(
                AudioFormat.Encoding.PCM_16BIT,
                AudioFormat.Encoding.PCM_OPUS,
                AudioFormat.Encoding.PCM_32BIT_FLOAT,
            ),
            values,
        )
    }
}