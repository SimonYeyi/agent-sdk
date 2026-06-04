package io.github.yeyi.agent.core.llm

public sealed interface StreamEvent {
    public data class ContentDelta(public val text: String) : StreamEvent
    public data class ToolCallDelta(
        public val id: String?,
        public val name: String?,
        public val argumentsDelta: String
    ) : StreamEvent
    public data class Done(public val usage: Usage?) : StreamEvent
    public data class Error(public val cause: Throwable) : StreamEvent
}
