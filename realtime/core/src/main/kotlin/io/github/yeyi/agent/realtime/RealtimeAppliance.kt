package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

public interface RealtimeAppliance {
    public val delegation: RealtimeDelegation?
    public val events: Flow<RealtimeEvent>
    public suspend fun start()
    public suspend fun close()
}

internal class DefaultRealtimeAppliance(
    private val session: RealtimeSession,
    private val sessionConfig: SessionConfig,
    private val microphone: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {
    private var scope: CoroutineScope? = null
    private var userQuerying: Boolean = false
    private var audioChannel: Channel<ByteArray>? = null

    private val delegationHandler: DelegationHandler? = delegation?.let { delegation ->
        DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = { text -> session.injectAndRespond(text) },
        )
    }

    override val events: Flow<RealtimeEvent> = MutableSharedFlow(extraBufferCapacity = 64)

    override suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        userQuerying = false
        try {
            val instructions = delegationHandler
                ?.appendInstructions(sessionConfig.instructions)
                ?: sessionConfig.instructions
            session.connect(sessionConfig.copy(instructions = instructions))
            microphone.start(session.inputAudioFormat)
            speaker.start(session.outputAudioFormat)
            scope?.launch {
                session.events.collect { event ->
                    val finalEvent = when {
                        delegationHandler == null -> event
                        else -> delegationHandler.handle(event) ?: return@collect
                    }
                    handleEvent(finalEvent)
                    (events as MutableSharedFlow).emit(finalEvent)
                }
            }
            scope?.launch {
                microphone.capture().collect { pcm -> session.sendAudio(pcm) }
            }

            audioChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED).also { channel ->
                scope?.launch {
                    for (pcm in channel) {
                        speaker.play(pcm)
                    }
                }
            }

            delegationHandler?.start()
        } catch (e: Throwable) {
            runCatching { close() }
            throw e
        }
    }

    override suspend fun close() {
        userQuerying = false
        audioChannel?.close()
        audioChannel = null
        scope?.coroutineContext[Job]?.cancelAndJoin()
        scope = null
        microphone.close()
        speaker.close()
        session.close()
    }

    private suspend fun handleEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptStarted -> {
                userQuerying = true
                drainAudioChannel()
                speaker.stopPlayback()
            }
            is RealtimeEvent.AssistantAudioStarted -> {
                userQuerying = false
            }
            is RealtimeEvent.AssistantAudioDelta if !userQuerying -> {
                audioChannel?.send(event.pcm)
            }
            else -> {}
        }
    }

    private fun drainAudioChannel() {
        while (audioChannel?.tryReceive()?.isSuccess == true) {
            // drop pending audio to discard the tail of the previous round's TTS
        }
    }
}

public fun RealtimeAppliance(
    session: RealtimeSession,
    sessionConfig: SessionConfig,
    microphone: MicrophoneAdapter,
    speaker: SpeakerAdapter,
    delegation: RealtimeDelegation? = null,
): RealtimeAppliance = DefaultRealtimeAppliance(
    session = session,
    sessionConfig = sessionConfig,
    microphone = microphone,
    speaker = speaker,
    delegation = delegation,
)
