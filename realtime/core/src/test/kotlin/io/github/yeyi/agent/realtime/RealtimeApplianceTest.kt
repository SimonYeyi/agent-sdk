@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeApplianceTest {

    private class FakeMicrophone(
        private val captureFlow: Flow<ByteArray> = flowOf(ByteArray(0)),
    ) : MicrophoneAdapter {
        override val inputFormat = AudioFormat(
            sampleRateHz = 16_000,
            channels = 1,
            sampleBits = 16,
            encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
        )
        override fun capture() = captureFlow
        override suspend fun start() {}
        override suspend fun close() {}
    }

    private class FakeSpeaker : SpeakerAdapter {
        val played = Channel<ByteArray>(Channel.UNLIMITED)
        override val outputFormat = AudioFormat(
            sampleRateHz = 24_000,
            channels = 1,
            sampleBits = 16,
            encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
        )
        override suspend fun play(pcm: ByteArray) { played.send(pcm) }
        override suspend fun stopPlayback() {}
        override suspend fun start() {}
        override suspend fun close() {}
    }

    private class FakeSession : RealtimeSession {
        private val eventsChannel = Channel<RealtimeEvent>(Channel.UNLIMITED)
        val subscribedSignal = Channel<Unit>(Channel.CONFLATED)
        val sentAudio = mutableListOf<ByteArray>()
        val sentAudioSignal = Channel<ByteArray>(Channel.UNLIMITED)
        val injectedSignal = Channel<String>(Channel.UNLIMITED)
        var cancelledCount = 0
        var injectCount = 0
        override val events: Flow<RealtimeEvent>
            get() = eventsChannel.receiveAsFlow().onStart { subscribedSignal.trySend(Unit) }
        override suspend fun connect(config: SessionConfig) {}
        override fun close() { eventsChannel.close() }
        override suspend fun sendAudio(pcm: ByteArray) {
            sentAudio += pcm
            sentAudioSignal.send(pcm)
        }
        override suspend fun commitInput() {}
        override suspend fun cancelResponse() { cancelledCount++ }
        override suspend fun injectAndRespond(text: String) {
            injectCount++
            injectedSignal.send(text)
        }

        fun emit(event: RealtimeEvent) {
            eventsChannel.trySend(event)
        }
    }

    private class FakeDelegation(
        private val result: DelegationResult = DelegationResult.Success("stub"),
    ) : RealtimeDelegation {
        val calledSignal = Channel<String>(Channel.UNLIMITED)
        var lastInput: String? = null
        var callCount = 0
        override suspend fun run(asrText: String): DelegationResult {
            lastInput = asrText
            callCount++
            calledSignal.send(asrText)
            return result
        }
    }

    private fun sessionConfigFor(mic: FakeMicrophone, speaker: FakeSpeaker) = SessionConfig(
        apiKey = "k",
        endpoint = "wss://test",
        model = "m",
        instructions = "你是助手",
        voice = "v",
        inputFormat = mic.inputFormat,
        outputFormat = speaker.outputFormat,
    )

    private suspend fun awaitSubscribed(session: FakeSession) {
        realAwait { session.subscribedSignal.receive() }
    }

    private suspend fun <T> realAwait(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { block() }
        }

    @Test
    fun `chitchat path lets S2S audio through without invoking delegation`() = runTest {
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val delegation = FakeDelegation()

        val appliance = RealtimeAppliance(
            session = session,
            mic = mic,
            speaker = speaker,
            delegation = delegation,
            sessionConfig = sessionConfigFor(mic, speaker),
        )
        appliance.start()
        awaitSubscribed(session)

        session.emit(RealtimeEvent.UserTranscriptCompleted("今天天气真好"))
        session.emit(RealtimeEvent.AssistantTextDelta("是的, 阳光明媚"))
        session.emit(RealtimeEvent.AssistantAudioDelta("i1", byteArrayOf(9, 9)))
        session.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.COMPLETED))

        val first = realAwait { speaker.played.receive() }
        assertEquals(byteArrayOf(9, 9).toList(), first.toList())

        appliance.close()

        assertEquals(0, delegation.callCount)
        assertEquals(0, session.cancelledCount)
        assertEquals(0, session.injectCount)
    }

    @Test
    fun `delegate path cancels S2S and runs delegation`() = runTest {
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val delegation = FakeDelegation()

        val appliance = RealtimeAppliance(
            session = session,
            mic = mic,
            speaker = speaker,
            delegation = delegation,
            sessionConfig = sessionConfigFor(mic, speaker),
        )

        appliance.start()
        awaitSubscribed(session)

        session.emit(RealtimeEvent.UserTranscriptCompleted("帮我把客厅灯调暗到 30%"))
        session.emit(RealtimeEvent.AssistantTextDelta("<|TASK|>"))
        session.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.CANCELED))

        val called = realAwait { delegation.calledSignal.receive() }
        assertEquals("帮我把客厅灯调暗到 30%", called)

        appliance.close()

        assertTrue(session.cancelledCount >= 1)
    }

    @Test
    fun `delegation Success triggers injectAndRespond after S2S idle`() = runTest {
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val delegation = FakeDelegation(DelegationResult.Success("Boss 任务完成, 结果: stub"))

        val appliance = RealtimeAppliance(
            session = session,
            mic = mic,
            speaker = speaker,
            delegation = delegation,
            sessionConfig = sessionConfigFor(mic, speaker),
        )

        appliance.start()
        awaitSubscribed(session)

        session.emit(RealtimeEvent.UserTranscriptCompleted("帮我把灯调暗"))
        session.emit(RealtimeEvent.AssistantTextDelta("<|TASK|>"))
        session.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.CANCELED))

        realAwait { delegation.calledSignal.receive() }
        val injected = realAwait { session.injectedSignal.receive() }

        appliance.close()

        assertEquals("Boss 任务完成, 结果: stub", injected)
        assertTrue(session.injectCount >= 1)
    }

    @Test
    fun `mic capture forwards PCM to session sendAudio`() = runTest {
        val pcm1 = byteArrayOf(1, 2, 3)
        val pcm2 = byteArrayOf(4, 5)
        val session = FakeSession()
        val mic = FakeMicrophone(flowOf(pcm1, pcm2))
        val speaker = FakeSpeaker()
        val delegation = FakeDelegation()

        val appliance = RealtimeAppliance(
            session = session,
            mic = mic,
            speaker = speaker,
            delegation = delegation,
            sessionConfig = sessionConfigFor(mic, speaker),
        )

        appliance.start()

        val first = realAwait { session.sentAudioSignal.receive() }
        val second = realAwait { session.sentAudioSignal.receive() }

        appliance.close()

        assertEquals(pcm1.toList(), first.toList())
        assertEquals(pcm2.toList(), second.toList())
        assertEquals(2, session.sentAudio.size)
    }
}