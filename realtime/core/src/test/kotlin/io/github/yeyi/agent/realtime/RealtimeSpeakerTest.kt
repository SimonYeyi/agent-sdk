@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeSpeakerTest {

    private val fakeFormat = AudioFormat(24_000, AudioFormat.Encoding.PCM_16BIT)

    private class FakeSpeaker : SpeakerAdapter {
        val playedPcm = mutableListOf<ByteArray>()
        val playedSignal = Channel<ByteArray>(Channel.UNLIMITED)
        var stopPlaybackCount = 0
        var closed = false

        override suspend fun start(format: AudioFormat) {}
        override suspend fun play(pcm: ByteArray) {
            playedPcm.add(pcm)
            playedSignal.send(pcm)
        }
        override suspend fun stopPlayback() { stopPlaybackCount++ }
        override suspend fun close() { closed = true }
    }

    private suspend fun <T> await(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { block() }
        }

    @Test
    fun `start is idempotent and starts delegate`() = runTest {
        val delegate = FakeSpeaker()
        val speaker = RealtimeSpeaker(delegate) { null }

        speaker.start(fakeFormat)
        speaker.start(fakeFormat)

        assertTrue(!delegate.closed) // close not called yet
    }

    @Test
    fun `play when not userQuerying sends pcm to delegate`() = runTest {
        val delegate = FakeSpeaker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val speaker = RealtimeSpeaker(delegate) { scope }
        speaker.start(fakeFormat)

        await { speaker.play(byteArrayOf(1, 2, 3)) }
        await { delegate.playedSignal.receive() }

        assertTrue(delegate.playedPcm.last().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `play when userQuerying drops pcm`() = runTest {
        val delegate = FakeSpeaker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val speaker = RealtimeSpeaker(delegate) { scope }
        speaker.start(fakeFormat)

        await { speaker.observed(RealtimeEvent.UserTranscriptStarted("test")) }
        await { speaker.play(byteArrayOf(1, 2, 3)) }

        assertTrue(delegate.playedPcm.isEmpty())
    }

    @Test
    fun `observed UserTranscriptStarted stops playback and drains channel`() = runTest {
        val delegate = FakeSpeaker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val speaker = RealtimeSpeaker(delegate) { scope }
        speaker.start(fakeFormat)

        await { speaker.play(byteArrayOf(1)) }
        await { delegate.playedSignal.receive() }
        await { speaker.play(byteArrayOf(2)) }
        await { delegate.playedSignal.receive() }
        await { speaker.observed(RealtimeEvent.UserTranscriptStarted("test")) }

        assertEquals(1, delegate.stopPlaybackCount)
    }

    @Test
    fun `observed AssistantAudioStarted re-enables playback`() = runTest {
        val delegate = FakeSpeaker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val speaker = RealtimeSpeaker(delegate) { scope }
        speaker.start(fakeFormat)

        await { speaker.observed(RealtimeEvent.UserTranscriptStarted("test")) }
        await { speaker.observed(RealtimeEvent.AssistantAudioStarted) }
        await { speaker.play(byteArrayOf(3, 4)) }
        await { delegate.playedSignal.receive() }

        assertTrue(delegate.playedPcm.last().contentEquals(byteArrayOf(3, 4)))
    }

    @Test
    fun `observed AssistantAudioDelta plays pcm`() = runTest {
        val delegate = FakeSpeaker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val speaker = RealtimeSpeaker(delegate) { scope }
        speaker.start(fakeFormat)

        await { speaker.observed(RealtimeEvent.AssistantAudioDelta(byteArrayOf(5, 6))) }
        await { delegate.playedSignal.receive() }

        assertTrue(delegate.playedPcm.last().contentEquals(byteArrayOf(5, 6)))
    }

    @Test
    fun `observed AssistantAudioDelta while userQuerying drops pcm`() = runTest {
        val delegate = FakeSpeaker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val speaker = RealtimeSpeaker(delegate) { scope }
        speaker.start(fakeFormat)

        await { speaker.observed(RealtimeEvent.UserTranscriptStarted("test")) }
        await { speaker.observed(RealtimeEvent.AssistantAudioDelta(byteArrayOf(7))) }

        assertTrue(delegate.playedPcm.isEmpty())
    }

    @Test
    fun `stopPlayback delegates to delegate`() = runTest {
        val delegate = FakeSpeaker()
        val speaker = RealtimeSpeaker(delegate) { null }
        speaker.start(fakeFormat)

        speaker.stopPlayback()
        speaker.stopPlayback()

        assertEquals(2, delegate.stopPlaybackCount)
    }

    @Test
    fun `close closes channel and resets state`() = runTest {
        val delegate = FakeSpeaker()
        val speaker = RealtimeSpeaker(delegate) { null }
        speaker.start(fakeFormat)

        speaker.close()

        assertTrue(delegate.closed)
        // after close, channel is null, so play is no-op
        await { speaker.play(byteArrayOf(1)) }
        assertTrue(delegate.playedPcm.isEmpty())
    }

    @Test
    fun `close then start reinitializes`() = runTest {
        val delegate = FakeSpeaker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val speaker = RealtimeSpeaker(delegate) { scope }
        speaker.start(fakeFormat)
        speaker.close()

        speaker.start(fakeFormat)
        await { speaker.play(byteArrayOf(9, 9)) }
        await { delegate.playedSignal.receive() }

        assertTrue(delegate.playedPcm.last().contentEquals(byteArrayOf(9, 9)))
    }
}
