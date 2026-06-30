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
    /** 最大迭代次数，null 时继承父 agent 的 [io.github.yeyi.agent.AgentContext.maxIterations]。 */
    public val maxIterations: Int?

    /** 子 agent 使用的 Memory，null 时默认 [io.github.yeyi.agent.memory.InMemoryMemory]。 */
    public val memory: Memory?

    /** 子 agent 可用的工具列表，null 时继承父 agent 注册的所有非 MCP 工具。 */
    public val tools: List<Tool>?

    /**
     * 加载子 agent 的人设指令文本。
     *
     * 在 [run] 构造子 agent 前调用，返回值作为子 agent 的 [io.github.yeyi.agent.Persona] role 文本。
     * 可实现为从文件/网络/应用状态动态加载。
     */
    public fun load(context: SubagentContext): String

    override suspend fun activate(arguments: SubagentTask?, context: SubagentContext): String {
        arguments?.task ?: throw IllegalArgumentException("Missing 'task' argument")
        return run(arguments, context)
    }

    public suspend fun run(subagentTask: SubagentTask, context: SubagentContext): String {
        val memory = memory ?: InMemoryMemory()
        val resolvedTools = tools
            ?: context.agentContext.tools.filter { !it.name.contains(CAPABILITY_NAME) }
        val instruction = load(context)

        val sub = agent {
            persona(Persona(instruction))
            llmProvider(context.agentContext.llmProvider)
            memory(memory, context.agentContext.maxRounds)
            maxIterations(maxIterations ?: context.agentContext.maxIterations)
            tools(resolvedTools)
        }

        return sub.run(subagentTask.task).awaitResult().message.content
            ?: throw IllegalStateException("Subagent '${name}' returned empty content")
    }

    public companion object {
        public const val CAPABILITY_NAME: String = "subagent"
    }
}
