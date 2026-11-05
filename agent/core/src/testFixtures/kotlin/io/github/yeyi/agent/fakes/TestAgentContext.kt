package io.github.yeyi.agent.fakes

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool

/**
 * Test-only factory for [AgentContext].
 *
 * 统一提供合理默认值（agentId = "test"），测试只需覆盖关心的字段，后续字段增减只需改这一处。
 */
fun testAgentContext(
    agentId: String = "test",
    persona: AgentPersona = AgentPersona(""),
    maxIterations: Int = 1,
    currentIteration: Int = 1,
    memory: Memory = InMemoryMemory(),
    llmProvider: LlmProvider = FakeLlmProvider(),
    tools: List<Tool> = emptyList(),
    maxRounds: Int = 20,
    metadata: MutableMap<String, String> = mutableMapOf(),
): AgentContext = AgentContext(
    agentId = agentId,
    persona = persona,
    maxIterations = maxIterations,
    currentIteration = currentIteration,
    memory = memory,
    llmProvider = llmProvider,
    tools = tools,
    maxRounds = maxRounds,
    metadata = metadata,
)
