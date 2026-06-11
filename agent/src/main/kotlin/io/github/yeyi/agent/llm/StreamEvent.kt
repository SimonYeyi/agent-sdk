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
     * A fragment of a tool call's arguments JSON. Emitted one or more times per tool call,
     * after the corresponding [ToolCallStart]. The `id` MUST be non-null (providers fill it
     * on continuation chunks — see [LlmProvider] contract). The `name` is non-null on the first
     * delta for a given id and may be null on continuation chunks. Concatenate the
     * `argumentsDelta` values in order to reconstruct the full arguments JSON.
     */
    public data class ToolCallDelta(
        public val id: String?,
        public val name: String?,
        public val argumentsDelta: String
    ) : StreamEvent
    /**
     * Terminal event indicating the stream completed successfully. Carries the final usage
     * statistics and the finish reason. `usage` is nullable (some providers do not expose
     * token counts); `finishReason` is non-null — providers MUST map upstream values to one
     * of the [FinishReason] variants. Unknown or missing upstream finish signals become
     * [FinishReason.Stop] (a normal completion) so consumers can rely on a non-null value
     * inside a terminal event.
     */
    public data class Done(
        public val usage: Usage?,
        public val finishReason: FinishReason,
    ) : StreamEvent
    /**
     * Terminal event indicating the stream failed (parse error, protocol violation, or upstream
     * transport failure). The `cause` is the underlying throwable. ReActAgent propagates this
     * by throwing `cause`, which terminates the consuming flow.
     */
    public data class Error(public val cause: Throwable) : StreamEvent
}
