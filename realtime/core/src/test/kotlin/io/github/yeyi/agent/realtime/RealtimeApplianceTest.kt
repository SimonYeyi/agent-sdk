@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        val played = mutableListOf<ByteArray>()
        override val outputFormat = AudioFormat(
            sampleRateHz = 24_000,
            channels = 1,
            sampleBits = 16,
            encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
        )
        override suspend fun play(pcm: ByteArray) { played += pcm }
        override suspend fun stopPlayback() {}
        override suspend fun start() {}
        override suspend fun close() {}
    }

    private class FakeSession : RealtimeSession {
        val eventsEmitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
        val sentAudio = mutableListOf<ByteArray>()
        var cancelledCount = 0
        var injectCount = 0
        override val events: Flow<RealtimeEvent> get() = eventsEmitter.asSharedFlow()
        override suspend fun connect(config: SessionConfig) {}
        override fun close() {}
        override suspend fun sendAudio(pcm: ByteArray) { sentAudio += pcm }
        override suspend fun commitInput() {}
        override suspend fun cancelResponse() { cancelledCount++ }
        override suspend fun injectAndRespond(text: String) { injectCount++ }
    }

    private class FakeDelegation(
        private val result: DelegationResult = DelegationResult.Success("stub"),
    ) : RealtimeDelegation {
        var lastInput: String? = null
        var callCount = 0
        override suspend fun run(asrText: String): DelegationResult {
            lastInput = asrText
            callCount++
            return result
        }
    }

    @Test
    fun `chitchat path lets S2S audio through without invoking delegation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val delegation = FakeDelegation()

        val appliance = RealtimeAppliance(
            session = session,
            mic = mic,
            speaker = speaker,
            delegation = delegation,
            sessionConfig = SessionConfig(
                apiKey = "k",
                endpoint = "wss://test",
                model = "m",
                instructions = "你是助手",
                voice = "v",
                inputFormat = mic.inputFormat,
                outputFormat = speaker.outputFormat,
            ),
            scope = scope,
        )
        appliance.start()
        // Let the collector register before emitting; otherwise the test
        // SharedFlow (replay=0) drops events sent before subscription.
        advanceUntilIdle()

        session.eventsEmitter.emit(RealtimeEvent.UserTranscriptCompleted("今天天气真好"))
        session.eventsEmitter.emit(RealtimeEvent.AssistantTextDelta("是的, 阳光明媚"))
        session.eventsEmitter.emit(RealtimeEvent.AssistantAudioDelta("i1", byteArrayOf(9, 9)))
        session.eventsEmitter.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.COMPLETED))

        advanceUntilIdle()

        assertEquals(1, speaker.played.size)
        assertEquals(0, delegation.callCount)
        assertEquals(0, session.cancelledCount)
        assertEquals(0, session.injectCount)

        appliance.close()
        scope.cancel()
    }

    @Test
    fun `delegate path cancels S2S and runs delegation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val delegation = FakeDelegation()

        val appliance = RealtimeAppliance(
            session = session,
            mic = mic,
            speaker = speaker,
            delegation = delegation,
            sessionConfig = SessionConfig(
                apiKey = "k",
                endpoint = "wss://test",
                model = "m",
                instructions = "你是助手",
                voice = "v",
                inputFormat = mic.inputFormat,
                outputFormat = speaker.outputFormat,
            ),
            scope = scope,
        )

        appliance.start()
        advanceUntilIdle()

        session.eventsEmitter.emit(RealtimeEvent.UserTranscriptCompleted("帮我把客厅灯调暗到 30%"))
        session.eventsEmitter.emit(RealtimeEvent.AssistantTextDelta("<|TASK|>"))
        session.eventsEmitter.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.CANCELED))

        advanceUntilIdle()

        assertTrue(session.cancelledCount >= 1)
        assertEquals("帮我把客厅灯调暗到 30%", delegation.lastInput)

        appliance.close()
        scope.cancel()
    }

    @Test
    fun `delegation Success triggers injectAndRespond after S2S idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val delegation = FakeDelegation(DelegationResult.Success("Boss 任务完成, 结果: stub"))

        val appliance = RealtimeAppliance(
            session = session,
            mic = mic,
            speaker = speaker,
            delegation = delegation,
            sessionConfig = SessionConfig(
                apiKey = "k",
                endpoint = "wss://test",
                model = "m",
                instructions = "你是助手",
                voice = "v",
                inputFormat = mic.inputFormat,
                outputFormat = speaker.outputFormat,
            ),
            scope = scope,
        )

        appliance.start()
        advanceUntilIdle()

        session.eventsEmitter.emit(RealtimeEvent.UserTranscriptCompleted("帮我把灯调暗"))
        session.eventsEmitter.emit(RealtimeEvent.AssistantTextDelta("<|TASK|>"))
        session.eventsEmitter.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.CANCELED))

        advanceUntilIdle()

        assertTrue(session.injectCount >= 1)

        appliance.close()
        scope.cancel()
    }

    @Test
    fun `mic capture forwards PCM to session sendAudio`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
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
            sessionConfig = SessionConfig(
                apiKey = "k",
                endpoint = "wss://test",
                model = "m",
                instructions = "你是助手",
                voice = "v",
                inputFormat = mic.inputFormat,
                outputFormat = speaker.outputFormat,
            ),
            scope = scope,
        )

        appliance.start()
        advanceUntilIdle()

        assertEquals(2, session.sentAudio.size)
        assertEquals(pcm1.toList(), session.sentAudio[0].toList())
        assertEquals(pcm2.toList(), session.sentAudio[1].toList())

        appliance.close()
        scope.cancel()
    }
}
