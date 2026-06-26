package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool

/**
 * Subagent = 独立 LLM 循环 + 任务委托能力。
 *
 * 实现 [Capability] 接口，泛型 [SubagentTask] 是 arguments 的强类型表达，
 * 由 [SubagentArguments] 提供 schema 和 serializer 给 Adapter。
 */
public interface Subagent : Capability<SubagentTask, SubagentContext> {
    public val maxIterations: Int?
    public val memory: Memory?
    public val tools: List<Tool>?

    public fun load(context: SubagentContext): String

    override suspend fun activate(arguments: SubagentTask?, context: SubagentContext): String {
        val task = arguments?.task
            ?: throw IllegalArgumentException("Missing 'task' argument")

        val memory = memory ?: InMemoryMemory()
        val resolvedTools = tools
            ?: context.agentContext.tools.filter { !it.name.contains(CAPABILITY_NAME) }
        val instructions = load(context)

        val sub = agent {
            persona(Persona(instructions))
            llmProvider(context.agentContext.llmProvider)
            memory(memory, context.agentContext.maxRounds)
            maxIterations(maxIterations ?: context.agentContext.maxIterations)
            tools(resolvedTools)
        }

        return sub.run(task).awaitResult().message.content
            ?: throw IllegalStateException("Subagent '${name}' returned empty content")
    }

    public companion object {
        public const val CAPABILITY_NAME: String = "subagent"
    }
}
