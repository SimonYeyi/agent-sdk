package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool

public abstract class SimpleSubagent(
    override val name: String,
    override val description: String,
    override val maxIterations: Int? = null,
    override val memory: Memory? = null,
    override val tools: List<Tool>? = null
) : Subagent {
    public abstract fun load(): String

    override fun load(context: SubagentContext): String = load()
}
