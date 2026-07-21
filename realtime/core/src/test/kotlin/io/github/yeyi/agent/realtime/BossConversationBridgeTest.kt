@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.bossAgent
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

class BossConversationBridgeTest {

    private class FakeMicrophone : MicrophoneAdapter {
        override val inputFormat = AudioFormat(
            sampleRateHz = 16_000,
            channels = 1,
            sampleBits = 16,
            encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
        )
        override fun capture() = flowOf(ByteArray(0))
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
        var cancelledCount = 0
        var injectCount = 0
        override val events: Flow<RealtimeEvent> get() = eventsEmitter.asSharedFlow()
        override suspend fun connect(config: SessionConfig) {}
        override fun close() {}
        override suspend fun sendAudio(pcm: ByteArray) {}
        override suspend fun commitInput() {}
        override suspend fun cancelResponse() { cancelledCount++ }
        override suspend fun injectAndRespond(text: String) { injectCount++ }
    }

    private fun stubBoss(): BossAgent = bossAgent {
        llmProvider(
            FakeLlmProvider(
                nonStreamResponses = listOf(
                    ChatResponse(
                        message = ChatMessage.Assistant(content = "stub"),
                        finishReason = FinishReason.Stop,
                    )
                )
            )
        )
        memory(InMemoryMemory(), 20)
        maxIterations(1)
    }

    @Test
    fun `chitchat path lets S2S audio through without invoking Boss`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val boss = stubBoss()

        val bridge = BossConversationBridge(
            session = session,
            mic = mic,
            speaker = speaker,
            boss = boss,
            config = BridgeConfig(),
            scope = scope,
        )
        bridge.start()
        // Let the collector register before emitting; otherwise the test
        // SharedFlow (replay=0) drops events sent before subscription.
        advanceUntilIdle()

        session.eventsEmitter.emit(RealtimeEvent.UserTranscriptCompleted("今天天气真好"))
        session.eventsEmitter.emit(RealtimeEvent.AssistantTextDelta("是的, 阳光明媚"))
        session.eventsEmitter.emit(RealtimeEvent.AssistantAudioDelta("i1", byteArrayOf(9, 9)))
        session.eventsEmitter.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.COMPLETED))

        advanceUntilIdle()

        assertEquals(1, speaker.played.size)
        assertEquals(0, session.cancelledCount)
        assertEquals(0, session.injectCount)

        bridge.close()
        boss.shutdown()
        scope.cancel()
    }

    @Test
    fun `delegate path cancels S2S and runs Boss`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val boss = stubBoss()

        val bridge = BossConversationBridge(
            session = session,
            mic = mic,
            speaker = speaker,
            boss = boss,
            config = BridgeConfig(),
            scope = scope,
        )

        bridge.start()
        // Let the collector register before emitting; otherwise the test
        // SharedFlow (replay=0) drops events sent before subscription.
        advanceUntilIdle()

        session.eventsEmitter.emit(RealtimeEvent.UserTranscriptCompleted("帮我把客厅灯调暗到 30%"))
        session.eventsEmitter.emit(RealtimeEvent.AssistantTextDelta("<|DELEGATE_TO_BOSS|>"))
        session.eventsEmitter.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.CANCELED))

        advanceUntilIdle()

        assertTrue(session.cancelledCount >= 1)
        // Boss 完成后应 injectAndRespond（Task 9 校验）

        bridge.close()
        boss.shutdown()
        scope.cancel()
    }
}