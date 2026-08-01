@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        var stopPlaybackCount = 0
        override suspend fun start(format: AudioFormat) {}
        override suspend fun play(pcm: ByteArray) { played.send(pcm) }
        override suspend fun stopPlayback() { stopPlaybackCount++ }
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
        val cancelResponseSignal = Channel<Unit>(Channel.UNLIMITED)
        var injectCount = 0
        var cancelResponseCount = 0
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
        override suspend fun commitAudio() {}
        override suspend fun cancelResponse() {
            cancelResponseCount++
            cancelResponseSignal.send(Unit)
            eventsChannel.trySend(RealtimeEvent.ResponseCanceled)
        }
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

    private class FakeDelegation : RealtimeDelegation {
        override val capabilities: List<String> = emptyList()
        private val updateEmitter = MutableSharedFlow<DelegationReply>(extraBufferCapacity = 16)
        override val replies: Flow<DelegationReply> = updateEmitter.asSharedFlow()
        val dispatched = Channel<String>(Channel.UNLIMITED)

        override suspend fun run(task: String) {
            dispatched.send(task)
        }

        fun emit(update: DelegationReply) {
            check(updateEmitter.tryEmit(update))
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

        assertEquals(0, session.injectCount)
    }

    @Test
    fun `user transcript start stops playback`() = kotlinx.coroutines.runBlocking {
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

        session.emit(RealtimeEvent.UserTranscriptStarted("item-1"))

        appliance.close()

        assertEquals(1, speaker.stopPlaybackCount)
    }

    @Test
    fun `barge in during tts drops audio deltas until response is canceled`() = runTest {
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

        // 旧 TTS 正在播放
        session.emit(RealtimeEvent.AssistantAudioStarted)
        session.emit(RealtimeEvent.AssistantAudioDelta(byteArrayOf(10)))
        val firstPlayed = realAwait { speaker.played.receive() }
        assertEquals(byteArrayOf(10).toList(), firstPlayed.toList())

        // 用户插话
        session.emit(RealtimeEvent.UserTranscriptStarted("item-1"))
        runCurrent()

        // 飞行中的尾巴 delta 应被丢弃
        session.emit(RealtimeEvent.AssistantAudioDelta(byteArrayOf(20)))
        runCurrent()
        assertNull(withTimeoutOrNull(200) { speaker.played.receive() })

        // 服务端确认 cancel
        session.emit(RealtimeEvent.ResponseCanceled)
        runCurrent()

        // 新一轮 TTS 应正常播放
        session.emit(RealtimeEvent.AssistantAudioStarted)
        session.emit(RealtimeEvent.AssistantAudioDelta(byteArrayOf(30)))
        val played = realAwait { speaker.played.receive() }
        assertEquals(byteArrayOf(30).toList(), played.toList())

        appliance.close()
    }

    @Test
    fun `delegate path runs delegation`() = runTest {
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
        session.emit(RealtimeEvent.AssistantTextDelta("|好的"))
        session.emit(RealtimeEvent.ResponseDone)

        val called = realAwait { delegation.dispatched.receive() }
        assertEquals("帮我把客厅灯调暗到 30%", called)

        appliance.close()
    }

    @Test
    fun `delegation updates are injected in stream order`() = runTest {
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

        delegation.emit(DelegationReply.Confirmation("正在处理"))
        delegation.emit(DelegationReply.Success("空调已打开"))
        delegation.emit(DelegationReply.Failure("缺少房间参数"))

        val injected = listOf(
            realAwait { session.injectedSignal.receive() },
            realAwait { session.injectedSignal.receive() },
            realAwait { session.injectedSignal.receive() },
        )

        appliance.close()

        assertEquals(listOf("正在处理", "空调已打开", "缺少房间参数"), injected)
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
        session.emit(RealtimeEvent.AssistantTextDelta("|好的"))
        session.emit(RealtimeEvent.AssistantAudioDelta(byteArrayOf(11, 12, 13)))
        session.emit(RealtimeEvent.ResponseDone)

        val first = realAwait { speaker.played.receive() }
        assertEquals(byteArrayOf(11, 12, 13).toList(), first.toList())

        appliance.close()

        // 协议没被注入到 instructions
        assertEquals(baseInstructions, session.connectInstructions)
        // 委托链完全没触发
        assertEquals(0, session.injectCount)
    }

    @Test
    fun `user speech before cancel response aborts inject`() = runTest {
        val session = FakeSession(fakeInputFormat, fakeOutputFormat)
        val delegation = object : RealtimeDelegation {
            override val capabilities: List<String> = emptyList()
            override val classifier: IntentionClassifier = object : IntentionClassifier {
                override suspend fun classify(asr: String) = Intention.Delegated("好的", "开空调")
            }
            override val replies: Flow<DelegationReply> = MutableSharedFlow()
            override suspend fun run(task: String) {}
        }
        val speaker = FakeSpeaker()
        val ackProcessed = Channel<Unit>(Channel.CONFLATED)

        val appliance = RealtimeAppliance(
            session = session,
            sessionConfig = makeSessionConfig(),
            microphone = FakeMicrophone(),
            speaker = speaker,
            delegation = delegation,
        )

        appliance.start()
        awaitSubscribed(session)

        // UserTranscriptStarted 先于 ResponseCanceled 到达
        session.emit(RealtimeEvent.UserTranscriptStarted("new query"))

        // 触发 classifier 路径：onReplacementAck 被调用，
        // 它等待 session.events，UserTranscriptStarted 会先于 cancelResponse 后的 ResponseCanceled 被收到
        session.emit(RealtimeEvent.UserTranscriptCompleted("开空调"))

        // 等待 cancelResponse 完成
        realAwait { session.cancelResponseSignal.receive() }
        // 等待一小段时间确保 abort 完成
        kotlinx.coroutines.delay(100)

        // UserTranscriptStarted 先被收到，injectAndRespond 不应被调用
        assertEquals(0, session.injectCount)
        assertEquals(1, session.cancelResponseCount)

        appliance.close()
    }
}
