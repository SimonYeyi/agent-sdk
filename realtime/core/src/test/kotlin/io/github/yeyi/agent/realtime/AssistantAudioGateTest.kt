package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantAudioGateTest {
    private class FakeSpeaker : SpeakerAdapter {
        val played = mutableListOf<ByteArray>()
        var stopped = 0
        override suspend fun play(pcm: ByteArray) { played += pcm }
        override suspend fun stopPlayback() { stopped++ }
        override suspend fun start() {}
        override suspend fun close() {}
    }

    @Test
    fun `non-marker text flushes buffered audio and passes through`() = runTest {
        val speaker = FakeSpeaker()
        var delegateCalled = false
        val gate = AssistantAudioGate(
            delegationMarker = DelegationHandler.DELEGATION_MARKER, onDelegate = { delegateCalled = true }, speaker = speaker)

        gate.onUserTranscriptCompleted("hello")
        gate.onAudioDelta(byteArrayOf(1, 2, 3))
        gate.onAudioDelta(byteArrayOf(4, 5, 6))
        gate.onTextDelta("yes, hello there")

        assertEquals(2, speaker.played.size)
        assertEquals(byteArrayOf(1, 2, 3).toList(), speaker.played[0].toList())
        assertEquals(byteArrayOf(4, 5, 6).toList(), speaker.played[1].toList())
        assertTrue(!delegateCalled)
    }

    @Test
    fun `marker text drops audio and invokes onDelegate with ASR text`() = runTest {
        val speaker = FakeSpeaker()
        var delegatedText: String? = null
        val gate = AssistantAudioGate(
            delegationMarker = DelegationHandler.DELEGATION_MARKER, onDelegate = { delegatedText = it }, speaker = speaker)

        gate.onUserTranscriptCompleted("open the door")
        gate.onAudioDelta(byteArrayOf(7, 8, 9))
        gate.onTextDelta("<|TASK|>")

        assertEquals("open the door", delegatedText)
        assertTrue(speaker.played.isEmpty())
    }

    @Test
    fun `subsequent audio after marker is dropped`() = runTest {
        val speaker = FakeSpeaker()
        val gate = AssistantAudioGate(
            delegationMarker = DelegationHandler.DELEGATION_MARKER, onDelegate = {}, speaker = speaker)

        gate.onUserTranscriptCompleted("hi")
        gate.onTextDelta("<|TASK|>")
        gate.onAudioDelta(byteArrayOf(10, 11))
        gate.onTextDelta("more text")

        assertTrue(speaker.played.isEmpty())
    }

    @Test
    fun `onTurnEnd resets state for next turn`() = runTest {
        val speaker = FakeSpeaker()
        val gate = AssistantAudioGate(
            delegationMarker = DelegationHandler.DELEGATION_MARKER, onDelegate = {}, speaker = speaker)

        gate.onUserTranscriptCompleted("first")
        gate.onAudioDelta(byteArrayOf(1))
        gate.onTextDelta("reply")
        gate.onTurnEnd()

        gate.onUserTranscriptCompleted("second")
        gate.onAudioDelta(byteArrayOf(2))
        gate.onTextDelta("second reply")

        assertEquals(2, speaker.played.size)
    }

    @Test
    fun `onTextDelta throws if marker but no pending ASR text`() = runTest {
        val speaker = FakeSpeaker()
        val gate = AssistantAudioGate(
            delegationMarker = DelegationHandler.DELEGATION_MARKER, onDelegate = {}, speaker = speaker)

        var threw = false
        try {
            gate.onTextDelta("<|TASK|>")
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }
}
