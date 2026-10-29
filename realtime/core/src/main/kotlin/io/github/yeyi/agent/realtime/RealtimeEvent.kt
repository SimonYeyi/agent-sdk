package io.github.yeyi.agent.realtime

public sealed interface RealtimeEvent {
    public data class UserTranscriptStarted(val itemId: String) : RealtimeEvent
    public data class UserTranscriptDelta(val text: String) : RealtimeEvent
    public data class UserTranscriptCompleted(val text: String) : RealtimeEvent

    public data class AssistantTextDelta(val text: String) : RealtimeEvent
    public data class AssistantTextDone(val text: String) : RealtimeEvent

    public object AssistantAudioStarted : RealtimeEvent
    public data class AssistantAudioDelta(val pcm: ByteArray) : RealtimeEvent
    public object AssistantAudioDone : RealtimeEvent

    public object ResponseDone : RealtimeEvent
    public object ResponseCanceled : RealtimeEvent

    /**
     * 会话建立后检索到的历史上下文条目列表。
     *
     * 协议侧 [RealtimeAdapter.retrieveConversationHistoryFrame] 不带对话 id 时，服务端返回最近
     * 20 轮完整上下文；adapter 负责把协议条目收敛为 UI 渲染所需的角色与文本。
     */
    public data class ConversationHistoryRetrieved(val items: List<ConversationItem>) : RealtimeEvent

    public data class Connected(val sessionId: String) : RealtimeEvent
    public data class Disconnected(val reason: String?) : RealtimeEvent
    public data class Error(val code: String, val message: String, val isFatal: Boolean) : RealtimeEvent
}

/** 单条历史上下文条目：仅暴露角色与正文 */
public data class ConversationItem(
    val id: String? = null,
    val role: String? = null,
    val text: String? = null,
)
