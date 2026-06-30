package io.github.yeyi.agent.llm

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

public data class ChatResponse(
    public val message: ChatMessage.Assistant,
    public val usage: Usage? = null,
    public val finishReason: FinishReason
)
