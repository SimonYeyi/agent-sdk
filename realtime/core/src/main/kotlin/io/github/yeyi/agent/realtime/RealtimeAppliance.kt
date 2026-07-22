package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RealtimeAppliance(
    private val session: RealtimeSession,
    private val mic: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    private val delegation: RealtimeDelegation,
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

    private suspend fun handleEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptCompleted ->
                gate.onUserTranscriptCompleted(event.text)

            is RealtimeEvent.AssistantTextDelta ->
                gate.onTextDelta(event.text)

            is RealtimeEvent.AssistantAudioDelta ->
                gate.onAudioDelta(event.pcm)

            is RealtimeEvent.AssistantAudioDone,
            is RealtimeEvent.ResponseDone -> gate.onTurnEnd()

            else -> Unit
        }
    }

    private suspend fun runDelegation(asrText: String) {
        session.cancelResponse()
        val result = delegation.run(asrText)
        val text = renderResult(result)
        session.injectAndRespond(text)
    }

    private fun renderResult(result: DelegationResult): String = when (result) {
        is DelegationResult.Success -> result.text
        is DelegationResult.Failure -> result.message
    }
}
