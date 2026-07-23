package io.github.yeyi.agent.realtime

sealed interface RealtimeEvent {
    data class UserTranscriptStarted(val itemId: String) : RealtimeEvent
    data class UserTranscriptDelta(val text: String) : RealtimeEvent
    data class UserTranscriptCompleted(val text: String) : RealtimeEvent

    data class AssistantTextDelta(val text: String) : RealtimeEvent

    object AssistantAudioStarted : RealtimeEvent
    data class AssistantAudioDelta(val pcm: ByteArray) : RealtimeEvent
    object AssistantAudioDone : RealtimeEvent

    object ResponseDone : RealtimeEvent
    object ResponseCanceled : RealtimeEvent

    data class Connected(val sessionId: String) : RealtimeEvent
    data class Disconnected(val reason: String?) : RealtimeEvent
    data class Error(val code: String, val message: String, val isFatal: Boolean) : RealtimeEvent
}
