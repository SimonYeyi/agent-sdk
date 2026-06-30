package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool

private class DefaultSubagent(
    override val name: String,
    override val description: String,
    val instruction: String,
    override val maxIterations: Int?,
    override val memory: Memory?,
    override val tools: List<Tool>?
) : Subagent {
    override fun load(context: SubagentContext): String = instruction
}

/**
 * Subagent 工厂函数。
 *
 * @param name 子 agent 唯一名称，供 LLM 识别
 * @param description 自然语言描述，告诉 LLM 何时应该委托给此子 agent
 * @param instruction 子 agent 的人设指令文本，作为其 [io.github.yeyi.agent.Persona] 的 role
 * @param maxIterations 最大迭代次数，null 继承父 agent 配置
 * @param memory 使用的 Memory，null 默认 [io.github.yeyi.agent.memory.InMemoryMemory]
 * @param tools 可用工具列表，null 继承父 agent 所有工具
 */
public fun subagent(
    name: String,
    description: String,
    instruction: String = description,
    maxIterations: Int? = null,
    memory: Memory? = null,
    tools: List<Tool>? = null
): Subagent {
    return DefaultSubagent(name, description, instruction, maxIterations, memory, tools)
}
