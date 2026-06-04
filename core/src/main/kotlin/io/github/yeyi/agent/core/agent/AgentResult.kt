package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.memory.Memory

public data class AgentResult(
    public val finalMessage: ChatMessage.Assistant,
    public val memory: Memory,
    public val iterations: Int,
    public val toolCalls: List<ToolCallRecord>
)
