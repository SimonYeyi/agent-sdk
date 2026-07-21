package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import io.github.yeyi.agent.team.BossAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BossConversationBridge internal constructor(
    private val session: RealtimeSession,
    private val mic: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    private val boss: BossAgent,
    private val config: BridgeConfig = BridgeConfig(),
    private val scope: CoroutineScope,
) : AutoCloseable {

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

    private fun handleEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptCompleted ->
                gate.onUserTranscriptCompleted(event.text)
            is RealtimeEvent.AssistantTextDelta ->
                scope.launch { gate.onTextDelta(event.text) }
            is RealtimeEvent.AssistantAudioDelta ->
                scope.launch { gate.onAudioDelta(event.pcm) }
            is RealtimeEvent.AssistantAudioDone,
            is RealtimeEvent.ResponseDone ->
                gate.onTurnEnd()
            else -> Unit
        }
    }

    private suspend fun runDelegation(asrText: String) {
        session.cancelResponse()
        // 委派路径实现见 Task 8
    }
}