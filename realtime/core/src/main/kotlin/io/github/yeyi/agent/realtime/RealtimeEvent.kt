package io.github.yeyi.agent.realtime

public sealed interface RealtimeEvent {
    public data class UserTranscriptStarted(val itemId: String) : RealtimeEvent
    public data class UserTranscriptDelta(val text: String) : RealtimeEvent
    public data class UserTranscriptCompleted(val text: String) : RealtimeEvent

    public data class AssistantTextDelta(val text: String) : RealtimeEvent

    public object AssistantAudioStarted : RealtimeEvent
    public data class AssistantAudioDelta(val pcm: ByteArray) : RealtimeEvent
    public object AssistantAudioDone : RealtimeEvent

    public object ResponseDone : RealtimeEvent
    public object ResponseCanceled : RealtimeEvent

    public data class Connected(val sessionId: String) : RealtimeEvent
    public data class Disconnected(val reason: String?) : RealtimeEvent
    public data class Error(val code: String, val message: String, val isFatal: Boolean) : RealtimeEvent
}
