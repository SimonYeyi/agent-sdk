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
