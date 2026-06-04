package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.llm.LlmClient
import io.github.yeyi.agent.core.memory.Memory
import io.github.yeyi.agent.core.tool.Tool

public data class AgentConfig(
    public val systemPrompt: String,
    public val llmClient: LlmClient,
    public val tools: List<Tool>,
    public val memoryFactory: () -> Memory,
    public val maxIterations: Int,
    public val hooks: List<AgentHook> = emptyList()
)
