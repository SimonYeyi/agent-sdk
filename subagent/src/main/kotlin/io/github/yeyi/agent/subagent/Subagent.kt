package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool
import kotlinx.serialization.Serializable

/**
 * Task 输入类型，由 [SubagentArguments] 提供 schema 和 serializer。
 */
@Serializable
public data class SubagentTask(val task: String)

/**
 * Subagent 的 CapabilityContext。
 *
 * @param agentContext 透传 main agent 上下文(LlmProvider/Hook/Memory)
 */
public class SubagentContext(public val agentContext: AgentContext) : CapabilityContext

/**
 * Subagent = 独立 LLM 循环 + 任务委托能力。
 *
 * 实现 [Capability] 接口，泛型 [SubagentTask] 是 arguments 的强类型表达，
 * 由 [SubagentArguments] 提供 schema 和 serializer 给 Adapter。
 */
public interface Subagent : Capability<SubagentTask, SubagentContext> {
    public val maxIterations: Int
    public val memory: Memory?

    public val tools: List<Tool>

    public fun loadInstructions(): String

    override suspend fun activate(arguments: SubagentTask?, context: SubagentContext): String {
        val task = arguments?.task
            ?: throw IllegalArgumentException("Missing 'task' argument")

        val memory = memory ?: InMemoryMemory()
        val instructions = loadInstructions()
        val resolvedTools = tools.takeIf { it.isNotEmpty() }
            ?: context.agentContext.tools.filter { !it.name.contains(NAME) }

        val sub = agent {
            persona(Persona(instructions))
            llmProvider(context.agentContext.llmProvider)
            memory(memory, context.agentContext.maxRounds)
            maxIterations(maxIterations)
            tools(resolvedTools)
        }

        return sub.run(task).awaitResult().message.content
            ?: throw IllegalStateException("Subagent '${name}' returned empty content")
    }

    public companion object {
        public const val NAME: String = "subagent"
    }
}
