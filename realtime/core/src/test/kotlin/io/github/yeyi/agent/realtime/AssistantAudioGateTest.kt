package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantAudioGateTest {
    private class FakeSpeaker : SpeakerAdapter {
        val played = mutableListOf<ByteArray>()
        var stopped = 0
        override val outputFormat = AudioFormat(
            sampleRateHz = 24_000,
            channels = 1,
            sampleBits = 16,
            encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
        )
        override suspend fun play(pcm: ByteArray) { played += pcm }
        override suspend fun stopPlayback() { stopped++ }
        override suspend fun start() {}
        override suspend fun close() {}
    }

    @Test
    fun `non-marker text flushes buffered audio and passes through`() = runTest {
        val speaker = FakeSpeaker()
        var delegateCalled = false
        val gate = AssistantAudioGate(onDelegate = { delegateCalled = true }, speaker = speaker)

        gate.onUserTranscriptCompleted("hello")
        gate.onAudioDelta(byteArrayOf(1, 2, 3))
        gate.onAudioDelta(byteArrayOf(4, 5, 6))
        gate.onTextDelta("yes, hello there")

        assertEquals(2, speaker.played.size)
        assertEquals(byteArrayOf(1, 2, 3).toList(), speaker.played[0].toList())
        assertEquals(byteArrayOf(4, 5, 6).toList(), speaker.played[1].toList())
        assertTrue(!delegateCalled)
    }
}
