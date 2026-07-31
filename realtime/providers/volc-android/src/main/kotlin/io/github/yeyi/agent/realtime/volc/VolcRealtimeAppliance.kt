package io.github.yeyi.agent.realtime.volc

import android.content.Context
import io.github.yeyi.agent.realtime.DelegationHandler
import io.github.yeyi.agent.realtime.RealtimeAppliance
import io.github.yeyi.agent.realtime.RealtimeDelegation
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.RealtimeSpeaker
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

public class VolcRealtimeAppliance(
    context: Context,
    private val sessionConfig: SessionConfig,
    speaker: SpeakerAdapter,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {
    private val applicationContext: Context = context.applicationContext
    private val protocolAdapter = VolcRealtimeAdapter(AudioFormat.Encoding.PCM_OPUS)
    private val session: VolcRealtimeSession = VolcRealtimeSession(applicationContext, protocolAdapter)
    private var scope: CoroutineScope? = null
    private val speaker: RealtimeSpeaker = RealtimeSpeaker(speaker, { scope })
    private val eventEmitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)

    private val delegationHandler: DelegationHandler? = delegation?.let {
        DelegationHandler(
            delegation = it,
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

    override val events: Flow<RealtimeEvent> = eventEmitter.asSharedFlow()

    override suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val instructions = delegationHandler
            ?.appendInstructions(sessionConfig.instructions)
            ?: sessionConfig.instructions

        session.connect(sessionConfig.copy(instructions = instructions))
        speaker.start(OUTPUT_AUDIO_FORMAT)

        scope?.launch {
            session.events.collect { event ->
                val finalEvent = when {
                    delegationHandler == null -> event
                    else -> delegationHandler.handle(event) ?: return@collect
                }
                speaker.observed(finalEvent)
                eventEmitter.emit(finalEvent)
            }
        }
        delegationHandler?.start()
    }

    override suspend fun close() {
        speaker.close()
        session.close()
        scope?.coroutineContext[Job]?.cancelAndJoin()
        scope = null
    }

    private companion object {
        private val OUTPUT_AUDIO_FORMAT = AudioFormat(
            sampleRateHz = 24_000,
            encoding = AudioFormat.Encoding.PCM_16BIT,
        )
    }
}
