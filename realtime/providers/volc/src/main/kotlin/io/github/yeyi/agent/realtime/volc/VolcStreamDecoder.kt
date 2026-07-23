package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import java.util.Base64

/**
 * Volc 协议事件解码器 — 提供 [VolcEvent] -> [RealtimeEvent] 适配.
 *
 * [toRealtimeEvents]: [VolcEvent] -> List<[RealtimeEvent]>. 由 [VolcRealtimeSession] 的
 * adaptor 协程在订阅 [VolcWireProtocol.events] 时调用, 做 SDK 编排层适配.
 */
internal object VolcStreamDecoder {

    fun toRealtimeEvents(evt: VolcEvent): List<RealtimeEvent> = when (evt.type) {
        "session.created" ->
            evt.session?.id?.let { listOf(RealtimeEvent.Connected(it)) } ?: emptyList()

        "session.updated" ->
            evt.session?.id?.let { listOf(RealtimeEvent.Connected(it)) } ?: emptyList()

        "session.closed" ->
            listOf(RealtimeEvent.Disconnected("session closed"))

        "input_audio_buffer.committed" -> emptyList()

        "conversation.item.input_audio_transcription.started" ->
            evt.itemId?.let { listOf(RealtimeEvent.UserTranscriptStarted(it)) } ?: emptyList()

        "conversation.item.input_audio_transcription.delta" ->
            evt.delta?.let { listOf(RealtimeEvent.UserTranscriptDelta(it)) } ?: emptyList()

        "conversation.item.input_audio_transcription.completed" ->
            evt.text?.let { listOf(RealtimeEvent.UserTranscriptCompleted(it)) } ?: emptyList()

        "conversation.item.input_audio_transcription.failed" ->
            listOf(
                RealtimeEvent.Error(
                    code = "transcription_failed",
                    message = evt.error?.message ?: "",
                    isFatal = false,
                )
            )

        "response.output_text.delta" ->
            evt.delta?.let { listOf(RealtimeEvent.AssistantTextDelta(it)) } ?: emptyList()

        "response.output_text.done" -> emptyList()

        "response.output_audio.started" ->
            listOf(RealtimeEvent.AssistantAudioStarted)

        "response.output_audio.delta" -> {
            val pcm = evt.delta?.let { Base64.getDecoder().decode(it) } ?: return emptyList()
            listOf(RealtimeEvent.AssistantAudioDelta(pcm))
        }

        "response.output_audio.done" ->
            listOf(RealtimeEvent.AssistantAudioDone)

        "response.canceled" ->
            listOf(RealtimeEvent.ResponseCanceled)

        "response.done" -> {
            listOf(RealtimeEvent.ResponseDone)
        }

        "response.function_call_arguments.done" -> emptyList()

        "conversation.item.added",
        "conversation.item.retrieved",
        "conversation.item.updated",
        "conversation.item.deleted" -> emptyList()

        "error" -> listOf(
            RealtimeEvent.Error(
                code = evt.error?.code ?: "unknown",
                message = evt.error?.message ?: "unknown",
                isFatal = false,
            )
        )

        else -> emptyList()
    }
}