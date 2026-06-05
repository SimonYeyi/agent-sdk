package io.github.yeyi.agent.llm

public data class ChatRequest(
    public val messages: List<ChatMessage>,
    public val tools: List<ToolDefinition> = emptyList(),
    public val temperature: Double? = null,
    public val maxTokens: Int? = null,
    public val stopSequences: List<String> = emptyList()
)
