package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityAdapter

/**
 * 创建一个最简的 DefaultSubagent。
 *
 * @param name subagent 路由名
 * @param description 给 LLM 看的简介
 * @param instructions subagent 的 persona 文本(系统提示词)
 * @param memoryStrategy 内存策略，默认 Isolated
 * @param sharedMemory Shared 模式下预创建的 memory（可空，默认新建一个 InMemoryMemory）
 */
public fun subagent(
    name: String,
    description: String,
    instructions: String,
    memoryStrategy: MemoryStrategy = MemoryStrategy.Isolated,
    maxIterations: Int = 5,
): Subagent = ReActSubagent(
    name = name,
    description = description,
    instructions = instructions,
    sharedMemory = null,
    maxIterations = maxIterations,
)

/**
 * 把已有 registry 挂到 AgentBuilder。
 */
public fun AgentBuilder.subagents(
    registry: SubagentRegistry,
    mode: CapabilityAdapter.Mode = CapabilityAdapter.Mode.Delegate,
) {
    CapabilityAdapter.of(
        registry,
        SubagentContextFactory(),
        SubagentArguments(),
        mode
    ).installOn(this)
}
