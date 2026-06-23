package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.ToolContext

/**
 * [SubagentContext] 的工厂实现，供 Adapter 使用。
 */
public class SubagentContextFactory(
    private val maxIterations: Int = 5,
) : CapabilityContextFactory<SubagentContext> {
    override fun create(context: ToolContext): SubagentContext {
        return SubagentContext(
            agentContext = context.agentContext,
            maxIterations = maxIterations,
        )
    }
}
