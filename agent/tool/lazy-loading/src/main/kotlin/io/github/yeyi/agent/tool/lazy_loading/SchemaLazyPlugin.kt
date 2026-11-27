package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.capability.CapabilityPlugin
import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext

/**
 * SCHEMA level 的 LazyTool 接线模板。
 *
 * 内部使用 [CapabilityPlugin] 架构，安装 `load_lazy_tool` 工具。
 *
 * @param registry SCHEMA level 视图（或完整 registry），由 [lazyTools] 传入
 */
internal class SchemaLazyPlugin(
    registry: CapabilityRegistry<LazyTool, Unit, SchemaLazyContext>,
    private val dynamicToolCaller: DynamicToolCaller,
) : CapabilityPlugin<LazyTool, Unit, SchemaLazyContext>(registry, true) {

    override fun contextFactory(): SchemaLazyContextFactory = SchemaLazyContextFactory()

    override fun auxiliaryTools(): List<Tool> = listOf(dynamicToolCaller)
}

public class SchemaLazyContext : CapabilityContext

internal class SchemaLazyContextFactory : CapabilityContextFactory<SchemaLazyContext> {
    override fun create(context: ToolExecutionContext): SchemaLazyContext = SchemaLazyContext()
}
