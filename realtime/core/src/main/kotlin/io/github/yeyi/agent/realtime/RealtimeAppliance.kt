package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

public interface RealtimeAppliance {
    public val delegation: RealtimeDelegation?
    public val events: Flow<RealtimeEvent>
    public suspend fun start()
    public suspend fun close()
}

private class DefaultRealtimeAppliance(
    private val session: RealtimeSession,
    private val sessionConfig: SessionConfig,
    private val microphone: MicrophoneAdapter,
    speaker: SpeakerAdapter,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {
    private var scope: CoroutineScope? = null
    private val speaker: RealtimeSpeaker = RealtimeSpeaker(speaker, { scope })

    private val delegationProcessor: DelegationProcessor? = delegation?.let { delegation ->
        DelegationProcessor(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = { text -> session.injectAndRespond(text) },
            onReplacementAck = { ack ->
                session.cancelResponse()
                session.events
                    .filter { it is RealtimeEvent.ResponseDone || it is RealtimeEvent.ResponseCanceled || it is RealtimeEvent.Error }
                    .first()
                session.injectAndRespond(ack)
            },
        )
    }

    override val events: Flow<RealtimeEvent> = MutableSharedFlow(extraBufferCapacity = 64)

    override suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val instructions = delegationProcessor
                ?.appendInstructions(sessionConfig.instructions)
                ?: sessionConfig.instructions
            session.connect(sessionConfig.copy(instructions = instructions))
            speaker.start(session.outputAudioFormat)
            microphone.start(session.inputAudioFormat)
            scope?.launch {
                microphone.capture().collect { pcm -> session.sendAudio(pcm) }
            }
            scope?.launch {
                session.events.collect { event ->
                    val finalEvent = when {
                        delegationProcessor == null -> event
                        else -> delegationProcessor.process(event) ?: return@collect
                    }
                    speaker.observed(finalEvent)
                    (events as MutableSharedFlow).emit(finalEvent)
                }
            }
            delegationProcessor?.start()
        } catch (e: Throwable) {
            runCatching { close() }
            throw e
        }
    }

    override suspend fun close() {
        microphone.close()
        speaker.close()
        session.close()
        scope?.coroutineContext[Job]?.cancelAndJoin()
        scope = null
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
