package io.github.yeyi.agent.tool

import io.github.yeyi.agent.AgentContext

/**
 * Tool 执行时的运行时上下文。
 *
 * @param toolCallId LLM 给本次调用的唯一 id，用于回写 tool result message
 * @param agentContext 当前 agent 上下文（非空），tool 可访问 llmProvider/hook/memory 等
 */
public data class ToolContext(
    public val toolCallId: String,
    public val agentContext: AgentContext,
)
