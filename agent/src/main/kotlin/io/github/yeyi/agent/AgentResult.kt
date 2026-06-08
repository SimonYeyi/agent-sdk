package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage

public data class AgentResult(
    public val finalMessage: ChatMessage.Assistant,
    public val iterations: Int,
    public val toolCalls: List<ToolCallRecord>
)
