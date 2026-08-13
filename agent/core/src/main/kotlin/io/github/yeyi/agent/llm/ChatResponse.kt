package io.github.yeyi.agent.llm

/**
 * LLM 聊天响应数据
 *
 * @param message 模型返回的消息
 * @param usage token 消耗统计
 * @param finishReason 模型输出终止原因
 */
public data class ChatResponse(
    public val message: ChatMessage.Assistant,
    public val usage: Usage? = null,
    public val finishReason: FinishReason
)

/**
 * LLM 聊天流式响应事件
 */
public sealed interface ChatResponseEvent {
    /**
     * A fragment of incremental text from the model. Multiple ContentDelta events form the
     * streamed response; they should be concatenated in arrival order to form the final answer.
     */
    public data class ContentDelta(public val text: String) : ChatResponseEvent
    /**
     * Marks the start of a tool call. Emitted once per tool call id, before any [ToolCallDelta]
     * events for that id. The (id, name) pair identifies the call.
     */
    public data class ToolCallStart(public val id: String, public val name: String) : ChatResponseEvent
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
    ) : ChatResponseEvent
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
    ) : ChatResponseEvent
    /**
     * Terminal event indicating the stream failed (parse error, protocol violation, or upstream
     * transport failure). The `cause` is the underlying throwable. ReActAgent propagates this
     * by throwing `cause`, which terminates the consuming flow.
     */
    public data class Error(public val cause: Throwable) : ChatResponseEvent
}

/**
 * Token 使用量统计。
 *
 * @param promptTokens 输入侧消耗的 token 数
 * @param completionTokens 输出侧消耗的 token 数
 * @param totalTokens 总消耗 token 数
 */
public data class Usage(
    public val promptTokens: Int,
    public val completionTokens: Int,
    public val totalTokens: Int
)

/**
 * LLM 生成终止原因。
 *
 * @property Stop 正常停止（[ChatMessage.Assistant.content] 有内容）
 * @property ToolCalls LLM 决定调用工具（[ChatMessage.Assistant.toolCalls] 非空）
 * @property Length 达到 [ChatRequest.maxTokens] 上限被迫终止
 */
public enum class FinishReason { Stop, ToolCalls, Length }
