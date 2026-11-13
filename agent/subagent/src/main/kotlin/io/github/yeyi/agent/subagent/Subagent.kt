package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool
import kotlinx.serialization.json.JsonNull

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
     * 在 [run] 构造子 agent 前调用，返回值作为子 agent 的 [io.github.yeyi.agent.AgentPersona] role 文本。
     * 可实现为从文件/网络/应用状态动态加载。
     */
    public suspend fun load(): String

    override suspend fun activate(arguments: SubagentTask?, context: SubagentContext): String {
        arguments?.task ?: throw IllegalArgumentException("Missing 'task' argument")
        return run(arguments, context)
    }

    public suspend fun run(subagentTask: SubagentTask, context: SubagentContext): String {
        val memory = memory ?: InMemoryMemory()
        val resolvedTools = (tools ?: context.agentContext.tools)
            .filter { !it.name.contains(CAPABILITY_TYPE) }
        val instruction = load()

        val sub = agent {
            persona(AgentPersona(instruction).personality("寡言少语"))
            llmProvider(context.agentContext.llmProvider)
            memory(memory, context.agentContext.maxRounds)
            maxIterations(maxIterations ?: context.agentContext.maxIterations)
            tools(resolvedTools)
        }

        val userMessage = if (subagentTask.context.isNullOrEmpty()) {
            subagentTask.task
        } else {
            "${subagentTask.context}\n\n${subagentTask.task}"
        }
        return sub.run(AgentQuery.text(userMessage)).awaitResult()
            .contentNotNull().message.content!!
    }

    public companion object {
        internal const val CAPABILITY_TYPE: String = "subagent"
    }
}

/**
 * 保证 `AgentResult.message.content` 可读: 非空原样返回; 为 null(仅出现在可终结工具短路路径)
 * 时以最后一次迭代那批工具的参数与结果文本部分按序拼装。
 *
 * 子 agent(被调度的下游 agent)的 Final 可能 content 为 null —— 跑了工具短路、没生成最终
 * 正文,但上游消费方(父 agent 的 context prepend)需要知晓执行结果,故在此
 * 以最后一轮工具调用补齐。顶层 agent 直接对用户时 content 为 null 无须处理，不走此兜底。
 *
 * 取「最后一次迭代」而非全部记录: 早期批次的中间结果不属于本次产出。
 *
 * 参数也拼入: 可终结工具短路时, 终态信息可能承载在参数里, 工具结果本身可能为空或仅状态
 * 确认 —— 只取结果会丢掉关键语义。JsonNull / `{}` / 空白视为无参数,输出 `name()`。
 */
public fun AgentResult.contentNotNull(): AgentResult {
    if (message.content != null) return this
    val text = buildString {
        appendLine("最后一轮工具调用情况：")
        iterationRecords.last().calls.forEachIndexed { i, call ->
            val args = call.arguments.takeIf {
                it !is JsonNull && it.toString().let { s -> s.isNotBlank() && s != "{}" }
            }?.toString().orEmpty()
            val output = call.result.parts.filterIsInstance<ContentPart.Text>()
                .joinToString("\n") { it.text }
            appendLine("[${i + 1}] 工具调用：${call.toolName}($args)")
            if (output.isNotEmpty()) appendLine("调用结果：$output")
        }
    }.trimEnd()
    return copy(message = message.copy(content = text))
}
