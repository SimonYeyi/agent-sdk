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

    private val fakeInputFormat = AudioFormat(
        sampleRateHz = 16_000,
        encoding = AudioFormat.Encoding.PCM_16BIT,
    )

    private val fakeOutputFormat = AudioFormat(
        sampleRateHz = 24_000,
        encoding = AudioFormat.Encoding.PCM_16BIT,
    )

    private class FakeMicrophone(
        private val captureFlow: Flow<ByteArray> = flowOf(ByteArray(0)),
    ) : MicrophoneAdapter {
        override fun capture() = captureFlow
        override suspend fun start(format: AudioFormat) {}
        override suspend fun close() {}
    }

    private class FakeSpeaker : SpeakerAdapter {
        val played = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun start(format: AudioFormat) {}
        override suspend fun play(pcm: ByteArray) { played.send(pcm) }
        override suspend fun stopPlayback() {}
        override suspend fun close() {}
    }

    private class FakeSession(
        private val inputFormat: AudioFormat,
        private val outputFormat: AudioFormat,
    ) : RealtimeSession {
        private var eventsChannel = Channel<RealtimeEvent>(Channel.UNLIMITED)
        var subscribedSignal = Channel<Unit>(Channel.CONFLATED)
        val sentAudio = mutableListOf<ByteArray>()
        val sentAudioSignal = Channel<ByteArray>(Channel.UNLIMITED)
        val injectedSignal = Channel<String>(Channel.UNLIMITED)
        var cancelledCount = 0
        var injectCount = 0
        var connectCount = 0
        var closeCount = 0
        var connectInstructions: String? = null
        override val events: Flow<RealtimeEvent>
            get() = eventsChannel.receiveAsFlow().onStart { subscribedSignal.trySend(Unit) }
        override val inputAudioFormat: AudioFormat = inputFormat
        override val outputAudioFormat: AudioFormat = outputFormat
        override suspend fun connect(config: SessionConfig) {
            connectCount++
            connectInstructions = config.instructions
        }
        override fun close() {
            closeCount++
            eventsChannel.close()
        }
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

        fun resetChannelForReconnect() {
            eventsChannel = Channel(Channel.UNLIMITED)
            subscribedSignal = Channel(Channel.CONFLATED)
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

    private fun makeSessionConfig() = SessionConfig(
        apiKey = "k",
        endpoint = "wss://test",
        model = "m",
        instructions = "你是助手",
        voice = "v",
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
        val session = FakeSession(fakeInputFormat, fakeOutputFormat)
        val speaker = FakeSpeaker()
        val delegation = FakeDelegation()

        val appliance = RealtimeAppliance(
            session = session,
            sessionConfig = makeSessionConfig(),
            microphone = FakeMicrophone(),
            speaker = speaker,
            delegation = delegation,
        )
        appliance.start()
        awaitSubscribed(session)

        session.emit(RealtimeEvent.UserTranscriptCompleted("今天天气真好"))
        session.emit(RealtimeEvent.AssistantTextDelta("是的, 阳光明媚"))
        session.emit(RealtimeEvent.AssistantAudioDelta(byteArrayOf(9, 9)))
        session.emit(RealtimeEvent.ResponseDone)

        val first = realAwait { speaker.played.receive() }
        assertEquals(byteArrayOf(9, 9).toList(), first.toList())

        appliance.close()

        assertEquals(0, delegation.callCount)
        assertEquals(0, session.cancelledCount)
        assertEquals(0, session.injectCount)
    }

    @Test
    fun `delegate path cancels S2S and runs delegation`() = runTest {
        val session = FakeSession(fakeInputFormat, fakeOutputFormat)
        val delegation = FakeDelegation()

        val appliance = RealtimeAppliance(
            session = session,
            sessionConfig = makeSessionConfig(),
            microphone = FakeMicrophone(),
            speaker = FakeSpeaker(),
            delegation = delegation,
        )

        appliance.start()
        awaitSubscribed(session)

        session.emit(RealtimeEvent.UserTranscriptCompleted("帮我把客厅灯调暗到 30%"))
        session.emit(RealtimeEvent.AssistantTextDelta("<|TASK|>"))
        session.emit(RealtimeEvent.ResponseDone)

        val called = realAwait { delegation.calledSignal.receive() }
        assertEquals("帮我把客厅灯调暗到 30%", called)

        appliance.close()

        assertTrue(session.cancelledCount >= 1)
    }

    @Test
    fun `delegation Success triggers injectAndRespond after S2S idle`() = runTest {
        val session = FakeSession(fakeInputFormat, fakeOutputFormat)
        val delegation = FakeDelegation(DelegationResult.Success("Boss 任务完成, 结果: stub"))

        val appliance = RealtimeAppliance(
            session = session,
            sessionConfig = makeSessionConfig(),
            microphone = FakeMicrophone(),
            speaker = FakeSpeaker(),
            delegation = delegation,
        )

        appliance.start()
        awaitSubscribed(session)

        session.emit(RealtimeEvent.UserTranscriptCompleted("帮我把灯调暗"))
        session.emit(RealtimeEvent.AssistantTextDelta("<|TASK|>"))
        session.emit(RealtimeEvent.ResponseDone)

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
        val session = FakeSession(fakeInputFormat, fakeOutputFormat)

        val appliance = RealtimeAppliance(
            session = session,
            sessionConfig = makeSessionConfig(),
            microphone = FakeMicrophone(flowOf(pcm1, pcm2)),
            speaker = FakeSpeaker(),
            delegation = null,
        )

        appliance.start()

        val first = realAwait { session.sentAudioSignal.receive() }
        val second = realAwait { session.sentAudioSignal.receive() }

        appliance.close()

        assertEquals(pcm1.toList(), first.toList())
        assertEquals(pcm2.toList(), second.toList())
        assertEquals(2, session.sentAudio.size)
    }

    @Test
    fun `start is idempotent and second call is no-op`() = runTest {
        val session = FakeSession(fakeInputFormat, fakeOutputFormat)

        val appliance = RealtimeAppliance(
            session = session,
            sessionConfig = makeSessionConfig(),
            microphone = FakeMicrophone(),
            speaker = FakeSpeaker(),
            delegation = null,
        )

        appliance.start()
        appliance.start()

        assertEquals(1, session.connectCount)
        appliance.close()
    }

    @Test
    fun `close then start reconnects and processes events from the new session`() = kotlinx.coroutines.runBlocking {
        val session = FakeSession(fakeInputFormat, fakeOutputFormat)
        val speaker = FakeSpeaker()

        val appliance = RealtimeAppliance(
            session = session,
            sessionConfig = makeSessionConfig(),
            microphone = FakeMicrophone(),
            speaker = speaker,
            delegation = null,
        )

        appliance.start()
        awaitSubscribed(session)

        session.emit(RealtimeEvent.UserTranscriptCompleted("第一次"))
        appliance.close()

        assertEquals(1, session.connectCount)
        assertEquals(1, session.closeCount)

        session.resetChannelForReconnect()
        appliance.start()
        awaitSubscribed(session)

        assertEquals(2, session.connectCount)
        assertEquals(1, session.closeCount)

        session.emit(RealtimeEvent.AssistantTextDelta("hi"))
        session.emit(RealtimeEvent.AssistantAudioDelta(byteArrayOf(7, 7, 7)))
        val played = realAwait { speaker.played.receive() }
        assertEquals(byteArrayOf(7, 7, 7).toList(), played.toList())

        appliance.close()
        assertEquals(2, session.closeCount)
    }

    @Test
    fun `double close does not throw`() = runTest {
        val session = FakeSession(fakeInputFormat, fakeOutputFormat)

        val appliance = RealtimeAppliance(
            session = session,
            sessionConfig = makeSessionConfig(),
            microphone = FakeMicrophone(),
            speaker = FakeSpeaker(),
            delegation = null,
        )

        appliance.start()
        appliance.close()
        appliance.close()

        assertEquals(2, session.closeCount)
    }

    @Test
    fun `null delegation keeps instructions untouched and audio passes through`() = runTest {
        val session = FakeSession(fakeInputFormat, fakeOutputFormat)
        val speaker = FakeSpeaker()
        val baseInstructions = "你是助手"

        val appliance = RealtimeAppliance(
            session = session,
            sessionConfig = SessionConfig(
                apiKey = "k",
                endpoint = "wss://test",
                model = "m",
                instructions = baseInstructions,
                voice = "v",
            ),
            microphone = FakeMicrophone(),
            speaker = speaker,
            delegation = null,
        )

        appliance.start()
        awaitSubscribed(session)

        // 即使带了 marker 文本，因 gate 不存在，音频也应直通，无拦截
        session.emit(RealtimeEvent.UserTranscriptCompleted("查一下明天北京天气"))
        session.emit(RealtimeEvent.AssistantTextDelta("${DelegationHandler.DELEGATION_MARKER}好的"))
        session.emit(RealtimeEvent.AssistantAudioDelta(byteArrayOf(11, 12, 13)))
        session.emit(RealtimeEvent.ResponseDone)

        val first = realAwait { speaker.played.receive() }
        assertEquals(byteArrayOf(11, 12, 13).toList(), first.toList())

        appliance.close()

        // 协议没被注入到 instructions
        assertEquals(baseInstructions, session.connectInstructions)
        // 委托链完全没触发
        assertEquals(0, session.cancelledCount)
        assertEquals(0, session.injectCount)
    }
}
