package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.capability.CapabilityPlugin
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext

/**
 * SCHEMA level 的 LazyTool 接线模板。
 *
 * 内部使用 [CapabilityPlugin] 架构，安装 `load_lazy_tool` 工具。
 */
internal class SchemaLazyPlugin(
    registry: LazyToolRegistry,
    private val toolCaller: ToolCaller,
) : CapabilityPlugin<LazyTool, Unit, SchemaLazyContext>(registry, true) {

    override fun contextFactory(): SchemaLazyContextFactory = SchemaLazyContextFactory()

    override fun auxiliaryTools(): List<Tool> = listOf(toolCaller)
}

public class SchemaLazyContext : CapabilityContext

internal class SchemaLazyContextFactory : CapabilityContextFactory<SchemaLazyContext> {
    override fun create(context: ToolExecutionContext): SchemaLazyContext = SchemaLazyContext()
}
