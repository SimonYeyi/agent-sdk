package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import io.github.yeyi.agent.team.BossAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class BossConversationBridge internal constructor(
    private val session: RealtimeSession,
    private val mic: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    private val boss: BossAgent,
    private val config: BridgeConfig = BridgeConfig(),
    private val scope: CoroutineScope,
) : AutoCloseable {

    private val s2sResponseDone = Channel<Unit>(capacity = Channel.CONFLATED)

    private val gate = AssistantAudioGate(
        speaker = speaker,
        onDelegate = { asrText -> scope.launch { runDelegation(asrText) } },
    )
    private var eventsJob: Job? = null

    suspend fun start() {
        mic.start()
        speaker.start()
        eventsJob = scope.launch {
            session.events.collect { event -> handleEvent(event) }
        }
    }

    override fun close() {
        eventsJob?.cancel()
        scope.launch { mic.close() }
        scope.launch { speaker.close() }
        session.close()
    }

    private suspend fun handleEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptCompleted ->
                gate.onUserTranscriptCompleted(event.text)
            is RealtimeEvent.AssistantTextDelta ->
                gate.onTextDelta(event.text)
            is RealtimeEvent.AssistantAudioDelta ->
                gate.onAudioDelta(event.pcm)
            is RealtimeEvent.AssistantAudioDone,
            is RealtimeEvent.ResponseDone -> {
                gate.onTurnEnd()
                if (event is RealtimeEvent.ResponseDone) s2sResponseDone.trySend(Unit)
            }
            else -> Unit
        }
    }

    private suspend fun runDelegation(asrText: String) {
        session.cancelResponse()

        var bossResultText: String? = null
        var bossFailed: Throwable? = null
        boss.run(asrText).collect { event ->
            when (event) {
                is AgentEvent.Final -> bossResultText = event.result.message.content
                is AgentEvent.Failed -> bossFailed = event.cause
                else -> Unit
            }
        }

        s2sResponseDone.receive()

        val text = bossResultText?.let { "Boss 任务完成, 结果: $it" }
            ?: bossFailed?.let { "抱歉, 任务执行失败: ${it.message ?: "未知错误"}" }
            ?: "抱歉, 任务未返回结果"
        session.injectAndRespond(text)
    }
}