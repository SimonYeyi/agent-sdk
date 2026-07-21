package io.github.yeyi.agent.realtime

sealed interface RealtimeEvent {
    data class UserTranscriptStarted(val itemId: String) : RealtimeEvent
    data class UserTranscriptDelta(val text: String) : RealtimeEvent
    data class UserTranscriptCompleted(val text: String) : RealtimeEvent

    data class AssistantTextDelta(val text: String) : RealtimeEvent

    data class AssistantAudioStarted(val itemId: String) : RealtimeEvent
    data class AssistantAudioDelta(val itemId: String, val pcm: ByteArray) : RealtimeEvent
    data class AssistantAudioDone(val itemId: String) : RealtimeEvent

    data class ResponseStarted(val responseId: String) : RealtimeEvent
    data class ResponseDone(val responseId: String, val status: ResponseStatus) : RealtimeEvent

    data class Connected(val sessionId: String) : RealtimeEvent
    data class Disconnected(val reason: String?) : RealtimeEvent
    data class Error(val code: String, val message: String, val isFatal: Boolean) : RealtimeEvent
}

enum class ResponseStatus { COMPLETED, CANCELED, FAILED, INCOMPLETE }