package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.ToolContext

/**
 * [Toolset] 能力的 [CapabilityContext]。
 *
 * [Toolset.activate] 只返回子工具列表，不需要透传 agent 上下文，所以这里只是空标记类。
 */
public class ToolsetContext : CapabilityContext

/**
 * [ToolsetContext] 的工厂实现，供 [io.github.yeyi.agent.capability.CapabilityAdapter] 使用。
 */
internal class ToolsetContextFactory : CapabilityContextFactory<ToolsetContext> {
    override fun create(context: ToolContext): ToolsetContext = ToolsetContext()
}
