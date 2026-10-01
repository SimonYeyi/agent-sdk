package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.ToolExecutionContext

/**
 * LazyTool 执行时的上下文，继承自 [io.github.yeyi.agent.capability.CapabilityContext]。
 *
 * 当前为空标记类，保留扩展余地。
 */
public class LazyToolContext : CapabilityContext

internal class LazyToolContextFactory : CapabilityContextFactory<LazyToolContext> {
    override fun create(context: ToolExecutionContext): LazyToolContext = LazyToolContext()
}
