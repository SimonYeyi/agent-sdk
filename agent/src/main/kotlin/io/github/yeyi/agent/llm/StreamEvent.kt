package io.github.yeyi.agent.llm

public sealed interface StreamEvent {
    /**
     * A fragment of incremental text from the model. Multiple ContentDelta events form the
     * streamed response; they should be concatenated in arrival order to form the final answer.
     */
    public data class ContentDelta(public val text: String) : StreamEvent
    /**
     * Marks the start of a tool call. Emitted once per tool call id, before any [ToolCallDelta]
     * events for that id. The (id, name) pair identifies the call.
     */
    public data class ToolCallStart(public val id: String, public val name: String) : StreamEvent
    /**
     * A fragment of a tool call's arguments JSON. Continuation chunks may have null id and name
     * (they refer to the most recent unconsumed [ToolCallStart]). Concatenate the argumentsDelta
     * values in order to reconstruct the full arguments JSON.
     */
    public data class ToolCallDelta(
        public val id: String?,
        public val name: String?,
        public val argumentsDelta: String
    ) : StreamEvent
    /**
     * Terminal event indicating the stream completed successfully. Carries the final usage
     * statistics and finish reason if the protocol exposes them (both fields are best-effort;
     * may be null for older or restricted APIs).
     */
    public data class Done(
        public val usage: Usage?,
        public val finishReason: FinishReason? = null,
    ) : StreamEvent
    /**
     * Terminal event indicating the stream failed (parse error, protocol violation, or upstream
     * transport failure). The `cause` is the underlying throwable. ReActAgent propagates this
     * by throwing `cause`, which terminates the consuming flow.
     */
    public data class Error(public val cause: Throwable) : StreamEvent
}
