package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.ToolContext

/**
 * [SubagentContext] 的工厂实现，供 Adapter 使用。
 */
internal class SubagentContextFactory : CapabilityContextFactory<SubagentContext> {
    override fun create(context: ToolContext): SubagentContext {
        return SubagentContext(context.agentContext)
    }
}
