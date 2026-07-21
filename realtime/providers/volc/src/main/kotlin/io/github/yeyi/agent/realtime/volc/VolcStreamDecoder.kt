package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.ResponseStatus
import kotlinx.serialization.json.Json
import java.util.Base64

internal object VolcStreamDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(frame: String): List<RealtimeEvent> {
        val evt = json.decodeFromString(VolcEvent.serializer(), frame)
        return when (evt.type) {
            "conversation.item.input_audio_transcription.completed" ->
                evt.transcript?.let { listOf(RealtimeEvent.UserTranscriptCompleted(it)) } ?: emptyList()

            "response.output_text.delta" ->
                evt.delta?.let { listOf(RealtimeEvent.AssistantTextDelta(it)) } ?: emptyList()

            "response.output_audio.started" ->
                evt.itemId?.let { listOf(RealtimeEvent.AssistantAudioStarted(it)) } ?: emptyList()

            "response.output_audio.delta" -> {
                val id = evt.itemId ?: return emptyList()
                val pcm = evt.delta?.let { Base64.getDecoder().decode(it) } ?: return emptyList()
                listOf(RealtimeEvent.AssistantAudioDelta(id, pcm))
            }

            "response.output_audio.done" ->
                evt.itemId?.let { listOf(RealtimeEvent.AssistantAudioDone(it)) } ?: emptyList()

            "response.done" ->
                evt.responseId?.let { id ->
                    val status = when (evt.status) {
                        "completed" -> ResponseStatus.COMPLETED
                        "canceled" -> ResponseStatus.CANCELED
                        "failed" -> ResponseStatus.FAILED
                        else -> ResponseStatus.INCOMPLETE
                    }
                    listOf(RealtimeEvent.ResponseDone(id, status))
                } ?: emptyList()

            "session.created" ->
                evt.sessionId?.let { listOf(RealtimeEvent.Connected(it)) } ?: emptyList()

            "error" -> listOf(
                RealtimeEvent.Error(
                    code = evt.error?.code ?: "unknown",
                    message = evt.error?.message ?: "",
                    isFatal = false,
                )
            )

            else -> emptyList()
        }
    }
}