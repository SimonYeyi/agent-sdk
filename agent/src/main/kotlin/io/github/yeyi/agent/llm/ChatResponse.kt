package io.github.yeyi.agent.llm

public data class Usage(
    public val promptTokens: Int,
    public val completionTokens: Int,
    public val totalTokens: Int
)

public enum class FinishReason { Stop, ToolCalls, Length }

public data class ChatResponse(
    public val message: ChatMessage.Assistant,
    public val usage: Usage? = null,
    public val finishReason: FinishReason
)
