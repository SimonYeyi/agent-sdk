package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.capability.CapabilityContext
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
 * @param maxIterations subagent 独立预算,默认 5
 */
public class SubagentContext(
    public val agentContext: AgentContext,
    public val maxIterations: Int,
) : CapabilityContext

/**
 * Subagent = 独立 LLM 循环 + 任务委托能力。
 *
 * 实现 [Capability] 接口，泛型 [SubagentTask] 是 arguments 的强类型表达，
 * 由 [SubagentArguments] 提供 schema 和 serializer 给 Adapter。
 */
public interface Subagent : Capability<SubagentTask, SubagentContext>
