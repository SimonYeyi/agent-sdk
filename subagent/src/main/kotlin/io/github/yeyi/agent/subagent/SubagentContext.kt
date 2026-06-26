package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.ToolContext


/**
 * Subagent 的 CapabilityContext。
 *
 * @param agentContext 透传 main agent 上下文(LlmProvider/Hook/Memory)
 */
public class SubagentContext(public val agentContext: AgentContext) : CapabilityContext

/**
 * [SubagentContext] 的工厂实现，供 Adapter 使用。
 */
internal class SubagentContextFactory : CapabilityContextFactory<SubagentContext> {
    override fun create(context: ToolContext): SubagentContext {
        return SubagentContext(context.agentContext)
    }
}
